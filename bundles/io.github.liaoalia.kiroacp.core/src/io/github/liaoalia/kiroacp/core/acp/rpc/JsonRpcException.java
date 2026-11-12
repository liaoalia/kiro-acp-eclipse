package io.github.liaoalia.kiroacp.core.acp.rpc;

import com.fasterxml.jackson.databind.JsonNode;

/** The peer answered a request with a JSON-RPC error. */
public final class JsonRpcException extends Exception {

	public static final int PARSE_ERROR = -32700;
	public static final int INVALID_REQUEST = -32600;
	public static final int METHOD_NOT_FOUND = -32601;
	public static final int INVALID_PARAMS = -32602;
	public static final int INTERNAL_ERROR = -32603;

	private static final long serialVersionUID = 1L;

	private final int code;
	private final transient JsonNode data;

	public JsonRpcException(int code, String message, JsonNode data) {
		super(message);
		this.code = code;
		this.data = data;
	}

	public int code() {
		return code;
	}

	/** @return the error's {@code data}, or {@code null} */
	public JsonNode data() {
		return data;
	}
}
