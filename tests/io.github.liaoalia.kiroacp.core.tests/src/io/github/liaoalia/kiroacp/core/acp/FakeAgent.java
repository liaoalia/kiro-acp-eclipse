package io.github.liaoalia.kiroacp.core.acp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The reference's fake Kiro agent ({@code tests/fixtures/fake-agent/agent.mjs})
 * as a child process.
 *
 * <p>Needs {@code node} on the PATH and {@code npm ci} run in the fixture folder.
 */
public final class FakeAgent implements AutoCloseable {

	private final Process process;

	private FakeAgent(Process process) {
		this.process = process;
	}

	/** @param arguments what follows {@code agent.mjs acp}, e.g. {@code --scenario chat} */
	public static FakeAgent start(String... arguments) throws IOException {
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

	public static Path fixtures() {
		String dir = System.getProperty("kiroacp.fixtures");
		if (dir == null || dir.isBlank()) {
			throw new IllegalStateException("System property kiroacp.fixtures is not set (the test bundle's pom sets it)");
		}
		return Path.of(dir).toAbsolutePath().normalize();
	}

	/** What the agent writes. */
	public InputStream stdout() {
		return process.getInputStream();
	}

	/** What the agent reads. */
	public OutputStream stdin() {
		return process.getOutputStream();
	}

	public boolean isAlive() {
		return process.isAlive();
	}

	/** Kills the agent at once, as a crash would. */
	public void kill() throws InterruptedException {
		process.destroyForcibly();
		process.waitFor(5, TimeUnit.SECONDS);
	}

	@Override
	public void close() throws InterruptedException {
		try {
			process.getOutputStream().close();
		} catch (IOException e) {
			// The agent may already be gone.
		}
		if (!process.waitFor(5, TimeUnit.SECONDS)) {
			kill();
		}
	}
}
