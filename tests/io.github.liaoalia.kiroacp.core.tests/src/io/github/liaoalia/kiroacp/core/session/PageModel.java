package io.github.liaoalia.kiroacp.core.session;

import java.util.Iterator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * What the chat page makes of the messages it gets: the reducer of the
 * reference's {@code src/webview/state.ts}, on the JSON the page receives.
 * It lets a test compare the state a page arrived at message by message with
 * the snapshot it would get for {@code ready}.
 */
final class PageModel {

	private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

	private ObjectNode state = JSON.objectNode();

	PageModel() {
		state.putNull("sessionId");
		state.put("turnActive", false);
		for (String list : new String[] { "sessions", "items", "plan", "permissions", "configOptions", "commands", "chips",
				"notices" }) {
			state.putArray(list);
		}
		state.putObject("toolCalls");
	}

	JsonNode state() {
		return state.deepCopy();
	}

	void apply(JsonNode message) {
		switch (message.path("type").asText()) {
		case "state" -> state = (ObjectNode) message.get("state").deepCopy();
		case "connection" -> state.set("connection", message.get("connection"));
		case "session" -> {
			JsonNode id = message.get("sessionId");
			if (id.isNull() || !id.equals(state.get("sessionId"))) {
				state.set("sessionId", id);
				state.put("turnActive", false);
				state.putArray("items");
				state.putObject("toolCalls");
				state.putArray("plan");
				state.putArray("permissions");
				state.putArray("notices");
				state.remove("usage");
				state.remove("lastActivityAt");
			}
		}
		case "sessions" -> state.set("sessions", message.get("sessions"));
		case "turn" -> state.put("turnActive", message.get("active").asBoolean());
		case "chunk" -> chunk(message);
		case "messageDelivery" -> delivery(message);
		case "toolCall" -> {
			JsonNode call = message.get("call");
			String id = call.get("toolCallId").asText();
			((ObjectNode) state.get("toolCalls")).set(id, call.deepCopy());
			addToolItem(id);
		}
		case "plan" -> state.set("plan", message.get("entries"));
		case "permission" -> permission(message.get("request"));
		case "permissionResolved" -> permissionResolved(message.get("requestId").asText());
		case "modes" -> state.set("modes", message.get("modes"));
		case "configOptions" -> state.set("configOptions", message.get("options"));
		case "models" -> state.set("models", message.get("models"));
		case "commands" -> state.set("commands", message.get("commands"));
		case "chips" -> state.set("chips", message.get("chips"));
		case "notice" -> {
			JsonNode notice = message.get("notice");
			ArrayNode notices = (ArrayNode) state.get("notices");
			int at = indexOf(notices, "id", notice.get("id").asText());
			if (at >= 0) {
				notices.set(at, notice);
			} else {
				notices.add(notice);
			}
		}
		case "clearNotice" -> remove((ArrayNode) state.get("notices"), "id", message.get("id").asText());
		case "usage" -> state.set("usage", message.get("usage"));
		default -> {
			// Transient messages are not part of the state.
		}
		}
	}

	private void chunk(JsonNode message) {
		ArrayNode items = (ArrayNode) state.get("items");
		String messageId = message.get("messageId").asText();
		JsonNode segment = message.get("segment");
		for (int i = items.size() - 1; i >= 0; i--) {
			JsonNode item = items.get(i);
			if ("message".equals(item.path("type").asText()) && messageId.equals(item.path("message").path("id").asText())) {
				ArrayNode segments = (ArrayNode) item.get("message").get("segments");
				JsonNode last = segments.isEmpty() ? null : segments.get(segments.size() - 1);
				if (last != null && "text".equals(last.path("type").asText()) && "text".equals(segment.path("type").asText())) {
					((ObjectNode) last).put("text", last.get("text").asText() + segment.get("text").asText());
				} else {
					segments.add(segment.deepCopy());
				}
				return;
			}
		}
		ObjectNode created = JSON.objectNode();
		created.put("id", messageId);
		created.set("role", message.get("role"));
		created.putArray("segments").add(segment.deepCopy());
		ObjectNode item = items.addObject();
		item.put("type", "message");
		item.set("message", created);
	}

	private void delivery(JsonNode message) {
		for (JsonNode item : state.get("items")) {
			if (message.get("messageId").asText().equals(item.path("message").path("id").asText())) {
				ObjectNode shown = (ObjectNode) item.get("message");
				if (message.get("delivery").isNull()) {
					shown.remove("delivery");
				} else {
					shown.set("delivery", message.get("delivery"));
				}
			}
		}
	}

	private void permission(JsonNode request) {
		ArrayNode permissions = (ArrayNode) state.get("permissions");
		int at = indexOf(permissions, "requestId", request.get("requestId").asText());
		if (at >= 0) {
			permissions.set(at, request);
		} else {
			permissions.add(request);
		}
		ObjectNode toolCalls = (ObjectNode) state.get("toolCalls");
		String toolCallId = request.get("toolCallId").asText();
		ObjectNode call = (ObjectNode) toolCalls.get(toolCallId);
		if (call == null) {
			// Permission for a tool call not seen yet: a stub card, which a later toolCall replaces.
			call = toolCalls.putObject(toolCallId);
			call.put("toolCallId", toolCallId);
			call.set("title", request.get("title"));
			call.set("kind", request.get("kind"));
			call.put("status", "pending");
			call.putArray("content");
			call.putArray("locations");
			addToolItem(toolCallId);
		}
		call.set("pendingPermissionId", request.get("requestId"));
	}

	private void permissionResolved(String requestId) {
		ArrayNode permissions = (ArrayNode) state.get("permissions");
		int at = indexOf(permissions, "requestId", requestId);
		if (at < 0) {
			return;
		}
		JsonNode call = state.get("toolCalls").get(permissions.get(at).get("toolCallId").asText());
		if (call != null && requestId.equals(call.path("pendingPermissionId").asText(null))) {
			((ObjectNode) call).remove("pendingPermissionId");
		}
		permissions.remove(at);
	}

	private void addToolItem(String toolCallId) {
		ArrayNode items = (ArrayNode) state.get("items");
		for (JsonNode item : items) {
			if ("tool".equals(item.path("type").asText()) && toolCallId.equals(item.path("toolCallId").asText())) {
				return;
			}
		}
		items.addObject().put("type", "tool").put("toolCallId", toolCallId);
	}

	private static int indexOf(ArrayNode array, String field, String value) {
		for (int i = 0; i < array.size(); i++) {
			if (value.equals(array.get(i).path(field).asText(null))) {
				return i;
			}
		}
		return -1;
	}

	private static void remove(ArrayNode array, String field, String value) {
		for (Iterator<JsonNode> it = array.iterator(); it.hasNext();) {
			if (value.equals(it.next().path(field).asText(null))) {
				it.remove();
			}
		}
	}
}
