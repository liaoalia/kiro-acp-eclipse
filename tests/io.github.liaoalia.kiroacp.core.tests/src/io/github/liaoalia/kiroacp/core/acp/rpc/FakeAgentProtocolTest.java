package io.github.liaoalia.kiroacp.core.acp.rpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.liaoalia.kiroacp.core.acp.FakeAgent;

/**
 * The checks that decided the protocol layer (docs/DECISIONS.md, D1), against
 * the reference's fake agent: nothing the agent sends is lost or reordered,
 * known or not.
 */
class FakeAgentProtocolTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final long SECONDS = 30;

	@TempDir
	Path cwd;

	private FakeAgent agent;
	private JsonRpcConnection connection;
	/** Everything that arrived, in the order the reader thread handled it. */
	private final List<String> events = new CopyOnWriteArrayList<>();
	private final List<JsonNode> updates = new CopyOnWriteArrayList<>();
	private final List<String> protocolErrors = new CopyOnWriteArrayList<>();
	private final CountDownLatch closed = new CountDownLatch(1);

	@AfterEach
	void stop() throws Exception {
		if (connection != null) {
			connection.close();
		}
		if (agent != null) {
			agent.close();
		}
	}

	@Test
	void aStreamedAnswerArrivesWhole() throws Exception {
		String sessionId = startSession("--scenario", "chat");

		JsonNode response = prompt(sessionId, "hi").get(SECONDS, TimeUnit.SECONDS);

		assertEquals("end_turn", response.path("stopReason").asText());
		assertEquals("The answer is 42.", agentText());
		assertEquals(List.of(), protocolErrors);
	}

	/** Hazard: updates overtaken by the response of the prompt they belong to. */
	@RepeatedTest(5)
	void everyChunkIsHandledBeforeThePromptCompletes() throws Exception {
		String sessionId = startSession("--scenario", "slow");
		events.clear();

		// The dependent runs on the reader thread at the moment the response is handled;
		// waiting on its own future (not the prompt's) guarantees it has run.
		prompt(sessionId, "go").whenComplete((result, error) -> events.add("response")).get(SECONDS, TimeUnit.SECONDS);

		List<String> expected = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			expected.add("update agent_message_chunk");
		}
		expected.add("response");
		assertEquals(expected, withoutSessionHousekeeping(events));
	}

	/**
	 * The tour: permission round-trip, fs and terminal requests, an update kind
	 * and a notification method this client has no idea about.
	 */
	@Test
	void theTourReachesUsCompleteAndInOrder() throws Exception {
		String sessionId = startSession("--scenario", "tour");
		events.clear();

		JsonNode response = prompt(sessionId, "tour").get(SECONDS, TimeUnit.SECONDS);

		assertEquals("end_turn", response.path("stopReason").asText());
		// The fake agent reports every expectation of its recording that we failed as a chunk.
		assertFalse(agentText().contains("[fake-agent]"), agentText());
		assertTrue(agentText().endsWith("All done."), agentText());

		// Unknown to any typed schema, delivered all the same, with their payload.
		assertTrue(events.contains("update kiro_custom_update"), events.toString());
		JsonNode custom = updates.stream().filter(u -> "kiro_custom_update".equals(u.path("sessionUpdate").asText()))
				.findFirst().orElseThrow();
		assertEquals(1, custom.path("payload").asInt());
		assertTrue(events.contains("notification _kiro/mcp/status"), events.toString());

		assertInOrder(events,
				"update agent_thought_chunk",
				"notification _kiro/mcp/status",
				"update kiro_custom_update",
				"request _kiro/openExternalUrl",
				"request fs/read_text_file",
				"update plan",
				"request terminal/create",
				"request terminal/output",
				"request terminal/wait_for_exit",
				"request terminal/release",
				"request session/request_permission",
				"request fs/write_text_file");
		assertEquals(List.of(), protocolErrors);
	}

	@Test
	void metaFieldsSurvive() throws Exception {
		startSession("--scenario", "tour");

		assertEquals("v3", newSessionResponse.path("_meta").path("kiro").path("engine").asText());
		assertTrue(initializeResponse.path("agentCapabilities").path("_meta").path("kiro").path("extensionMethods").isContainerNode(),
				initializeResponse.toString());
	}

	@Test
	void thePermissionRequestKeepsItsMeta() throws Exception {
		String sessionId = startSession("--scenario", "tour");

		prompt(sessionId, "tour").get(SECONDS, TimeUnit.SECONDS);

		JsonNode permission = requests.stream().filter(r -> r.path("toolCall").isObject()).findFirst().orElseThrow();
		assertEquals("fs_write", permission.path("_meta").path("kiro").path("consent").path("capability").asText());
		assertEquals(4, permission.path("options").size());
	}

	@Test
	void anUnknownRequestIsRefusedWithMethodNotFound() throws Exception {
		String sessionId = startSession("--scenario", "bad-request");

		prompt(sessionId, "go").get(SECONDS, TimeUnit.SECONDS);

		// The agent says so itself once it has seen the -32601.
		assertEquals("unknown request rejected with -32601", agentText());
		assertTrue(events.contains("request _kiro/unknown/thing"), events.toString());
	}

	@Test
	void anErrorFromTheAgentIsAnException() throws Exception {
		startSession("--scenario", "chat");

		ExecutionException failure = assertThrows(ExecutionException.class,
				() -> prompt("no-such-session", "hi").get(SECONDS, TimeUnit.SECONDS));

		assertInstanceOf(JsonRpcException.class, failure.getCause());
	}

	@Test
	void aCrashMidTurnFailsThePromptAtOnce() throws Exception {
		String sessionId = startSession("--scenario", "crash");

		CompletableFuture<JsonNode> prompt = prompt(sessionId, "go");

		ExecutionException failure = assertThrows(ExecutionException.class, () -> prompt.get(SECONDS, TimeUnit.SECONDS));
		assertInstanceOf(ConnectionClosedException.class, failure.getCause());
		assertTrue(closed.await(SECONDS, TimeUnit.SECONDS));
	}

	// ---------------------------------------------------------------------

	private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
	private JsonNode initializeResponse;
	private JsonNode newSessionResponse;

	private String startSession(String... agentArguments) throws Exception {
		agent = FakeAgent.start(agentArguments);
		connection = new JsonRpcConnection(agent.stdout(), agent.stdin(), new Client(), "fake");
		connection.start();

		ObjectNode init = MAPPER.createObjectNode();
		init.put("protocolVersion", 1);
		ObjectNode capabilities = init.putObject("clientCapabilities");
		capabilities.putObject("fs").put("readTextFile", true).put("writeTextFile", true);
		capabilities.put("terminal", true);
		initializeResponse = connection.request("initialize", init).get(SECONDS, TimeUnit.SECONDS);
		assertEquals(1, initializeResponse.path("protocolVersion").asInt());

		ObjectNode session = MAPPER.createObjectNode();
		session.put("cwd", cwd.toString());
		session.putArray("mcpServers");
		newSessionResponse = connection.request("session/new", session).get(SECONDS, TimeUnit.SECONDS);
		String sessionId = newSessionResponse.path("sessionId").asText();
		assertFalse(sessionId.isEmpty(), newSessionResponse.toString());
		return sessionId;
	}

	private CompletableFuture<JsonNode> prompt(String sessionId, String text) {
		ObjectNode params = MAPPER.createObjectNode();
		params.put("sessionId", sessionId);
		params.putArray("prompt").addObject().put("type", "text").put("text", text);
		return connection.request("session/prompt", params);
	}

	private String agentText() {
		StringBuilder text = new StringBuilder();
		for (JsonNode update : updates) {
			if ("agent_message_chunk".equals(update.path("sessionUpdate").asText())) {
				text.append(update.path("content").path("text").asText());
			}
		}
		return text.toString();
	}

	/** Updates a new session announces on its own (commands and the like) are not part of a turn. */
	private static List<String> withoutSessionHousekeeping(List<String> all) {
		return all.stream().filter(e -> e.equals("response") || e.equals("update agent_message_chunk")).toList();
	}

	private static void assertInOrder(List<String> actual, String... expected) {
		int from = 0;
		for (String wanted : expected) {
			int at = actual.subList(from, actual.size()).indexOf(wanted);
			assertTrue(at >= 0, "\"" + wanted + "\" is missing or out of order in " + actual);
			from += at + 1;
		}
	}

	/** Just enough of a client for the recordings: canned answers, everything recorded. */
	private final class Client implements JsonRpcConnection.Handler {

		@Override
		public void onNotification(String method, JsonNode params) {
			if ("session/update".equals(method)) {
				JsonNode update = params.path("update");
				updates.add(update);
				events.add("update " + update.path("sessionUpdate").asText());
			} else {
				events.add("notification " + method);
			}
		}

		@Override
		public void onRequest(String method, JsonNode params, JsonRpcConnection.Responder responder) {
			events.add("request " + method);
			requests.add(params == null ? MAPPER.nullNode() : params);
			ObjectNode result = MAPPER.createObjectNode();
			switch (method) {
				case "fs/read_text_file" -> responder.result(result.put("content", "# readme\n"));
				case "fs/write_text_file", "terminal/release" -> responder.result(result);
				case "terminal/create" -> responder.result(result.put("terminalId", "term-1"));
				case "terminal/output" -> responder.result(result.put("output", "hello\n").put("truncated", false));
				case "terminal/wait_for_exit" -> responder.result(result.put("exitCode", 0));
				case "session/request_permission" -> {
					result.putObject("outcome").put("outcome", "selected").put("optionId", "accept");
					// Answered from another thread, as a user's click would be.
					CompletableFuture.runAsync(() -> responder.result(result));
				}
				default -> responder.error(JsonRpcException.METHOD_NOT_FOUND, "Method not found: " + method);
			}
		}

		@Override
		public void onProtocolError(String description, String line, Throwable cause) {
			protocolErrors.add(description + ": " + line);
		}

		@Override
		public void onClosed(Throwable cause) {
			closed.countDown();
		}
	}
}
