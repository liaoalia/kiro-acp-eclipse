package io.github.liaoalia.kiroacp.core.session;

import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.liaoalia.kiroacp.core.acp.api.SessionEvent;
import io.github.liaoalia.kiroacp.core.acp.api.SessionState;
import io.github.liaoalia.kiroacp.core.model.NoticeView;
import io.github.liaoalia.kiroacp.core.model.ToWebview;
import io.github.liaoalia.kiroacp.core.pure.text.JsText;
import io.github.liaoalia.kiroacp.core.pure.transcript.ApplyResult;
import io.github.liaoalia.kiroacp.core.pure.transcript.Transcript;

/**
 * What the agent reports about its sessions, turned into transcript changes
 * and page messages ({@code onSessionEvent} in the reference). Runs on the
 * controller thread, one event at a time in the order the agent wrote them;
 * nothing is coalesced, as in the reference.
 */
final class SessionEvents {

	/** Session-metadata updates that say nothing about when the session was last used. */
	private static final Set<String> METADATA_UPDATES = Set.of("available_commands_update", "current_mode_update",
			"config_option_update", "session_info_update", "usage_update");

	private final ChatController c;

	SessionEvents(ChatController controller) {
		this.c = controller;
	}

	void handle(SessionEvent event) {
		if (event instanceof SessionEvent.Update update) {
			onUpdate(update);
		} else if (event instanceof SessionEvent.State state) {
			ChatSession session = c.sessions.get(state.session().sessionId());
			if (session != null) {
				applySessionState(session, state.session());
			}
		} else if (event instanceof SessionEvent.ExtensionNotification notification) {
			c.logger.info("[ext] " + notification.method() + " " + notification.params());
		}
		// Turn events: the turn is driven by the prompt's own future, so the page sees its end in order.
	}

	private void onUpdate(SessionEvent.Update event) {
		ChatSession session = c.sessions.get(event.sessionId());
		JsonNode update = event.update();
		if (session == null || update == null) {
			return;
		}
		boolean replay = event.replay();
		String kind = update.path("sessionUpdate").asText("");
		boolean activity = !METADATA_UPDATES.contains(kind);
		if (session.abandonedTurn && !session.turnActive && !replay && activity) {
			// The agent resumed streaming the turn the Stop watchdog ended (reference D27):
			// with no turn running, this is that turn's late output, not a reply.
			if (!session.abandonedDropLogged) {
				session.abandonedDropLogged = true;
				c.logger.info("session " + session.id() + ": dropping late " + kind + " of the stopped turn");
			}
			return;
		}
		// Only activity counts as "recent": the metadata an agent sends right after
		// session/new must not reorder the session list.
		if (activity) {
			c.touch(session);
		}
		c.listing.schedule();
		// Seam (reference D22, D26): usage and steering read the updates the transcript does not show.
		c.hooks.each(listener -> listener.update(session.id(), update, replay));

		if ("user_message_chunk".equals(kind)) {
			// Live user chunks are echoed locally when the prompt is sent (reference D13);
			// replayed history must be rendered (D21).
			if (!replay) {
				return;
			}
			JsonNode content = update.path("content");
			if (session.firstPrompt == null && "text".equals(content.path("type").asText(null)) && content.path("text").isTextual()
					&& !JsText.trim(content.path("text").textValue()).isEmpty()) {
				session.firstPrompt = Titles.truncate(content.path("text").textValue());
			}
		}
		if ("session_info_update".equals(kind)) {
			JsonNode title = update.get("title");
			if (title != null && title.isTextual()) {
				String trimmed = JsText.trim(title.textValue());
				session.agentTitle = trimmed.isEmpty() ? null : trimmed;
			} else if (title != null && title.isNull()) {
				session.agentTitle = null;
			}
			return;
		}

		ApplyResult result = Transcript.applyUpdate(session.transcript, update);
		session.transcript = result.state();
		for (ToWebview emit : result.emits()) {
			c.postTo(session.id(), emit instanceof ToWebview.Commands ? new ToWebview.Commands(c.commandsOf(session)) : emit);
			if (emit instanceof ToWebview.ToolCall tool) {
				// Replayed tool calls are history: nothing to open or reconcile for them.
				if (replay) {
					session.replayedToolCalls.add(tool.call().toolCallId());
				} else {
					c.hooks.each(listener -> listener.toolCallChanged(session.id(), tool.call()));
				}
			}
			if (emit instanceof ToWebview.Plan plan && session.id().equals(c.focusedId)) {
				c.hooks.each(listener -> listener.planChanged(session.id(), plan.entries()));
			}
		}
		if ("notice".equals(kind)) {
			if (update.path("title").isTextual()) {
				String severity = update.path("severity").asText("");
				c.tell(session, "error".equals(severity) ? NoticeView.Severity.ERROR
						: "warning".equals(severity) ? NoticeView.Severity.WARNING : NoticeView.Severity.INFO,
						update.path("title").textValue());
			}
		} else if (result.ignored() != null && !"usage_update".equals(kind)) {
			c.logger.info("session " + session.id() + ": " + result.ignored());
		}
	}

	/** Merge a {@code SessionState} (session/new, session/load, state events) into the session and tell its pages. */
	void applySessionState(ChatSession session, SessionState state) {
		session.state = state;
		session.transcript = Transcript.withSessionSnapshot(session.transcript, state);
		session.models = Transcript.toModelView(state.models());
		c.postTo(session.id(), new ToWebview.Modes(session.transcript.modes()));
		c.postTo(session.id(), new ToWebview.ConfigOptions(session.transcript.configOptions()));
		c.postTo(session.id(), new ToWebview.Models(session.models));
		c.postTo(session.id(), new ToWebview.Commands(c.commandsOf(session)));
	}
}
