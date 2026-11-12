package io.github.liaoalia.kiroacp.core.services;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.liaoalia.kiroacp.core.acp.api.ClientServices;
import io.github.liaoalia.kiroacp.core.acp.impl.KiroAgents;
import io.github.liaoalia.kiroacp.core.acp.process.AgentCommandLine;
import io.github.liaoalia.kiroacp.core.acp.process.Platform;
import io.github.liaoalia.kiroacp.core.host.Logger;

/**
 * The feature services composed into the {@link ClientServices} the ACP layer
 * calls (the reference's {@code createClientServices}).
 *
 * <p>Every method returns at once, as the ACP layer requires. The three
 * services belong to whoever created them and are closed by them;
 * {@link #close()} only ends this object's own worker threads.
 */
public final class HostServices implements ClientServices, AutoCloseable {

	/** Name of the terminal a sign-in runs in. */
	public static final String SIGN_IN_TERMINAL_NAME = "Kiro login";

	/** What the user is told while the sign-in terminal is open. */
	public static final String SIGN_IN_HINT = "Finish signing in in the terminal; the chat reconnects when it completes (or click Reconnect).";

	static final long LOGOUT_TIMEOUT_MS = 15_000;

	private final PermissionManager permissions;
	private final FsService fs;
	private final TerminalService terminal;
	private final ExternalActions external;
	private final Logger logger;
	/** A thread per task: each may wait for the user. */
	private final ExecutorService workers = Workers.pool("kiro-host");
	private final long logoutTimeoutMillis;

	/**
	 * @param external may be {@code null}: opening URLs and terminal sign-in are then reported to
	 *                 the agent as not supported
	 * @param logger   may be {@code null}
	 */
	public HostServices(PermissionManager permissions, FsService fs, TerminalService terminal, ExternalActions external,
			Logger logger) {
		this(permissions, fs, terminal, external, logger, LOGOUT_TIMEOUT_MS);
	}

	HostServices(PermissionManager permissions, FsService fs, TerminalService terminal, ExternalActions external,
			Logger logger, long logoutTimeoutMillis) {
		this.permissions = permissions;
		this.fs = fs;
		this.terminal = terminal;
		this.external = external;
		this.logger = logger != null ? logger : Logger.NONE;
		this.logoutTimeoutMillis = logoutTimeoutMillis;
	}

	/** The question a URL is confirmed with. */
	public static String openUrlQuestion(String host) {
		return "Kiro wants to open " + host + " in your browser.";
	}

	@Override
	public CompletableFuture<JsonNode> requestPermission(JsonNode request, CompletionStage<Void> cancelled) {
		return permissions.request(request, cancelled);
	}

	@Override
	public CompletableFuture<JsonNode> readTextFile(JsonNode request) {
		return fs.readTextFile(request);
	}

	@Override
	public CompletableFuture<JsonNode> writeTextFile(JsonNode request) {
		return fs.writeTextFile(request);
	}

	@Override
	public CompletableFuture<JsonNode> createTerminal(JsonNode request) {
		return terminal.createTerminal(request);
	}

	@Override
	public CompletableFuture<JsonNode> terminalOutput(JsonNode request) {
		return terminal.terminalOutput(request);
	}

	@Override
	public CompletableFuture<JsonNode> waitForTerminalExit(JsonNode request) {
		return terminal.waitForTerminalExit(request);
	}

	@Override
	public CompletableFuture<JsonNode> killTerminal(JsonNode request) {
		return terminal.killTerminal(request);
	}

	@Override
	public CompletableFuture<JsonNode> releaseTerminal(JsonNode request) {
		return terminal.releaseTerminal(request);
	}

	@Override
	public CompletableFuture<Void> openExternalUrl(String url) {
		if (external == null) {
			return null;
		}
		String host = hostOf(url);
		CompletableFuture<Void> result = new CompletableFuture<>();
		submit(result, () -> {
			CompletionStage<ExternalActions.OpenResult> asked = external.confirmAndOpenUrl(host, url);
			if (asked == null) {
				throw new IllegalStateException("User declined to open the URL");
			}
			asked.whenComplete((outcome, failure) -> {
				if (failure != null) {
					result.completeExceptionally(failure);
				} else if (outcome == ExternalActions.OpenResult.OPENED) {
					logger.info("opened external URL " + host);
					result.complete(null);
				} else if (outcome == ExternalActions.OpenResult.FAILED) {
					result.completeExceptionally(
							new IllegalStateException("The operating system could not open the URL"));
				} else {
					result.completeExceptionally(new IllegalStateException("User declined to open the URL"));
				}
			});
		});
		return result;
	}

	@Override
	public CompletableFuture<Void> runTerminalAuth(JsonNode method, String command, List<String> args) {
		if (external == null) {
			return null;
		}
		String id = method.path("id").asText("");
		String name = method.path("name").asText("");
		List<String> all = signInArgs(args, method);
		Map<String, String> env = signInEnv(method);
		CompletableFuture<Void> result = new CompletableFuture<>();
		submit(result, () -> {
			if (KiroAgents.FALLBACK_LOGIN_METHOD_ID.equals(id)) {
				logout(command);
			}
			// The sign-in is the terminal's process (not a command typed into a shell),
			// so its exit is observable: code 0 means signed in and the caller
			// reconnects at once.
			CompletionStage<Integer> exit = external.runSignIn(new ExternalActions.SignIn(SIGN_IN_TERMINAL_NAME, command,
					all, AgentCommandLine.of(command, all, Platform.current(), System.getenv()), env));
			if (exit == null) {
				throw new IllegalStateException("The sign-in terminal could not be started");
			}
			logger.info("started terminal auth \"" + name + "\" (" + id + "): " + displayLine(command, all));
			exit.whenComplete((code, failure) -> {
				if (failure != null) {
					result.completeExceptionally(failure);
				} else if (code == null || code == 0) {
					logger.info("terminal auth finished" + (code != null ? " (exit 0)" : " (terminal closed)"));
					result.complete(null);
				} else {
					result.completeExceptionally(new IllegalStateException(
							"The sign-in command exited with code " + code + "; see the Kiro login terminal."));
				}
			});
		});
		return result;
	}

	/** Ends this object's worker threads; a sign-in or question in progress is left to finish. */
	@Override
	public void close() {
		workers.shutdown();
	}

	// ---------------------------------------------------------------------
	// Pure parts
	// ---------------------------------------------------------------------

	/**
	 * What the user is shown of a URL before it is opened: its host (with a
	 * port that is not the scheme's own), or the whole URL when it has none or
	 * cannot be parsed.
	 */
	static String hostOf(String url) {
		try {
			URI uri = new URI(url);
			String host = uri.getHost();
			if (host == null || host.isEmpty()) {
				return url;
			}
			host = host.toLowerCase(Locale.ROOT);
			int port = uri.getPort();
			return port < 0 || port == defaultPort(uri.getScheme()) ? host : host + ":" + port;
		} catch (URISyntaxException e) {
			return url;
		}
	}

	private static int defaultPort(String scheme) {
		switch (scheme == null ? "" : scheme.toLowerCase(Locale.ROOT)) {
		case "http":
		case "ws":
			return 80;
		case "https":
		case "wss":
			return 443;
		case "ftp":
			return 21;
		default:
			return -1;
		}
	}

	/**
	 * The arguments of a sign-in: those of the invocation, then the method's own
	 * (per the ACP spec a method's args are appended to the agent invocation).
	 */
	static List<String> signInArgs(List<String> invocationArgs, JsonNode method) {
		List<String> all = new ArrayList<>(invocationArgs);
		JsonNode own = method.get("args");
		if (own != null && own.isArray()) {
			for (JsonNode arg : own) {
				all.add(arg.asText());
			}
		}
		return all;
	}

	/** The method's {@code env} object as a map; entries that are not strings are skipped. */
	static Map<String, String> signInEnv(JsonNode method) {
		Map<String, String> env = new LinkedHashMap<>();
		JsonNode own = method.get("env");
		if (own != null && own.isObject()) {
			own.fields().forEachRemaining(field -> {
				if (field.getValue().isTextual()) {
					env.put(field.getKey(), field.getValue().textValue());
				}
			});
		}
		return env;
	}

	private static String displayLine(String command, List<String> args) {
		List<String> parts = new ArrayList<>();
		parts.add(command);
		parts.addAll(args);
		return String.join(" ", parts);
	}

	/**
	 * {@code <cli> logout}, so the following {@code <cli> login} is a fresh
	 * sign-in instead of a refusal ("already logged in") or another attempt to
	 * refresh a dead token. Best effort: a failure (already signed out, timeout)
	 * is logged and ignored. Blocks until the command ended or timed out.
	 */
	void logout(String command) {
		AgentCommandLine line = AgentCommandLine.of(command, List.of("logout"), Platform.current(), System.getenv());
		List<String> argv = new ArrayList<>();
		argv.add(line.command());
		argv.addAll(line.args());
		String failure;
		try {
			ProcessBuilder builder = new ProcessBuilder(argv);
			builder.redirectErrorStream(true);
			// Nobody reads it, and an unread pipe would block the command.
			builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
			Process process = builder.start();
			process.getOutputStream().close();
			if (process.waitFor(logoutTimeoutMillis, TimeUnit.MILLISECONDS)) {
				failure = process.exitValue() == 0 ? null : "exited with code " + process.exitValue();
			} else {
				// Behind cmd.exe the CLI is a grandchild: list before killing the shell.
				List<ProcessHandle> tree = new ArrayList<>(process.descendants().toList());
				tree.add(process.toHandle());
				tree.forEach(ProcessHandle::destroyForcibly);
				failure = "timed out";
			}
		} catch (IOException | RuntimeException e) {
			failure = String.valueOf(e.getMessage());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			failure = "interrupted";
		}
		if (failure != null) {
			logger.warn("logout before sign-in failed (ignored): " + failure);
		} else {
			logger.info("signed out before the fresh sign-in");
		}
	}

	private interface Work {
		void run() throws Exception;
	}

	private void submit(CompletableFuture<Void> result, Work work) {
		try {
			workers.execute(() -> {
				try {
					work.run();
				} catch (Throwable e) {
					// Whatever it is, the agent must get an answer.
					result.completeExceptionally(e);
				}
			});
		} catch (RejectedExecutionException e) {
			result.completeExceptionally(new IllegalStateException("The host services are closed"));
		}
	}
}
