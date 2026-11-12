package io.github.liaoalia.kiroacp.core.acp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

class NdjsonCodecTest {

	@Test
	void aMessageIsOneLineWhateverItsText() throws Exception {
		ObjectNode message = JsonNodeFactory.instance.objectNode();
		message.put("text", "first\nsecond\r\nthird fourth é世");

		String line = NdjsonCodec.encode(message);

		assertFalse(line.contains("\n"), line);
		assertFalse(line.contains("\r"), line);
		assertEquals(message, NdjsonCodec.decode(line));
	}

	@Test
	void aBlankLineIsNoMessage() throws Exception {
		assertNull(NdjsonCodec.decode(""));
		assertNull(NdjsonCodec.decode("  \t"));
		assertNull(NdjsonCodec.decode(null));
	}

	@Test
	void aLineThatIsNotJsonIsAnError() {
		assertThrows(JsonProcessingException.class, () -> NdjsonCodec.decode("{\"id\":"));
	}

	@Test
	void decodesAnyJsonValue() throws Exception {
		JsonNode node = NdjsonCodec.decode("{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":null}");
		assertEquals(7, node.path("id").asInt());
	}
}
