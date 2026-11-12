package io.github.liaoalia.kiroacp.core.acp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * ACP frames JSON-RPC as newline-delimited JSON on the agent's stdio: one
 * message per line, UTF-8, no embedded line breaks.
 */
public final class NdjsonCodec {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private NdjsonCodec() {
	}

	/** @return the message as one line, without the terminating newline */
	public static String encode(JsonNode message) {
		try {
			// Jackson escapes control characters inside strings and emits no
			// insignificant whitespace, so the result never contains a newline.
			return MAPPER.writeValueAsString(message);
		} catch (JsonProcessingException e) {
			throw new IllegalArgumentException(e);
		}
	}

	/**
	 * @return the message on this line, or {@code null} for a blank line
	 * @throws JsonProcessingException if the line is not one JSON value
	 */
	public static JsonNode decode(String line) throws JsonProcessingException {
		if (line == null || line.isBlank()) {
			return null;
		}
		return MAPPER.readTree(line);
	}
}
