package io.github.liaoalia.kiroacp.core.acp.api;

import com.fasterxml.jackson.databind.JsonNode;

/** What happens in a session, delivered to listeners in the order the agent wrote it. */
public sealed interface SessionEvent {

	/**
	 * One {@code session/update}.
	 *
	 * @param update the ACP {@code SessionUpdate} object as sent, of a kind ACP defines. An update of
	 *               any other kind arrives as {@link ExtensionNotification} with the method
	 *               {@code session/update} and the whole notification as its params, as in the reference.
	 * @param replay true for history replayed by {@code session/load} (while the load is in flight, or
	 *			   when the agent marks it with {@code _meta.kiro.replay}). Replayed
	 *			   {@code user_message_chunk}s must be rendered; live ones are echoed locally by the host.
	 */
	record Update(String sessionId, JsonNode update, boolean replay) implements SessionEvent {
	}

	record TurnStarted(String sessionId) implements SessionEvent {
	}

	/** @param stopReason as the agent sent it; values beyond the ACP ones are possible */
	record TurnFinished(String sessionId, String stopReason) implements SessionEvent {
	}

	record TurnFailed(String sessionId, AcpException error) implements SessionEvent {
	}

	/** Session-level state changed (modes, config options, models, commands). */
	record State(SessionState session) implements SessionEvent {
	}

	/** Any {@code _kiro...} notification the layer does not interpret. Surfaced for the log and the UI. */
	record ExtensionNotification(String method, JsonNode params) implements SessionEvent {
	}
}
