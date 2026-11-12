package io.github.liaoalia.kiroacp.core.acp.api;

import com.fasterxml.jackson.databind.JsonNode;

/** A request to the agent failed: an error response, a dead connection, or a capability the agent lacks. */
public final class AcpException extends Exception {

	/** JSON-RPC error code Kiro returns while signed out. */
	public static final int AUTH_REQUIRED_CODE = -32000;

	private static final long serialVersionUID = 1L;
	private static final java.util.regex.Pattern AUTH_MESSAGE = java.util.regex.Pattern.compile(
			"not logged in|kiro-cli login|authentication required|not authenticated|unauthenticated|auth refresh",
			java.util.regex.Pattern.CASE_INSENSITIVE);

	private final Integer code;
	private final transient JsonNode data;

	public AcpException(String message) {
		this(message, null, null, null);
	}

	public AcpException(String message, Integer code, JsonNode data, Throwable cause) {
		super(message, cause);
		this.code = code;
		this.data = data;
	}

	/** @return the JSON-RPC error code, or {@code null} when the failure was not an error response */
	public Integer code() {
		return code;
	}

	public JsonNode data() {
		return data;
	}

	/** Does this failure mean the user must sign in (by error code, or by Kiro's message)? */
	public boolean isAuthRequired() {
		String message = getMessage();
		return (code != null && code == AUTH_REQUIRED_CODE) || (message != null && AUTH_MESSAGE.matcher(message).find());
	}
}
