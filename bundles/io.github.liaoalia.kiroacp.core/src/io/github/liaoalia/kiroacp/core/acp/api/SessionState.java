package io.github.liaoalia.kiroacp.core.acp.api;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Session-level state reported by {@code session/new} / {@code session/load} and later updates.
 *
 * @param modes			 ACP {@code SessionModeState}, or {@code null} when not advertised
 * @param configOptions	 ACP {@code SessionConfigOption[]}, or {@code null} when not advertised
 * @param models			{@code null} when not advertised
 * @param availableCommands ACP {@code AvailableCommand[]}, possibly empty
 * @param meta			  raw {@code _meta} of the response (Kiro puts extra state here), or {@code null}
 */
public record SessionState(String sessionId, String cwd, JsonNode modes, List<JsonNode> configOptions, ModelState models,
		List<JsonNode> availableCommands, JsonNode meta) {

	public SessionState {
		configOptions = configOptions == null ? null : List.copyOf(configOptions);
		availableCommands = List.copyOf(availableCommands);
	}

	public SessionState withModes(JsonNode newModes) {
		return new SessionState(sessionId, cwd, newModes, configOptions, models, availableCommands, meta);
	}

	public SessionState withConfigOptions(List<JsonNode> newOptions) {
		return new SessionState(sessionId, cwd, modes, newOptions, models, availableCommands, meta);
	}

	public SessionState withModels(ModelState newModels) {
		return new SessionState(sessionId, cwd, modes, configOptions, newModels, availableCommands, meta);
	}

	public SessionState withAvailableCommands(List<JsonNode> newCommands) {
		return new SessionState(sessionId, cwd, modes, configOptions, models, newCommands, meta);
	}
}
