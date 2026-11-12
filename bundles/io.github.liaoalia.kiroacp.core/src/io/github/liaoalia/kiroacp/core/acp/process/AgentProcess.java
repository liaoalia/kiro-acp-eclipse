package io.github.liaoalia.kiroacp.core.acp.process;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import io.github.liaoalia.kiroacp.core.host.Logger;

/**
 * The {@code kiro-cli acp} child process: started with piped stdio, its
 * stderr copied to the log, ended on request.
 */
public final class AgentProcess {

	/** How long a process asked to end may take before it is killed. */
	static final Duration GRACE = Duration.ofSeconds(2);

	private final Process process;
	private final boolean viaShell;
	private final CompletableFuture<Integer> exit;
	/** Completes when the processes behind a terminated shell are gone too. */
	private volatile CompletableFuture<Void> behindShellGone = CompletableFuture.completedFuture(null);

	private AgentProcess(Process process, boolean viaShell) {
		this.process = process;
		this.viaShell = viaShell;
		this.exit = process.onExit().thenCompose(ended -> behindShellGone.handle((done, failure) -> ended.exitValue()));
	}

	/**
	 * @param command the executable, already resolved (see {@link CliPathResolver})
	 * @param cwd	 working directory, or {@code null} for this process's own
	 * @param env	 the child's whole environment
	 * @throws IOException if the process cannot be started, e.g. the executable does not exist
	 */
	public static AgentProcess start(String command, List<String> args, Path cwd, Map<String, String> env, Logger logger)
			throws IOException {
		AgentCommandLine line = AgentCommandLine.of(command, args, Platform.current(), env);
		List<String> full = new ArrayList<>();
		full.add(line.command());
		full.addAll(line.args());
		ProcessBuilder builder = new ProcessBuilder(full);
		if (cwd != null) {
			builder.directory(cwd.toFile());
		}
		builder.environment().clear();
		builder.environment().putAll(env);
		Process process;
		try {
			process = builder.start();
		} catch (RuntimeException e) {
			// A NUL in an argument, a refused quoting: to the caller the same as a failed start.
			throw new IOException(e.getMessage(), e);
		}
		AgentProcess agent = new AgentProcess(process, line.viaShell());
		agent.copyStderr(logger);
		return agent;
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

	/**
	 * @return completes with the exit code once the process has ended, and after
	 *		 {@link #terminate()} also the agent a shell was running
	 */
	public CompletableFuture<Integer> onExit() {
		return exit;
	}

	/** Processes started by this one that are still running; the agent itself when it runs behind a shell. */
	public List<ProcessHandle> descendants() {
		return process.descendants().toList();
	}

	/**
	 * Asks the process to end and kills it if it has not after {@link #GRACE}.
	 * On Windows the request is already a kill. Does not wait.
	 *
	 * @return the same future as {@link #onExit()}
	 */
	public CompletableFuture<Integer> terminate() {
		if (!process.isAlive()) {
			return exit;
		}
		// Killing cmd.exe leaves the agent it started running, so that one is ended too.
		List<ProcessHandle> behindShell = viaShell ? descendants() : List.of();
		behindShellGone = CompletableFuture
				.allOf(behindShell.stream().map(ProcessHandle::onExit).toArray(CompletableFuture[]::new));
		process.destroy();
		behindShell.forEach(ProcessHandle::destroy);
		CompletableFuture.delayedExecutor(GRACE.toMillis(), TimeUnit.MILLISECONDS).execute(() -> {
			if (process.isAlive()) {
				process.destroyForcibly();
			}
			behindShell.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
		});
		return exit;
	}

	private void copyStderr(Logger logger) {
		Thread thread = new Thread(() -> {
			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
				for (String line = reader.readLine(); line != null; line = reader.readLine()) {
					report(logger, line);
				}
			} catch (IOException e) {
				// The process is gone, and its stderr with it.
			}
		}, "acp-stderr");
		thread.setDaemon(true);
		thread.start();
	}

	private static void report(Logger logger, String line) {
		try {
			if (logger.traces()) {
				logger.trace(Logger.Direction.STDERR, line);
			}
			if (!line.isBlank()) {
				logger.warn("[kiro-cli] " + line);
			}
		} catch (RuntimeException e) {
			// An unread stderr pipe would block the agent, so a failing logger must not stop the copying.
		}
	}
}
