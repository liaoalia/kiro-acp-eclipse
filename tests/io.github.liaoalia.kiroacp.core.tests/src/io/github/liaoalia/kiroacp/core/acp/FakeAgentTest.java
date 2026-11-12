package io.github.liaoalia.kiroacp.core.acp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** The fixture itself works: the fake agent starts and answers on its stdio. */
class FakeAgentTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Test
	void answersInitialize() throws Exception {
		try (FakeAgent agent = FakeAgent.start("--scenario", "chat")) {
			ObjectNode request = MAPPER.createObjectNode();
			request.put("jsonrpc", "2.0");
			request.put("id", 1);
			request.put("method", "initialize");
			ObjectNode params = request.putObject("params");
			params.put("protocolVersion", 1);
			params.putObject("clientCapabilities");
			agent.send(request);

			JsonNode response = agent.receive(20, TimeUnit.SECONDS);

			assertEquals(1, response.path("id").asInt(), response.toString());
			JsonNode result = response.path("result");
			assertEquals(1, result.path("protocolVersion").asInt(), response.toString());
			assertTrue(result.path("agentCapabilities").isObject(), response.toString());
		}
	}
}
