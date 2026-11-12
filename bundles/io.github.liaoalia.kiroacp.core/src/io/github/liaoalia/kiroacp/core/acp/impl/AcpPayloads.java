package io.github.liaoalia.kiroacp.core.acp.impl;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.liaoalia.kiroacp.core.acp.api.AgentInfo;
import io.github.liaoalia.kiroacp.core.acp.api.ModelState;
import io.github.liaoalia.kiroacp.core.acp.api.SessionListEntry;
import io.github.liaoalia.kiroacp.core.acp.api.SessionState;

/**
 * Reads what this layer acts on out of ACP payloads. Everything is read
 * defensively: a field of the wrong type counts as absent, because Kiro's
 * payloads are not checked against a schema anywhere (DECISIONS D1).
 */
final class AcpPayloads {

	private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

	private AcpPayloads() {
	}

	static AgentInfo agentInfo(JsonNode response) {
		JsonNode capabilities = response.path("agentCapabilities");
		if (!capabilities.isObject()) {
			capabilities = JSON.objectNode();
		}
		JsonNode info = response.path("agentInfo");
		return new AgentInfo(response.path("protocolVersion").asInt(0), capabilities, info.isObject() ? info : JSON.nullNode(),
				elements(response.path("authMethods")), kiroExtensionMethods(capabilities));
	}

	static List<String> kiroExtensionMethods(JsonNode capabilities) {
		List<String> methods = new ArrayList<>();
		for (JsonNode method : capabilities.path("_meta").path("kiro").path("extensionMethods")) {
			if (method.isTextual()) {
				methods.add(method.asText());
			}
		}
		return methods;
	}

	/** The unstable {@code models} field of a {@code session/new} or {@code session/load} response. */
	static ModelState modelState(JsonNode response) {
		JsonNode models = response.path("models");
		JsonNode current = models.path("currentModelId");
		if (!models.isObject() || !current.isTextual()) {
			return null;
		}
		List<ModelState.Model> available = new ArrayList<>();
		for (JsonNode entry : models.path("availableModels")) {
			JsonNode id = entry.path("modelId");
			if (!entry.isObject() || !id.isTextual()) {
				continue;
			}
			JsonNode name = entry.path("name");
			JsonNode description = entry.path("description");
			available.add(new ModelState.Model(id.asText(), name.isTextual() ? name.asText() : id.asText(),
					description.isTextual() ? description.asText() : null));
		}
		return new ModelState(current.asText(), available);
	}

	/** @return the state a {@code session/new} response describes, or {@code null} if it names no session */
	static SessionState sessionState(String cwd, JsonNode response) {
		JsonNode id = response.path("sessionId");
		if (!id.isTextual()) {
			return null;
		}
		return new SessionState(id.asText(), cwd, objectOrNull(response.path("modes")),
				arrayOrNull(response.path("configOptions")), modelState(response), List.of(),
				objectOrNull(response.path("_meta")));
	}

	static SessionListEntry sessionListEntry(JsonNode raw) {
		JsonNode id = raw.path("sessionId");
		if (!raw.isObject() || !id.isTextual()) {
			return null;
		}
		return new SessionListEntry(id.asText(), raw.path("cwd").isTextual() ? raw.path("cwd").asText() : "",
				textOrNull(raw.path("title")), textOrNull(raw.path("updatedAt")));
	}

	/** Does a {@code session/update} carry Kiro's {@code _meta.kiro.replay: true}? */
	static boolean isReplay(JsonNode update) {
		JsonNode replay = update.path("_meta").path("kiro").path("replay");
		return replay.isBoolean() && replay.asBoolean();
	}

	/** {@code modes} with another current mode; a session without modes gets an empty list of them. */
	static JsonNode withCurrentMode(JsonNode modes, String modeId) {
		ObjectNode next = modes != null && modes.isObject() ? modes.deepCopy() : JSON.objectNode();
		next.put("currentModeId", modeId);
		if (!next.has("availableModes")) {
			next.putArray("availableModes");
		}
		return next;
	}

	/**
	 * Kiro CLI v2 sends its slash commands in {@code _kiro.dev/commands/available}
	 * instead of an {@code available_commands_update}; this maps the one onto the other.
	 *
	 * @return {@code null} when the params carry no command list
	 */
	static V2Commands v2Commands(JsonNode params) {
		if (params == null || !params.isObject()) {
			return null;
		}
		JsonNode list = params.path("commands").isArray() ? params.path("commands") : params.path("availableCommands");
		if (!list.isArray()) {
			return null;
		}
		List<JsonNode> commands = new ArrayList<>();
		for (JsonNode item : list) {
			if (!item.isObject() || !item.path("name").isTextual()) {
				continue;
			}
			ObjectNode command = JSON.objectNode();
			command.put("name", item.path("name").asText());
			command.put("description", item.path("description").isTextual() ? item.path("description").asText() : "");
			JsonNode hint = item.path("input").path("hint").isTextual() ? item.path("input").path("hint") : item.path("hint");
			if (hint.isTextual()) {
				command.putObject("input").put("hint", hint.asText());
			}
			commands.add(command);
		}
		return new V2Commands(textOrNull(params.path("sessionId")), commands);
	}

	/** @param sessionId {@code null} when the notification names none */
	record V2Commands(String sessionId, List<JsonNode> commands) {
	}

	static List<JsonNode> elements(JsonNode array) {
		List<JsonNode> out = new ArrayList<>();
		if (array.isArray()) {
			array.forEach(out::add);
		}
		return out;
	}

	static JsonNode objectOrNull(JsonNode node) {
		return node.isObject() ? node : null;
	}

	private static List<JsonNode> arrayOrNull(JsonNode node) {
		return node.isArray() ? elements(node) : null;
	}

	private static String textOrNull(JsonNode node) {
		return node.isTextual() ? node.asText() : null;
	}
}
