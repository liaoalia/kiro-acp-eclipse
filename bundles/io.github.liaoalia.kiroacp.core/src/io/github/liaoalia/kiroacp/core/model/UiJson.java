package io.github.liaoalia.kiroacp.core.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/** The JSON form of the chat page protocol. */
public final class UiJson {

	private static final ObjectMapper MAPPER = new ObjectMapper()
			// A newer page may send fields this host does not know yet.
			.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
			// Messages without fields ("ready", "cancel") are still messages.
			.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);

	private UiJson() {
	}

	public static JsonNode toJson(ToWebview message) {
		return MAPPER.valueToTree(message);
	}

	public static JsonNode toJson(FromWebview message) {
		return MAPPER.valueToTree(message);
	}

	/**
	 * @return the message, or empty when it is not one this host understands
	 *		 (an unknown {@code type} from a newer page, or a malformed one)
	 */
	public static Optional<FromWebview> fromWebview(JsonNode message) {
		return read(message, FromWebview.class);
	}

	public static Optional<ToWebview> toWebview(JsonNode message) {
		return read(message, ToWebview.class);
	}

	private static <T> Optional<T> read(JsonNode message, Class<T> type) {
		if (message == null || !message.isObject()) {
			return Optional.empty();
		}
		try {
			return Optional.ofNullable(MAPPER.treeToValue(message, type));
		} catch (JsonProcessingException | IllegalArgumentException e) {
			return Optional.empty();
		}
	}
}
