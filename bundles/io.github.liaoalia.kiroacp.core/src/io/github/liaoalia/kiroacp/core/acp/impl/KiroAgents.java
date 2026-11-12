package io.github.liaoalia.kiroacp.core.acp.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.liaoalia.kiroacp.core.acp.api.AgentProcessOptions;
import io.github.liaoalia.kiroacp.core.acp.api.ClientServices;
import io.github.liaoalia.kiroacp.core.acp.api.KiroAgent;

/** Creates the ACP client and names the protocol constants the host needs to know. */
public final class KiroAgents {

	public static final String CLIENT_NAME = "kiro-acp-eclipse";
	public static final String CLIENT_TITLE = "Kiro ACP Chat for Eclipse";

	/** The ACP protocol version this client speaks. */
	public static final int PROTOCOL_VERSION = 1;

	/** Kiro CLI v2 notification carrying the slash-command catalogue. */
	public static final String KIRO_V2_COMMANDS_AVAILABLE = "_kiro.dev/commands/available";
	/** Kiro extension request asking the client to open a URL (MCP OAuth). */
	public static final String KIRO_OPEN_EXTERNAL_URL = "_kiro/openExternalUrl";
	/** Unstable ACP v1 model selection used by the CLI v2 server. */
	public static final String SESSION_SET_MODEL = "session/set_model";

	/**
	 * Id of the terminal login offered when the agent says the user is signed out
	 * without advertising an auth method of its own. Kiro V3 keeps
	 * {@code authMethods} empty while a stale token exists and only fails later,
	 * on {@code session/prompt}. The method runs the standalone
	 * {@code <cli> login}, not the {@code acp} invocation with arguments appended
	 * (reference D25). {@link KiroAgent#authenticate} accepts it at any time.
	 */
	public static final String FALLBACK_LOGIN_METHOD_ID = "kiro-cli-login";

	/** One instance: the client tells the fallback from an agent's method of the same id by identity. */
	static final ObjectNode FALLBACK_LOGIN_METHOD = JsonNodeFactory.instance.objectNode();

	static {
		FALLBACK_LOGIN_METHOD.put("id", FALLBACK_LOGIN_METHOD_ID);
		FALLBACK_LOGIN_METHOD.put("name", "Log in to Kiro");
		FALLBACK_LOGIN_METHOD.put("description", "Runs `kiro-cli login` in a terminal");
		FALLBACK_LOGIN_METHOD.put("type", "terminal");
		FALLBACK_LOGIN_METHOD.putArray("args").add("login");
	}

	private KiroAgents() {
	}

	/**
	 * A client for one agent process. Nothing is started until {@link KiroAgent#start()}.
	 *
	 * @param services what the agent may ask of the host
	 */
	public static KiroAgent create(AgentProcessOptions options, ClientServices services) {
		return new KiroAgentImpl(options, services);
	}

	/** @return a copy of the ACP {@code AuthMethod} behind {@link #FALLBACK_LOGIN_METHOD_ID} */
	public static JsonNode fallbackLoginMethod() {
		return FALLBACK_LOGIN_METHOD.deepCopy();
	}
}
