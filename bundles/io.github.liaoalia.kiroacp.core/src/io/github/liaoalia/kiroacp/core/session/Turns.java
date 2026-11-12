package io.github.liaoalia.kiroacp.core.session;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.liaoalia.kiroacp.core.acp.api.KiroAgent;
import io.github.liaoalia.kiroacp.core.model.ConnectionStatus;
import io.github.liaoalia.kiroacp.core.model.ContextChip;
import io.github.liaoalia.kiroacp.core.model.DeliveryState;
import io.github.liaoalia.kiroacp.core.model.ImageAttachment;
import io.github.liaoalia.kiroacp.core.model.NoticeView.Severity;
import io.github.liaoalia.kiroacp.core.model.ToWebview;
import io.github.liaoalia.kiroacp.core.pure.text.JsText;
import io.github.liaoalia.kiroacp.core.pure.transcript.ApplyResult;
import io.github.liaoalia.kiroacp.core.pure.transcript.Transcript;
import io.github.liaoalia.kiroacp.core.session.ChatHooks.MidTurnPrompts;
import io.github.liaoalia.kiroacp.core.session.ChatHooks.PromptComposer;
import io.github.liaoalia.kiroacp.core.session.ChatHooks.PromptInterceptor;
import io.github.liaoalia.kiroacp.core.session.ChatHooks.PromptRequest;

/** Prompts and the life of a turn: echo, start, the agent's answer, Stop. Controller thread only. */
final class Turns {

	/** Stop: how long the agent gets to end the turn after {@code session/cancel} before it is ended here (reference D27). */
	static final long CANCEL_GRACE_MS = 5_000;

	private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

	private final ChatController c;

	Turns(ChatController controller) {
		this.c = controller;
	}

	// ---------------------------------------------------------------------
	// Prompting
	// ---------------------------------------------------------------------

	/**
	 * Send a prompt to {@code sessionId} (default: the session of
	 * {@code page}, else the focused session).
	 *
	 * @return completes when the turn has ended, or at once when nothing was sent
	 */
	CompletableFuture<Void> prompt(String text, List<ContextChip> chips, String sessionId, PageBinding page,
			List<ImageAttachment> images) {
		return c.lifecycle.ensureStarted()
				.thenCompose(started -> page != null && page.displayed == null ? c.roster.assignSession(page) : Async.done(null))
				.thenCompose(assigned -> send(text == null ? "" : text, chips == null ? List.of() : chips, sessionId, page,
						images == null ? List.of() : images));
	}

	private CompletableFuture<Void> send(String text, List<ContextChip> chips, String sessionId, PageBinding page,
			List<ImageAttachment> images) {
		KiroAgent agent = c.agent;
		ChatSession session = sessionId != null ? c.sessions.get(sessionId) : null;
		if (session == null && page != null && page.displayed != null) {
			session = c.sessions.get(page.displayed);
		}
		if (session == null && c.focusedId != null) {
			session = c.sessions.get(c.focusedId);
		}
		String trimmed = JsText.trim(text);
		if (agent == null || session == null || agent.status() != ConnectionStatus.READY) {
			// Seam (reference D18, D25): `/login` and `/restart` exist for exactly this state
			// and are run by an interceptor, which gets the prompt without a session.
			return intercept(new PromptRequest(null, trimmed, chips, images, page)).thenAccept(handled -> {
				if (!handled) {
					c.tell(null, Severity.WARNING, "No active session. Sign in or restart the agent, then try again.");
				}
			});
		}
		ChatSession target = session;
		if (target.closing) {
			// Seam (reference D22): `/clear` would hand the prompt on to the session replacing this one.
			c.tell(target, Severity.WARNING, "The session is closing; send the message again in a new one.");
			return Async.done(null);
		}
		c.focus(target.id());
		if (target.loading) {
			// Updates are flagged as replay until session/load resolves (reference D21),
			// so a prompt now would render its live answer as history.
			c.tell(target, Severity.INFO, "Wait for the session to finish restoring.");
			return Async.done(null);
		}
		if (trimmed.isEmpty() && chips.isEmpty() && images.isEmpty()) {
			return Async.done(null);
		}
		PromptRequest request = new PromptRequest(target.id(), trimmed, chips, images, page);
		PromptComposer composer = c.hooks.composer();
		// Seam (reference D18, D29): slash commands and the image checks run before anything is echoed.
		return intercept(request).thenCompose(handled -> handled ? Async.done(null)
				: c.async.ui(composer.blocks(request, c.agentInfo)).thenCompose(blocks -> dispatch(agent, target, request,
						composer, blocks == null ? List.of() : blocks)));
	}

	private CompletableFuture<Void> dispatch(KiroAgent agent, ChatSession session, PromptRequest request,
			PromptComposer composer, List<JsonNode> blocks) {
		if (session.turnActive) {
			MidTurnPrompts midTurn = c.hooks.midTurn();
			if (midTurn == null) {
				// Seam (reference D22): nothing steers or queues, so the prompt is refused as it was before steering existed.
				c.tell(session, Severity.INFO, "Wait for the current turn to finish or cancel it first.");
				return Async.done(null);
			}
			return c.async.ui(midTurn.prompt(request, blocks));
		}
		if (blocks.isEmpty()) {
			c.logger.info("session " + session.id() + ": nothing to send for the prompt");
			return Async.done(null);
		}
		if (session.firstPrompt == null && !request.text().isEmpty()) {
			session.firstPrompt = Titles.truncate(request.text());
		}
		echoUserMessage(session, request.text(), composer.echo(request), null);
		composer.sent(request);
		return runTurn(agent, session, blocks);
	}

	/** Asks the interceptors in turn; completes on the controller thread with whether one took the prompt. */
	private CompletableFuture<Boolean> intercept(PromptRequest request) {
		return intercept(request, c.hooks.interceptors(), 0);
	}

	private CompletableFuture<Boolean> intercept(PromptRequest request, List<PromptInterceptor> interceptors, int index) {
		if (index >= interceptors.size()) {
			return Async.done(false);
		}
		return c.async.ui(interceptors.get(index).intercept(request))
				.thenCompose(handled -> Boolean.TRUE.equals(handled) ? Async.done(true) : intercept(request, interceptors, index + 1));
	}

	/**
	 * {@code session/prompt} for a message that is already echoed: flips the
	 * turn state and reports the stop reason, or the failure, when the agent
	 * answers. Everything the agent streamed before its answer was queued on
	 * the controller thread before the answer, so the turn ends after it.
	 */
	CompletableFuture<Void> runTurn(KiroAgent agent, ChatSession session, List<JsonNode> blocks) {
		int generation = ++session.turnGeneration;
		setTurnActive(session, true, null);
		c.hooks.each(listener -> listener.turnStarted(session.id()));
		return c.async.ui(agent.prompt(session.id(), blocks)).handle((response, error) -> {
			if (session.turnGeneration != generation) {
				// Ended here meanwhile, by the Stop watchdog or because the agent went away; a later turn may be running.
				session.abandonedTurn = false;
				c.logger.info("session " + session.id() + ": late prompt " + (error == null
						? "response (" + stopReasonOf(response) + ") ignored"
						: "failure ignored: " + Async.message(error)));
				return null;
			}
			if (error == null) {
				handleStopReason(session, stopReasonOf(response));
				return null;
			}
			String message = Async.message(error);
			if (agent.status() == ConnectionStatus.AUTH_REQUIRED) {
				// The sign-in banner already shows the reason.
				c.logger.warn("session " + session.id() + " prompt rejected, sign-in required: " + message);
			} else {
				c.tell(session, Severity.ERROR, "Prompt failed: " + message);
			}
			endTurn(session, "error");
			return null;
		});
	}

	private static String stopReasonOf(JsonNode response) {
		JsonNode reason = response == null ? null : response.get("stopReason");
		return reason != null && reason.isTextual() ? reason.textValue() : null;
	}

	/**
	 * Show the user's message locally (reference D13: the agent's echo of it
	 * is ignored): the text, then one segment per extra content block. The
	 * text chunk is applied even when empty, so it opens the message.
	 *
	 * @param extras   ACP content blocks shown after the text (images, chips)
	 * @param delivery badge of the message, or {@code null}
	 * @return the message's view id, the one {@code chunk} and {@code messageDelivery} carry
	 */
	String echoUserMessage(ChatSession session, String text, List<JsonNode> extras, DeliveryState delivery) {
		String messageId = "user-" + (++session.userMessageSeq);
		List<ToWebview> emitted = applyLocalUpdate(session, userChunk(messageId, JSON.objectNode().put("type", "text").put("text", text)));
		String viewId = emitted.stream().filter(ToWebview.Chunk.class::isInstance).map(emit -> ((ToWebview.Chunk) emit).messageId())
				.findFirst().orElse(session.transcript.openMessage() != null ? session.transcript.openMessage().id() : messageId);
		for (JsonNode extra : extras) {
			applyLocalUpdate(session, userChunk(messageId, extra));
		}
		if (delivery != null) {
			setDelivery(session, viewId, delivery);
		}
		return viewId;
	}

	private static ObjectNode userChunk(String messageId, JsonNode content) {
		ObjectNode update = JSON.objectNode();
		update.put("sessionUpdate", "user_message_chunk");
		update.put("messageId", messageId);
		update.set("content", content);
		return update;
	}

	/** Update the delivery badge of a user message in the transcript and in the pages. */
	void setDelivery(ChatSession session, String messageId, DeliveryState delivery) {
		session.transcript = Transcript.setMessageDelivery(session.transcript, messageId, delivery);
		c.postTo(session.id(), new ToWebview.MessageDelivery(messageId, delivery));
	}

	/** Apply an update made here, not by the agent, and forward what it emits. */
	List<ToWebview> applyLocalUpdate(ChatSession session, JsonNode update) {
		ApplyResult result = Transcript.applyUpdate(session.transcript, update);
		session.transcript = result.state();
		c.touch(session);
		for (ToWebview emit : result.emits()) {
			c.postTo(session.id(), emit);
		}
		c.listing.schedule();
		return result.emits();
	}

	// ---------------------------------------------------------------------
	// Turn state
	// ---------------------------------------------------------------------

	private void setTurnActive(ChatSession session, boolean active, String stopReason) {
		session.turnActive = active;
		c.touch(session);
		if (session.id().equals(c.focusedId)) {
			c.host.actions().turnActiveChanged(active);
		}
		c.postTo(session.id(), new ToWebview.Turn(active, stopReason == null || stopReason.isEmpty() ? null : stopReason));
		c.listing.schedule();
	}

	/**
	 * The turn of {@code session} is over: {@code session/prompt} was
	 * answered or failed, the agent went away, or the Stop watchdog gave up.
	 *
	 * @param stopReason the agent's, or {@code error}
	 */
	void endTurn(ChatSession session, String stopReason) {
		session.turnGeneration++;
		session.stopCancelTimer();
		setTurnActive(session, false, stopReason);
		// Seam (reference D22): steering badges go here, and the queue is dropped after an error.
		c.hooks.each(listener -> listener.turnEnded(session.id(), stopReason));
	}

	private void handleStopReason(ChatSession session, String stopReason) {
		endTurn(session, stopReason);
		if ("refusal".equals(stopReason)) {
			c.tell(session, Severity.WARNING, "Kiro declined to continue with this request.");
		} else if ("max_tokens".equals(stopReason)) {
			c.tell(session, Severity.WARNING, "The response was cut off (token limit reached).");
		} else if ("max_turn_requests".equals(stopReason)) {
			c.tell(session, Severity.WARNING, "The turn stopped after reaching the request limit.");
		}
		c.permissions.cancelAll(session.id());
		// Seam (reference D22, D26): the next queued prompt goes out from here, and the usage is polled.
		c.hooks.each(listener -> listener.afterTurn(session.id(), stopReason));
	}

	// ---------------------------------------------------------------------
	// Stop
	// ---------------------------------------------------------------------

	/**
	 * Cancel the running turn of {@code sessionId} (default: the focused
	 * session). Local first (reference D27): the session's permission
	 * requests are answered, the agent is told, the commands still running
	 * for the session are killed (a tool waiting on a service that never
	 * exits would otherwise keep the turn open for ever), and a watchdog ends
	 * the turn here when the agent does not.
	 */
	CompletableFuture<Void> cancel(String sessionId) {
		String id = sessionId != null ? sessionId : c.focusedId;
		KiroAgent agent = c.agent;
		ChatSession session = id != null ? c.sessions.get(id) : null;
		if (agent == null || session == null) {
			return Async.done(null);
		}
		c.permissions.cancelAll(id);
		if (session.turnActive) {
			armCancelWatchdog(session);
		}
		return c.async.ui(agent.cancel(id)).handle((done, error) -> {
			if (error != null) {
				c.tell(session, Severity.WARNING, "Cancel failed: " + Async.message(error));
			}
			return null;
		}).thenCompose(told -> c.async.ui(c.terminal.killSession(id))).handle((killed, error) -> {
			if (error != null) {
				c.logger.warn("session " + id + " stop: could not kill its terminals: " + Async.message(error));
			} else if (killed != null && !killed.isEmpty()) {
				c.logger.info("session " + id + " stop: killed running terminal(s) " + String.join(", ", killed));
			}
			return null;
		});
	}

	private void armCancelWatchdog(ChatSession session) {
		if (session.cancelTimer != null) {
			return;
		}
		int generation = session.turnGeneration;
		session.cancelTimer = c.host.scheduler().schedule(CANCEL_GRACE_MS, () -> c.async.guarded(() -> {
			session.cancelTimer = null;
			// The same session object only: the id may have been closed and restored meanwhile.
			if (!session.turnActive || session.turnGeneration != generation || c.sessions.get(session.id()) != session) {
				return;
			}
			c.logger.warn("session " + session.id() + ": the agent did not end the turn within " + CANCEL_GRACE_MS
					+ " ms of session/cancel; ending it locally");
			session.abandonedTurn = true;
			c.tell(session, Severity.WARNING,
					"Kiro did not confirm the stop; the turn was ended here. Its late reply, if any, is ignored.");
			handleStopReason(session, "cancelled");
		}));
	}
}
