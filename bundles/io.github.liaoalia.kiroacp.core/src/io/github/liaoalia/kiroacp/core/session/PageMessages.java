package io.github.liaoalia.kiroacp.core.session;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import io.github.liaoalia.kiroacp.core.acp.api.KiroAgent;
import io.github.liaoalia.kiroacp.core.model.FromWebview;
import io.github.liaoalia.kiroacp.core.model.NoticeView.Severity;
import io.github.liaoalia.kiroacp.core.model.ToWebview;
import io.github.liaoalia.kiroacp.core.model.UiJson;
import io.github.liaoalia.kiroacp.core.pure.permission.PolicyDecision;
import io.github.liaoalia.kiroacp.core.session.ChatHooks.Option;

/**
 * What the pages send ({@code handle} in the reference): the messages of the
 * core chat are acted on here, every other one goes to the handler a feature
 * registered for it. Controller thread only.
 */
final class PageMessages {

	/** Page messages that act on the page's session and may therefore need one assigned first. */
	private static final Set<Class<? extends FromWebview>> NEEDS_SESSION = Set.of(FromWebview.Prompt.class,
			FromWebview.SetMode.class, FromWebview.SetConfigOption.class, FromWebview.SetModel.class, FromWebview.Cancel.class,
			FromWebview.NewSession.class, FromWebview.SelectSession.class);

	private final ChatController c;

	PageMessages(ChatController controller) {
		this.c = controller;
	}

	/** @return completes when the message has been dealt with; for a prompt, when its turn has ended */
	CompletableFuture<Void> dispatch(FromWebview message, PageBinding page) {
		if (c.closed || message == null || !c.pages.contains(page)) {
			return Async.done(null);
		}
		if (message instanceof FromWebview.Ready) {
			c.postPage(page, new ToWebview.State(c.snapshots.forPage(page)));
			return c.roster.ensurePageSession(page);
		}
		// Only messages that act on a session may assign one, and never while the agent is
		// down: `restart` and `ready` own the process, and "Show log" after a failed start
		// must not spawn the CLI again.
		CompletableFuture<Void> assigned = NEEDS_SESSION.contains(message.getClass()) && page.displayed == null && !c.lifecycle.agentDown()
				? c.roster.assignSession(page)
				: Async.done(null);
		return assigned.thenCompose(done -> {
			c.focusPage(page);
			return act(message, page);
		});
	}

	private CompletableFuture<Void> act(FromWebview message, PageBinding page) {
		String sessionId = page.displayed;
		ChatSession session = sessionId != null ? c.sessions.get(sessionId) : null;
		if (message instanceof FromWebview.Prompt m) {
			return c.turns.prompt(m.text(), m.chips(), sessionId, page, m.images());
		}
		if (message instanceof FromWebview.Cancel) {
			return c.turns.cancel(sessionId);
		}
		if (message instanceof FromWebview.NewSession) {
			return c.roster.doNewSession(page).thenApply(id -> null);
		}
		if (message instanceof FromWebview.SelectSession m) {
			c.roster.doSelectSession(m.sessionId(), page);
		} else if (message instanceof FromWebview.CloseSession m) {
			return c.roster.doCloseSession(m.sessionId());
		} else if (message instanceof FromWebview.Restart) {
			return c.lifecycle.doRestart(false);
		} else if (message instanceof FromWebview.PermissionResponse m) {
			try {
				c.permissions.resolve(m.requestId(), m.optionId());
			} catch (IllegalArgumentException e) {
				c.tell(session, Severity.WARNING, Async.message(e));
			}
		} else if (message instanceof FromWebview.PermissionAlways m) {
			if (m.decision() == null) {
				// Never guess what to remember for the rest of the session.
				c.logger.warn("permissionAlways without a decision ignored");
				return Async.done(null);
			}
			try {
				c.permissions.setPolicy(m.requestId(),
						m.decision() == FromWebview.Decision.REJECT ? PolicyDecision.REJECT : PolicyDecision.ALLOW, m.scope());
			} catch (IllegalArgumentException e) {
				c.tell(session, Severity.WARNING, Async.message(e));
			}
		} else if (message instanceof FromWebview.SetMode m) {
			return withSession(session, current -> current.setMode(sessionId, m.modeId()), Option.MODE, m.modeId(), null);
		} else if (message instanceof FromWebview.SetConfigOption m) {
			return withSession(session, current -> current.setConfigOption(sessionId, m.configId(), m.value()), Option.CONFIG,
					m.configId(), m.value());
		} else if (message instanceof FromWebview.SetModel m) {
			return withSession(session, current -> current.setModel(sessionId, m.modelId()), Option.MODEL, m.modelId(), null);
		} else if (message instanceof FromWebview.OpenFile m) {
			c.host.actions().openFile(m.path(), m.line());
		} else if (message instanceof FromWebview.ShowTerminal m) {
			if (session == null || !c.terminal.showTerminal(m.terminalId(), session.id())) {
				c.tell(session, Severity.INFO, "That terminal is no longer available.");
			}
		} else if (message instanceof FromWebview.ShowLog) {
			c.host.actions().showLog();
		} else if (!c.hooks.dispatch(message, page)) {
			// Seam: every other message belongs to a feature that registers its handler in ChatHooks.
			c.logger.info("page message without a handler: " + UiJson.toJson(message).path("type").asText("?"));
		}
		return Async.done(null);
	}

	CompletableFuture<Void> withSession(ChatSession session,
			Function<KiroAgent, CompletableFuture<?>> change, Option option, String id, JsonNode value) {
		KiroAgent current = c.agent;
		if (current == null || session == null) {
			return Async.done(null);
		}
		return c.async.ui(change.apply(current)).handle((done, error) -> {
			if (error != null) {
				c.tell(session, Severity.WARNING, Async.message(error));
			} else {
				// Seam (reference D24): the choice is remembered for new sessions.
				JsonNode chosen = value != null ? value : JsonNodeFactory.instance.textNode(id);
				c.hooks.each(listener -> listener.optionChosen(session.id(), option, id, chosen));
			}
			return null;
		});
	}
}
