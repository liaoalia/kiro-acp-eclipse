package io.github.liaoalia.kiroacp.core.acp.rpc;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.liaoalia.kiroacp.core.acp.NdjsonCodec;

/**
 * JSON-RPC 2.0 over a pair of byte streams, one message per line: the wire
 * level of ACP, with nothing ACP-specific in it.
 *
 * <p>Messages stay JSON trees. Nothing is mapped to types here, so a method,
 * an update kind or a field this code has never heard of passes through
 * untouched.
 *
 * <p><b>Order.</b> One thread reads the input and handles each message before
 * reading the next: it calls the {@link Handler} for a request or notification
 * and completes the future for a response. So when the future of a request
 * completes, every message the peer wrote before that response has already
 * been through the handler. The handler must therefore return quickly and
 * must not wait for a response on that thread; an answer that takes time is
 * given later through the {@link Responder}, from any thread.
 */
public final class JsonRpcConnection implements AutoCloseable {

	/** Receives what the peer sends. All methods are called on the reader thread, in wire order. */
	public interface Handler {

		void onNotification(String method, JsonNode params);

		/** The request must be answered exactly once, now or later; see {@link Responder}. */
		void onRequest(String method, JsonNode params, Responder responder);

		/**
		 * The peer broke the protocol or a handler method threw; the connection
		 * carries on. {@code line} is the offending input when there is one.
		 */
		void onProtocolError(String description, String line, Throwable cause);

		/**
		 * The input ended or failed; called once, after every pending request
		 * has failed with {@link ConnectionClosedException}.
		 *
		 * @param cause {@code null} for a plain end of input or a local {@link #close()}
		 */
		void onClosed(Throwable cause);
	}

	/** Answers one request from the peer. The first answer wins; later ones are ignored. */
	public interface Responder {

		void result(JsonNode result);

		void error(int code, String message, JsonNode data);

		default void error(int code, String message) {
			error(code, message, null);
		}
	}

	private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

	private final InputStream rawIn;
	private final BufferedReader in;
	private final BufferedWriter out;
	private final Handler handler;
	private final Thread reader;
	private final Object writeLock = new Object();
	private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
	private final AtomicLong nextId = new AtomicLong(1);
	private final AtomicBoolean closed = new AtomicBoolean();
	private volatile boolean closedLocally;

	/**
	 * @param name used in the reader thread's name
	 */
	public JsonRpcConnection(InputStream in, OutputStream out, Handler handler, String name) {
		// Never the platform charset: on Windows before Java 18 that is an ANSI code page.
		this.rawIn = in;
		this.in = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
		this.out = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
		this.handler = handler;
		this.reader = new Thread(this::readLoop, "acp-reader-" + name);
		this.reader.setDaemon(true);
	}

	/** Starts reading. Call once. */
	public void start() {
		reader.start();
	}

	/**
	 * Sends a request.
	 *
	 * @param params an object or array, or {@code null} to omit them
	 * @return completes with the result on the reader thread; fails with
	 *         {@link JsonRpcException} for an error response and with
	 *         {@link ConnectionClosedException} when the connection is or goes down
	 */
	public CompletableFuture<JsonNode> request(String method, JsonNode params) {
		CompletableFuture<JsonNode> future = new CompletableFuture<>();
		long id = nextId.getAndIncrement();
		ObjectNode message = envelope();
		message.put("id", id);
		message.put("method", method);
		if (params != null) {
			message.set("params", params);
		}
		pending.put(id, future);
		try {
			if (closed.get()) {
				throw new ConnectionClosedException("The connection is closed", null);
			}
			write(message);
		} catch (IOException e) {
			pending.remove(id);
			future.completeExceptionally(asClosed(e));
		}
		// The connection may have gone down between the check and the write reaching the peer.
		if (closed.get() && pending.remove(id) != null) {
			future.completeExceptionally(new ConnectionClosedException("The connection is closed", null));
		}
		return future;
	}

	/**
	 * Sends a notification.
	 *
	 * @throws ConnectionClosedException if the connection is down
	 */
	public void notify(String method, JsonNode params) throws ConnectionClosedException {
		ObjectNode message = envelope();
		message.put("method", method);
		if (params != null) {
			message.set("params", params);
		}
		try {
			if (closed.get()) {
				throw new ConnectionClosedException("The connection is closed", null);
			}
			write(message);
		} catch (IOException e) {
			throw asClosed(e);
		}
	}

	public boolean isClosed() {
		return closed.get();
	}

	/**
	 * Closes both streams. Pending requests fail and {@link Handler#onClosed} is
	 * called once the reader has seen the end of the input. Whether closing a
	 * stream wakes a thread blocked reading it depends on the stream, so the
	 * dependable way to end the reader is to end the peer, which for an agent
	 * process means terminating it.
	 */
	@Override
	public void close() {
		closedLocally = true;
		synchronized (writeLock) {
			try {
				out.close();
			} catch (IOException e) {
				// Closing is best effort.
			}
		}
		try {
			// Not the BufferedReader: its close waits for the lock a blocked readLine holds.
			rawIn.close();
		} catch (IOException e) {
			// Closing is best effort.
		}
		if (!reader.isAlive()) {
			// Never started, or already finished: nobody else will report the end.
			finish(null);
		}
	}

	private void readLoop() {
		Throwable cause = null;
		try {
			for (String line = in.readLine(); line != null; line = in.readLine()) {
				handleLine(line);
			}
		} catch (IOException e) {
			cause = closedLocally ? null : e;
		}
		finish(cause);
	}

	private void finish(Throwable cause) {
		if (!closed.compareAndSet(false, true)) {
			return;
		}
		ConnectionClosedException failure = new ConnectionClosedException(
				cause == null ? "The connection was closed" : "The connection failed: " + cause.getMessage(), cause);
		List<Long> ids = new ArrayList<>(pending.keySet());
		for (Long id : ids) {
			CompletableFuture<JsonNode> future = pending.remove(id);
			if (future != null) {
				future.completeExceptionally(failure);
			}
		}
		try {
			handler.onClosed(cause);
		} catch (RuntimeException e) {
			// Nothing is left to report it to.
		}
	}

	private void handleLine(String line) {
		JsonNode message;
		try {
			message = NdjsonCodec.decode(line);
		} catch (JsonProcessingException e) {
			// An agent that prints a banner or a log line on stdout is still an agent.
			protocolError("A line that is not JSON", line, e);
			return;
		}
		if (message == null) {
			return;
		}
		if (!message.isObject()) {
			protocolError("A message that is not a JSON object", line, null);
			return;
		}
		JsonNode method = message.get("method");
		JsonNode id = message.get("id");
		boolean hasId = id != null && !id.isNull();
		if (method != null) {
			if (!method.isTextual()) {
				protocolError("A message whose method is not a string", line, null);
			} else if (hasId) {
				handleRequest(method.asText(), message.get("params"), id);
			} else {
				try {
					handler.onNotification(method.asText(), message.get("params"));
				} catch (RuntimeException e) {
					protocolError("Handling the notification " + method.asText() + " failed", line, e);
				}
			}
		} else if (message.has("result") || message.has("error")) {
			handleResponse(message, id, line);
		} else {
			protocolError("A message that is neither a request, a notification nor a response", line, null);
		}
	}

	private void handleRequest(String method, JsonNode params, JsonNode id) {
		PendingAnswer responder = new PendingAnswer(id);
		try {
			handler.onRequest(method, params, responder);
		} catch (RuntimeException e) {
			protocolError("Handling the request " + method + " failed", null, e);
			// The peer is waiting; an answer already given wins over this one.
			responder.error(JsonRpcException.INTERNAL_ERROR, "Internal error");
		}
	}

	private void handleResponse(JsonNode message, JsonNode id, String line) {
		CompletableFuture<JsonNode> future = id != null && id.isIntegralNumber() && id.canConvertToLong()
				? pending.remove(id.asLong())
				: null;
		if (future == null) {
			protocolError("A response to a request that is not pending", line, null);
			return;
		}
		JsonNode error = message.get("error");
		if (error != null && !error.isNull()) {
			future.completeExceptionally(new JsonRpcException(error.path("code").asInt(JsonRpcException.INTERNAL_ERROR),
					error.path("message").asText("Unknown error"), error.get("data")));
		} else {
			JsonNode result = message.get("result");
			future.complete(result == null ? JSON.nullNode() : result);
		}
	}

	private void protocolError(String description, String line, Throwable cause) {
		try {
			handler.onProtocolError(description, line, cause);
		} catch (RuntimeException e) {
			// A failing error report must not take the reader down.
		}
	}

	private void write(JsonNode message) throws IOException {
		String line = NdjsonCodec.encode(message);
		synchronized (writeLock) {
			out.write(line);
			out.write('\n');
			out.flush();
		}
	}

	private static ObjectNode envelope() {
		ObjectNode message = JSON.objectNode();
		message.put("jsonrpc", "2.0");
		return message;
	}

	private static ConnectionClosedException asClosed(IOException e) {
		return e instanceof ConnectionClosedException closed ? closed
				: new ConnectionClosedException("The connection failed: " + e.getMessage(), e);
	}

	private final class PendingAnswer implements Responder {

		private final JsonNode id;
		private final AtomicBoolean answered = new AtomicBoolean();

		PendingAnswer(JsonNode id) {
			this.id = id;
		}

		@Override
		public void result(JsonNode result) {
			ObjectNode message = envelope();
			// The id goes back exactly as it came, string or number.
			message.set("id", id);
			message.set("result", result == null ? JSON.nullNode() : result);
			send(message);
		}

		@Override
		public void error(int code, String text, JsonNode data) {
			ObjectNode message = envelope();
			message.set("id", id);
			ObjectNode error = message.putObject("error");
			error.put("code", code);
			error.put("message", text);
			if (data != null) {
				error.set("data", data);
			}
			send(message);
		}

		private void send(ObjectNode message) {
			if (!answered.compareAndSet(false, true)) {
				return;
			}
			try {
				write(message);
			} catch (IOException e) {
				// The peer is gone and no longer waits for this answer.
			}
		}
	}
}
