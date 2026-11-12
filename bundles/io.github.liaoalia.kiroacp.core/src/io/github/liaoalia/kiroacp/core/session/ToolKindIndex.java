package io.github.liaoalia.kiroacp.core.session;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.liaoalia.kiroacp.core.acp.api.SessionEvent;
import io.github.liaoalia.kiroacp.core.model.ToolKind;
import io.github.liaoalia.kiroacp.core.pure.transcript.Transcript;
import io.github.liaoalia.kiroacp.core.services.PermissionManager;

/**
 * The kind of every tool call the agent announced, per session (reference
 * D23): Kiro leaves the kind out of a permission request, and the request is
 * answered on the reader thread right after the {@code tool_call} it is
 * about. The transcripts live on the controller thread and have not seen
 * that update yet, so the kinds are also kept here, where the reader thread
 * writes and reads them.
 */
final class ToolKindIndex implements PermissionManager.ToolCallKinds {

	private final Map<String, Map<String, ToolKind>> bySession = new ConcurrentHashMap<>();

	/** On the reader thread, before the event is handed to the controller thread. */
	void record(SessionEvent event) {
		if (!(event instanceof SessionEvent.Update update) || update.sessionId() == null || update.update() == null) {
			return;
		}
		JsonNode body = update.update();
		JsonNode id = body.path("toolCallId");
		if (!id.isTextual()) {
			return;
		}
		String type = body.path("sessionUpdate").asText("");
		JsonNode kind = body.get("kind");
		// As the transcript does it: a tool_call replaces the card, an update keeps what it does not name.
		if ("tool_call".equals(type)) {
			kinds(update.sessionId()).put(id.textValue(), Transcript.toToolKind(kind));
		} else if ("tool_call_update".equals(type)) {
			if (kind != null && !kind.isNull()) {
				kinds(update.sessionId()).put(id.textValue(), Transcript.toToolKind(kind));
			} else {
				kinds(update.sessionId()).putIfAbsent(id.textValue(), ToolKind.OTHER);
			}
		}
	}

	@Override
	public ToolKind resolveKind(String sessionId, String toolCallId) {
		Map<String, ToolKind> kinds = sessionId == null ? null : bySession.get(sessionId);
		return kinds == null || toolCallId == null ? null : kinds.get(toolCallId);
	}

	void forget(String sessionId) {
		bySession.remove(sessionId);
	}

	void clear() {
		bySession.clear();
	}

	private Map<String, ToolKind> kinds(String sessionId) {
		return bySession.computeIfAbsent(sessionId, key -> new ConcurrentHashMap<>());
	}
}
