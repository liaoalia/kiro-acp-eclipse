package io.github.liaoalia.kiroacp.core.acp.api;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * What the agent said in its {@code initialize} response. ACP payloads stay
 * JSON trees (DECISIONS D1); the accessors read what this plugin acts on.
 *
 * @param agentCapabilities	the {@code agentCapabilities} object, never {@code null}
 * @param agentInfo			{@code {name, title, version}} or a null node
 * @param authMethods		  advertised auth methods, possibly empty
 * @param kiroExtensionMethods {@code agentCapabilities._meta.kiro.extensionMethods} when advertised (CLI V3)
 */
public record AgentInfo(int protocolVersion, JsonNode agentCapabilities, JsonNode agentInfo, List<JsonNode> authMethods,
		List<String> kiroExtensionMethods) {

	public AgentInfo {
		authMethods = List.copyOf(authMethods);
		kiroExtensionMethods = List.copyOf(kiroExtensionMethods);
	}

	public boolean canLoadSessions() {
		return agentCapabilities.path("loadSession").asBoolean(false);
	}

	public boolean canListSessions() {
		JsonNode list = agentCapabilities.path("sessionCapabilities").path("list");
		return !list.isMissingNode() && !list.isNull() && list.asBoolean(true);
	}

	public boolean acceptsImages() {
		return agentCapabilities.path("promptCapabilities").path("image").asBoolean(false);
	}

	public boolean hasExtension(String method) {
		return kiroExtensionMethods.contains(method);
	}
}
