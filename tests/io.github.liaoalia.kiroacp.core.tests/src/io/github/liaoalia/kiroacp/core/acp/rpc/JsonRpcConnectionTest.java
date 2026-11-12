package io.github.liaoalia.kiroacp.core.acp.rpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** The connection against a scripted peer: the test writes the peer's lines and reads ours. */
class JsonRpcConnectionTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** What the peer writes to us. */
	private OutputStream peerOut;
	/** What we write to the peer. */
	private BufferedReader peerIn;
	private JsonRpcConnection connection;
	private final List<String> events = new CopyOnWriteArrayList<>();
	private final LinkedBlockingQueue<JsonRpcConnection.Responder> responders = new LinkedBlockingQueue<>();
	private final CountDownLatch closedLatch = new CountDownLatch(1);
	private volatile boolean failOnRequest;

	@BeforeEach
	void connect() throws IOException {
		PipedInputStream toUs = new PipedInputStream(1 << 16);
		peerOut = new PipedOutputStream(toUs);
		PipedOutputStream fromUs = new PipedOutputStream();
		peerIn = new BufferedReader(new InputStreamReader(new PipedInputStream(fromUs, 1 << 16), StandardCharsets.UTF_8));
		connection = new JsonRpcConnection(toUs, fromUs, new JsonRpcConnection.Handler() {
			@Override
			public void onNotification(String method, JsonNode params) {
				events.add("notification " + method + " " + params);
			}

			@Override
			public void onRequest(String method, JsonNode params, JsonRpcConnection.Responder responder) {
				events.add("request " + method + " " + params);
				responders.add(responder);
				if (failOnRequest) {
					throw new IllegalStateException("handler bug");
				}
			}

			@Override
			public void onProtocolError(String description, String line, Throwable cause) {
				events.add("error " + description);
			}

			@Override
			public void onClosed(Throwable cause) {
				events.add("closed " + (cause == null ? "cleanly" : cause.getClass().getSimpleName()));
				closedLatch.countDown();
			}
		}, "test");
		connection.start();
	}

	@AfterEach
	void disconnect() throws IOException {
		connection.close();
		peerOut.close();
	}

	@Test
	void aRequestGetsItsResult() throws Exception {
		ObjectNode params = MAPPER.createObjectNode().put("protocolVersion", 1);
		CompletableFuture<JsonNode> future = connection.request("initialize", params);

		JsonNode sent = readFromUs();
		assertEquals("2.0", sent.path("jsonrpc").asText());
		assertEquals("initialize", sent.path("method").asText());
		assertEquals(params, sent.path("params"));
		peer("{\"jsonrpc\":\"2.0\",\"id\":" + sent.path("id") + ",\"result\":{\"ok\":true,\"_meta\":{\"kiro\":{\"x\":1}}}}");

		JsonNode result = future.get(5, TimeUnit.SECONDS);
		assertTrue(result.path("ok").asBoolean());
		assertEquals(1, result.path("_meta").path("kiro").path("x").asInt(), "_meta survives");
	}

	@Test
	void paramsAreOmittedWhenNull() throws Exception {
		connection.request("ping", null);
		assertFalse(readFromUs().has("params"));
	}

	@Test
	void anErrorResponseFailsTheRequest() throws Exception {
		CompletableFuture<JsonNode> future = connection.request("authenticate", null);
		JsonNode sent = readFromUs();
		peer("{\"jsonrpc\":\"2.0\",\"id\":" + sent.path("id")
				+ ",\"error\":{\"code\":-32000,\"message\":\"Not logged in\",\"data\":{\"hint\":\"login\"}}}");

		ExecutionException failure = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
		JsonRpcException error = assertInstanceOf(JsonRpcException.class, failure.getCause());
		assertEquals(-32000, error.code());
		assertEquals("Not logged in", error.getMessage());
		assertEquals("login", error.data().path("hint").asText());
	}

	@Test
	void everythingWrittenBeforeAResponseIsHandledBeforeItCompletes() throws Exception {
		CompletableFuture<JsonNode> future = connection.request("session/prompt", null);
		future.whenComplete((result, error) -> events.add("response"));
		JsonNode sent = readFromUs();

		StringBuilder burst = new StringBuilder();
		for (int i = 0; i < 200; i++) {
			burst.append("{\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":{\"i\":").append(i).append("}}\n");
		}
		burst.append("{\"jsonrpc\":\"2.0\",\"id\":").append(sent.path("id")).append(",\"result\":{}}\n");
		peerOut.write(burst.toString().getBytes(StandardCharsets.UTF_8));
		peerOut.flush();
		future.get(5, TimeUnit.SECONDS);
		// A dependent added before completion runs on the reader thread, so it is in the list by now.
		awaitEvents(201);

		assertEquals(201, events.size());
		for (int i = 0; i < 200; i++) {
			assertEquals("notification session/update {\"i\":" + i + "}", events.get(i));
		}
		assertEquals("response", events.get(200));
	}

	@Test
	void aRequestFromThePeerIsAnsweredWithItsOwnId() throws Exception {
		peer("{\"jsonrpc\":\"2.0\",\"id\":\"req-7\",\"method\":\"fs/read_text_file\",\"params\":{\"path\":\"/a\"}}");
		JsonRpcConnection.Responder responder = responders.poll(5, TimeUnit.SECONDS);
		assertNotNull(responder);
		assertEquals("request fs/read_text_file {\"path\":\"/a\"}", events.get(0));

		responder.result(MAPPER.createObjectNode().put("content", "text"));
		// The first answer wins.
		responder.error(JsonRpcException.INTERNAL_ERROR, "too late");

		JsonNode answer = readFromUs();
		assertEquals("req-7", answer.path("id").asText());
		assertTrue(answer.path("id").isTextual(), "a string id stays a string");
		assertEquals("text", answer.path("result").path("content").asText());

		peer("{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"_kiro/unknown/thing\"}");
		responders.poll(5, TimeUnit.SECONDS).error(JsonRpcException.METHOD_NOT_FOUND, "Method not found: _kiro/unknown/thing");

		JsonNode refusal = readFromUs();
		assertEquals(8, refusal.path("id").asInt());
		assertTrue(refusal.path("id").isNumber());
		assertEquals(-32601, refusal.path("error").path("code").asInt());
		assertFalse(refusal.has("result"));
	}

	@Test
	void aHandlerThatThrowsStillAnswersThePeer() throws Exception {
		failOnRequest = true;
		peer("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"terminal/create\",\"params\":{}}");

		JsonNode answer = readFromUs();
		assertEquals(-32603, answer.path("error").path("code").asInt());
		assertTrue(events.contains("error Handling the request terminal/create failed"), events.toString());
	}

	@Test
	void garbageOnTheWireIsReportedAndSkipped() throws Exception {
		peer("Welcome to the agent!");
		peer("[1,2]");
		peer("");
		peer("{\"jsonrpc\":\"2.0\"}");
		peer("{\"jsonrpc\":\"2.0\",\"id\":99,\"result\":{}}");
		peer("{\"jsonrpc\":\"2.0\",\"method\":\"_kiro/metadata\",\"params\":{\"text\":\"Привет € 日本\"}}");
		awaitEvents(5);

		assertEquals(List.of(
				"error A line that is not JSON",
				"error A message that is not a JSON object",
				"error A message that is neither a request, a notification nor a response",
				"error A response to a request that is not pending",
				"notification _kiro/metadata {\"text\":\"Привет € 日本\"}"), events);
	}

	@Test
	void theEndOfInputFailsWhatIsPending() throws Exception {
		CompletableFuture<JsonNode> future = connection.request("session/prompt", null);
		readFromUs();

		peerOut.close();

		ExecutionException failure = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
		assertInstanceOf(ConnectionClosedException.class, failure.getCause());
		assertTrue(closedLatch.await(5, TimeUnit.SECONDS));
		assertEquals("closed cleanly", events.get(events.size() - 1));
		assertTrue(connection.isClosed());

		ExecutionException later = assertThrows(ExecutionException.class,
				() -> connection.request("session/new", null).get(5, TimeUnit.SECONDS));
		assertInstanceOf(ConnectionClosedException.class, later.getCause());
		assertThrows(ConnectionClosedException.class, () -> connection.notify("session/cancel", null));
	}

	@Test
	void closingHereFailsWhatIsPendingAndReportsACleanEnd() throws Exception {
		CompletableFuture<JsonNode> future = connection.request("session/prompt", null);
		readFromUs();

		connection.close();
		// A piped stream does not interrupt a blocked read on close; the peer hanging up does.
		peerOut.close();

		ExecutionException failure = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
		assertInstanceOf(ConnectionClosedException.class, failure.getCause());
		assertTrue(closedLatch.await(5, TimeUnit.SECONDS));
		assertEquals(1, events.stream().filter(e -> e.startsWith("closed")).count());
		assertNull(peerIn.readLine(), "our output is closed too");
	}

	private void peer(String line) throws IOException {
		peerOut.write((line + "\n").getBytes(StandardCharsets.UTF_8));
		peerOut.flush();
	}

	private JsonNode readFromUs() throws IOException {
		String line = peerIn.readLine();
		assertNotNull(line, "the connection wrote nothing");
		return MAPPER.readTree(line);
	}

	private void awaitEvents(int count) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (events.size() < count && System.nanoTime() < deadline) {
			Thread.sleep(5);
		}
	}
}
