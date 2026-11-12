package io.github.liaoalia.kiroacp.core.acp;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The reference's fake Kiro agent ({@code tests/fixtures/fake-agent/agent.mjs})
 * as a child process, with its stdio as lines of JSON.
 *
 * <p>Needs {@code node} on the PATH and {@code npm ci} run in the fixture folder.
 */
final class FakeAgent implements AutoCloseable {

	private static final Object END = new Object();

	private final Process process;
	private final BufferedWriter stdin;
	private final BlockingQueue<Object> messages = new ArrayBlockingQueue<>(1024);

	private FakeAgent(Process process) {
		this.process = process;
		this.stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
		Thread reader = new Thread(this::read, "fake-agent-stdout");
		reader.setDaemon(true);
		reader.start();
	}

	/** @param arguments what follows {@code agent.mjs acp}, e.g. {@code --scenario chat} */
	static FakeAgent start(String... arguments) throws IOException {
		Path script = fixtures().resolve("fake-agent").resolve("agent.mjs");
		if (!Files.isRegularFile(script)) {
			throw new IOException("Fake agent not found: " + script);
		}
		if (!Files.isDirectory(script.resolveSibling("node_modules"))) {
			throw new IOException("Run `npm --prefix tests/fixtures/fake-agent ci` first: no node_modules next to " + script);
		}
		List<String> command = new ArrayList<>(List.of("node", script.toString(), "acp"));
		command.addAll(List.of(arguments));
		ProcessBuilder builder = new ProcessBuilder(command);
		// The agent's log goes to the test output; an unread pipe would block it.
		builder.redirectError(ProcessBuilder.Redirect.INHERIT);
		return new FakeAgent(builder.start());
	}

	static Path fixtures() {
		String dir = System.getProperty("kiroacp.fixtures");
		if (dir == null || dir.isBlank()) {
			throw new IllegalStateException("System property kiroacp.fixtures is not set (the test bundle's pom sets it)");
		}
		return Path.of(dir).toAbsolutePath().normalize();
	}

	void send(JsonNode message) throws IOException {
		stdin.write(NdjsonCodec.encode(message));
		stdin.write('\n');
		stdin.flush();
	}

	/** @return the next message from the agent, or {@code null} once its output has ended */
	JsonNode receive(long timeout, TimeUnit unit) throws InterruptedException {
		Object next = messages.poll(timeout, unit);
		if (next == null) {
			throw new AssertionError("No message from the fake agent within " + timeout + " " + unit);
		}
		if (next instanceof JsonNode message) {
			return message;
		}
		if (next instanceof Throwable failure) {
			throw new AssertionError("The fake agent wrote something that is not a JSON line", failure);
		}
		return null;
	}

	private void read() {
		Object last = END;
		try (BufferedReader stdout = new BufferedReader(
				new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
			for (String line = stdout.readLine(); line != null; line = stdout.readLine()) {
				JsonNode message = NdjsonCodec.decode(line);
				if (message != null) {
					messages.put(message);
				}
			}
		} catch (IOException e) {
			last = e;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return;
		}
		messages.offer(last);
	}

	@Override
	public void close() throws IOException, InterruptedException {
		try {
			stdin.close();
		} catch (IOException e) {
			// The agent may already be gone.
		}
		if (!process.waitFor(5, TimeUnit.SECONDS)) {
			process.destroyForcibly();
			process.waitFor(5, TimeUnit.SECONDS);
		}
	}
}
