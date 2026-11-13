package io.github.liaoalia.kiroacp.core.session.steering;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.liaoalia.kiroacp.core.acp.api.AcpException;
import io.github.liaoalia.kiroacp.core.acp.api.KiroAgent;
import io.github.liaoalia.kiroacp.core.host.Logger;
import io.github.liaoalia.kiroacp.core.host.UiScheduler;
import io.github.liaoalia.kiroacp.core.model.ConnectionStatus;
import io.github.liaoalia.kiroacp.core.model.DeliveryState;
import io.github.liaoalia.kiroacp.core.model.NoticeView.Severity;
import io.github.liaoalia.kiroacp.core.pure.context.Images;
import io.github.liaoalia.kiroacp.core.session.ChatController;
import io.github.liaoalia.kiroacp.core.session.ChatHooks;
import io.github.liaoalia.kiroacp.core.session.ChatHooks.PromptRequest;

/**
 * Prompts typed while a turn runs (reference D22): steered into the running
 * turn when the agent takes them, else held and sent as turns of their own
 * once it has ended.
 *
 * <p><b>Steering.</b> The message is echoed with the badge {@code steering}
 * and sent through the Kiro extension method {@value #STEER_METHOD} with the
 * blocks a {@code session/prompt} would carry. The badge goes when the
 * session reports {@code steering_injected} or {@code steering_cleared}, or
 * when the turn ends; the agent does not say which steered message it means,
 * so all of them clear together.
 *
 * <p><b>Queue.</b> When the agent refuses, for whatever reason, the message
 * is badged {@code queued} and held, and later prompts of that session queue
 * at once instead of asking again. When the turn ends with a stop reason,
 * {@code cancelled} included (Stop ends the current answer only), the first
 * queued prompt starts the next turn, without a second echo; the next one
 * follows that turn. A turn that failed or lost its agent drops the queue,
 * and so does a session that is being closed or replaced, with a notice
 * saying how many messages were discarded. Nothing is ever sent to a session
 * that is on its way out, not even by a refusal that arrives late.
 *
 * <p>Controller thread only.
 */
public final class Steering {

	/** Kiro CLI V3; not advertised by the agent, so it is simply tried. */
	public static final String STEER_METHOD = "_session/steer";

	private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

	/** A prompt held until the turn ends. */
	private record Queued(List<JsonNode> blocks, String messageId) {
	}

	private static final class State {
		final String sessionId;
		final Deque<Queued> queue = new ArrayDeque<>();
		/** View ids of the messages sent through {@value #STEER_METHOD} and not yet injected. */
		final Set<String> steering = new LinkedHashSet<>();
		/** The agent refused to steer a running turn of this session: later prompts queue directly. */
		boolean steerUnsupported;
		/** The session is gone, or going: whatever still arrives for it is dropped. */
		boolean closed;

		State(String sessionId) {
			this.sessionId = sessionId;
		}
	}

	private final ChatController controller;
	private final UiScheduler scheduler;
	private final Logger logger;
	private final Map<String, State> states = new HashMap<>();

	private Steering(ChatController controller) {
		this.controller = controller;
		this.scheduler = controller.host().scheduler();
		this.logger = controller.host().logger() != null ? controller.host().logger() : Logger.NONE;
	}

	/** Take the controller's prompts during a turn; its pages offer their composer while a turn runs from here on. */
	public static Steering install(ChatController controller) {
		Steering steering = new Steering(controller);
		controller.hooks().addListener(steering.new Listener());
		controller.hooks().setMidTurnPrompts(steering::prompt);
		return steering;
	}

	/** Test hook, as in the reference: pretend the agent already refused to steer this session, so its prompts queue. */
	public void setSteerUnsupported(String sessionId, boolean unsupported) {
		if (controller.sessionOpen(sessionId)) {
			state(sessionId).steerUnsupported = unsupported;
		}
	}

	private final class Listener implements ChatHooks.SessionListener {

		@Override
		public void update(String sessionId, JsonNode update, boolean replay) {
			if (!"session_info_update".equals(update.path("sessionUpdate").asText(""))) {
				return;
			}
			String kind = update.path("_meta").path("kiro").path("kind").asText("");
			if (kind.equals("steering_injected") || kind.equals("steering_cleared")) {
				logger.info("steer " + sessionId + ": " + kind);
				State state = states.get(sessionId);
				if (state != null) {
					clearSteering(state);
				}
			}
		}

		@Override
		public void turnEnded(String sessionId, String stopReason) {
			State state = states.get(sessionId);
			if (state == null) {
				return;
			}
			clearSteering(state);
			if ("error".equals(stopReason)) {
				// The turn failed or the agent went away: what waited for its end is not sent blindly.
				tellDiscarded(state, discardRemaining(state));
			}
		}

		@Override
		public void afterTurn(String sessionId, String stopReason) {
			State state = states.get(sessionId);
			if (state != null) {
				drain(state);
			}
		}

		@Override
		public void sessionReplacing(String sessionId) {
			State state = states.get(sessionId);
			if (state != null) {
				// Before the turn is cancelled: its end must find nothing to send.
				tellDiscarded(state, discardRemaining(state));
			}
		}

		@Override
		public void sessionClosing(String sessionId) {
			State state = states.remove(sessionId);
			if (state != null) {
				// Now, while a page may still display the session.
				tellDiscarded(state, discardRemaining(state));
				state.closed = true;
			}
		}
	}

	private State state(String sessionId) {
		return states.computeIfAbsent(sessionId, State::new);
	}

	/** Whether nothing may be sent to the session any more: it is closed, closing or being replaced. */
	private boolean leaving(State state) {
		return state.closed || states.get(state.sessionId) != state || !controller.sessionOpen(state.sessionId);
	}

	// ---------------------------------------------------------------------
	// A prompt during a turn
	// ---------------------------------------------------------------------

	/** @return completes once the message is with the agent or in the queue, not when it was answered */
	private CompletionStage<Void> prompt(PromptRequest request, List<JsonNode> blocks) {
		String sessionId = request.sessionId();
		if (blocks.isEmpty()) {
			logger.info("session " + sessionId + ": nothing to send for the prompt");
			return CompletableFuture.completedFuture(null);
		}
		if (!controller.sessionOpen(sessionId)) {
			return CompletableFuture.completedFuture(null);
		}
		State state = state(sessionId);
		KiroAgent agent = controller.agent();
		if (state.steerUnsupported || agent == null) {
			String messageId = controller.echoPrompt(request, DeliveryState.QUEUED);
			enqueue(state, new Queued(List.copyOf(blocks), messageId));
			return CompletableFuture.completedFuture(null);
		}
		String messageId = controller.echoPrompt(request, DeliveryState.STEERING);
		Queued prompt = new Queued(List.copyOf(blocks), messageId);
		state.steering.add(messageId);
		int generation = controller.turnGeneration(sessionId);
		ObjectNode params = JSON.objectNode().put("sessionId", sessionId);
		params.putArray("prompt").addAll(blocks);
		logger.info("steer " + sessionId + ": " + STEER_METHOD + " " + withoutImageData(params));
		CompletableFuture<JsonNode> call;
		try {
			call = agent.callExtension(STEER_METHOD, params);
		} catch (RuntimeException e) {
			call = CompletableFuture.failedFuture(e);
		}
		CompletableFuture<Void> settled = new CompletableFuture<>();
		// Through the controller's queue: whatever the agent said before its answer is handled first.
		call.whenComplete((result, error) -> scheduler.execute(() -> {
			try {
				if (error == null) {
					logger.info("steer " + sessionId + ": accepted " + result);
				} else {
					refused(state, prompt, generation, unwrap(error));
				}
			} finally {
				settled.complete(null);
			}
		}));
		return settled;
	}

	/**
	 * The agent did not take the steered message. It was typed and is still
	 * undelivered, so it is queued. Only a refusal for the turn that is still
	 * running says that the agent cannot steer: one that lands after the turn
	 * ended (Stop, an error, a normal end while the agent was slow to answer)
	 * says nothing about that, and the message goes out as the next turn.
	 */
	private void refused(State state, Queued prompt, int generation, Throwable error) {
		String sessionId = state.sessionId;
		String code = error instanceof AcpException acp && acp.code() != null ? " (" + acp.code() + ")" : "";
		logger.info("steer " + sessionId + ": failed" + code + ": " + message(error) + "; queueing mid-turn prompts instead");
		state.steering.remove(prompt.messageId());
		if (leaving(state)) {
			// The session is gone or on its way out: nothing may start a turn on it.
			clearBadge(state, prompt.messageId());
			return;
		}
		if (controller.turnGeneration(sessionId) != generation || !controller.turnActive(sessionId)) {
			logger.info("steer " + sessionId + ": the turn ended before the refusal; sending as a prompt");
		} else {
			state.steerUnsupported = true;
		}
		controller.setDelivery(sessionId, prompt.messageId(), DeliveryState.QUEUED);
		enqueue(state, prompt);
	}

	// ---------------------------------------------------------------------
	// The queue
	// ---------------------------------------------------------------------

	/** Hold the prompt until the turn ends; sent right away when the turn ended meanwhile. */
	private void enqueue(State state, Queued prompt) {
		if (leaving(state)) {
			clearBadge(state, prompt.messageId());
			tellDiscarded(state, 1);
			return;
		}
		state.queue.add(prompt);
		if (!controller.turnActive(state.sessionId)) {
			drain(state);
		}
	}

	/** Send the next queued prompt of an idle session as a turn of its own. */
	private void drain(State state) {
		String sessionId = state.sessionId;
		if (leaving(state) || controller.turnActive(sessionId)) {
			return;
		}
		Queued next = state.queue.poll();
		if (next == null) {
			return;
		}
		controller.setDelivery(sessionId, next.messageId(), null);
		KiroAgent agent = controller.agent();
		if (agent == null || agent.status() != ConnectionStatus.READY) {
			tellDiscarded(state, 1 + discardRemaining(state));
			return;
		}
		// The echo happened during the turn, so the previous answer may still be open:
		// the new turn's answer starts a message of its own.
		controller.closeOpenMessage(sessionId);
		CompletableFuture<Boolean> turn = controller.startTurn(sessionId, next.blocks());
		if (turn.isDone() && !turn.isCompletedExceptionally() && !Boolean.TRUE.equals(turn.join())) {
			tellDiscarded(state, 1 + discardRemaining(state));
			return;
		}
		turn.whenComplete((started, error) -> {
			if (error != null) {
				logger.error("session " + sessionId + ": the turn of a queued prompt failed: " + message(error), unwrap(error));
			}
		});
	}

	/** Clear the badges of every queued prompt and forget them. @return how many were dropped */
	private int discardRemaining(State state) {
		List<Queued> dropped = new ArrayList<>(state.queue);
		state.queue.clear();
		for (Queued prompt : dropped) {
			clearBadge(state, prompt.messageId());
		}
		return dropped.size();
	}

	private void tellDiscarded(State state, int count) {
		if (count > 0) {
			controller.notice(state.sessionId, Severity.INFO, count + " queued message" + (count == 1 ? "" : "s") + " discarded.");
		}
	}

	/** The agent injected (or dropped) the steered prompts, or the turn is over. */
	private void clearSteering(State state) {
		List<String> steered = List.copyOf(state.steering);
		state.steering.clear();
		for (String messageId : steered) {
			clearBadge(state, messageId);
		}
	}

	private void clearBadge(State state, String messageId) {
		// Not when the id belongs to another session by now (closed and restored meanwhile).
		State current = states.get(state.sessionId);
		if (current == null || current == state) {
			controller.setDelivery(state.sessionId, messageId, null);
		}
	}

	// ---------------------------------------------------------------------
	// Log
	// ---------------------------------------------------------------------

	/** The steer request for the log: each image's base64 payload replaced by its size (reference D29). */
	static JsonNode withoutImageData(ObjectNode params) {
		ObjectNode copy = params.deepCopy();
		JsonNode prompt = copy.get("prompt");
		if (prompt instanceof ArrayNode blocks) {
			for (JsonNode block : blocks) {
				if (block instanceof ObjectNode image && "image".equals(image.path("type").asText(null))
						&& image.path("data").isTextual()) {
					image.put("data", "<" + Images.formatBytes(Images.base64Size(image.get("data").textValue())) + " of base64>");
				}
			}
		}
		return copy;
	}

	private static Throwable unwrap(Throwable error) {
		Throwable current = error;
		while ((current instanceof CompletionException || current instanceof ExecutionException) && current.getCause() != null) {
			current = current.getCause();
		}
		return current;
	}

	private static String message(Throwable error) {
		Throwable cause = unwrap(error);
		return cause.getMessage() != null && !cause.getMessage().isEmpty() ? cause.getMessage() : cause.toString();
	}
}
