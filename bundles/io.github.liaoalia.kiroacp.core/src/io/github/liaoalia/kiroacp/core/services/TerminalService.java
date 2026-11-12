package io.github.liaoalia.kiroacp.core.services;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.liaoalia.kiroacp.core.host.Logger;
import io.github.liaoalia.kiroacp.core.host.Settings;
import io.github.liaoalia.kiroacp.core.host.Settings.TerminalMode;
import io.github.liaoalia.kiroacp.core.host.Workspace;
import io.github.liaoalia.kiroacp.core.pure.output.EnvVariable;
import io.github.liaoalia.kiroacp.core.pure.output.OutputBuffer;
import io.github.liaoalia.kiroacp.core.pure.output.ShellText;
import io.github.liaoalia.kiroacp.core.pure.output.TerminalPresentation;
import io.github.liaoalia.kiroacp.core.pure.output.TerminalPresentation.LinePrefixer;
import io.github.liaoalia.kiroacp.core.pure.text.Platform;

/**
 * ACP terminal methods (reference D9).
 *
 * <p>Each {@code terminal/create} starts a real child process through the
 * platform shell; its output is retained (bounded) for the agent and the chat
 * card, and mirrored into a {@link TerminalDisplay} according to
 * {@link Settings#terminalMode()}:
 *
 * <ul>
 * <li>shared (default): one console per ACP session, created lazily and
 * revealed only on its first command; every command of the session writes a
 * header, its output and an exit footer into it.
 * <li>per command: one console per command, revealed on creation.
 * <li>hidden: no console at all.
 * </ul>
 *
 * <p>Thread-safe. Every method may be called from any thread and returns at
 * once: processes are started on the service's own thread, stopped on another,
 * and each command's output is read on a thread of its own. The commands get
 * no input: their standard input is at its end from the start.
 */
public final class TerminalService implements AutoCloseable {

	/**
	 * Output retained for a terminal and how it ended.
	 *
	 * @param exited   {@code false} while the command runs
	 * @param exitCode {@code null} while running, and when it ended without a code (by a signal,
	 *                 or it could not be started)
	 */
	public record TerminalSnapshot(String output, boolean exited, Integer exitCode) {
	}

	record ExitStatus(Integer exitCode, String signal) implements TerminalPresentation.ExitLike {
	}

	static final long DEFAULT_LIMIT = 1024 * 1024;
	/** POSIX: SIGKILL follows SIGTERM after this long for whatever is still alive. */
	static final long POSIX_KILL_GRACE_MS = 2_000;
	/**
	 * How long after the shell ended its output may stay open before the command
	 * counts as ended anyway: a process it left behind keeps the pipe, and the
	 * agent waiting for the exit must not wait for that one.
	 */
	static final long DRAIN_GRACE_MS = 2_000;

	private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
	private static final int SIGTERM = 15;
	private static final int SIGKILL = 9;
	/** Windows: how long a killed shell may take to be gone before its children are looked for again. */
	private static final long WINDOWS_EXIT_WAIT_MS = 500;
	private static final int KILL_SWEEPS = 3;

	/** A console and the one place its text goes through, so concurrent commands do not interleave mid-chunk. */
	private final class Mirror {
		final TerminalDisplay.Console console;
		/** The user closed it or the service let go of it: nothing more is written. */
		volatile boolean closed;

		Mirror(TerminalDisplay.Console console) {
			this.console = console;
		}

		synchronized void write(LinePrefixer prefixer, String text) {
			if (closed) {
				return;
			}
			try {
				console.write(prefixer != null ? prefixer.apply(text) : text);
			} catch (RuntimeException e) {
				logger.error("Writing to a terminal console failed", e);
			}
		}

		synchronized void exited(String terminalId, ExitStatus status) {
			if (closed) {
				return;
			}
			try {
				console.exited(terminalId, status.exitCode(), status.signal());
			} catch (RuntimeException e) {
				logger.error("Marking a terminal console as exited failed", e);
			}
		}
	}

	private static final class Entry implements TerminalPresentation.MirrorCandidate {
		final String id;
		final String sessionId;
		final TerminalMode mode;
		final OutputBuffer buffer;
		final CompletableFuture<ExitStatus> exitFuture = new CompletableFuture<>();
		final AtomicBoolean killed = new AtomicBoolean();
		/** {@code terminal/release} happened: in shared mode, output of a surviving process is no longer mirrored. */
		volatile boolean released;
		volatile ExitStatus exit;
		/** {@code null} when the process could not be started. */
		volatile Process process;
		/** Where this command is mirrored; {@code null} in hidden mode. */
		volatile Mirror mirror;
		/** Shared mode, for a command started while another one runs. Used under the mirror's monitor. */
		volatile LinePrefixer prefixer;

		Entry(String id, String sessionId, TerminalMode mode, OutputBuffer buffer) {
			this.id = id;
			this.sessionId = sessionId;
			this.mode = mode;
			this.buffer = buffer;
		}

		@Override
		public TerminalMode mode() {
			return mode;
		}

		@Override
		public boolean released() {
			return released;
		}

		void write(String text) {
			Mirror target = mirror;
			if (target != null) {
				target.write(prefixer, text);
			}
		}
	}

	/** Where a terminal id was shown, kept after release for "Show terminal". */
	private record Placement(TerminalMode mode, String sessionId, Mirror own) {
	}

	private final Settings settings;
	private final Workspace workspace;
	private final TerminalDisplay display;
	private final Logger logger;
	private final long drainGraceMillis;
	/** One thread: consoles are opened and commands started in the order they were asked for. */
	private final ExecutorService starter = Workers.single("kiro-terminal");
	/** Stopping must not wait behind a start that is slow to open its console. */
	private final ExecutorService killer = Workers.pool("kiro-terminal-kill");
	private final Object lock = new Object();
	private final Map<String, Entry> active = new LinkedHashMap<>();
	/** Where every terminal id ever created was shown, including released ones. */
	private final Map<String, Placement> placements = new HashMap<>();
	/** Final output/exit of released terminals so tool cards can still show them. */
	private final Map<String, TerminalSnapshot> released = new HashMap<>();
	/** Shared mode: the session's console, present while it is open. */
	private final Map<String, Mirror> shared = new HashMap<>();
	private final AtomicLong counter = new AtomicLong();
	private volatile boolean closed;
	/**
	 * Random per-run id baked into every terminal id, so ids of an earlier run
	 * (replayed by {@code session/load}) never match a live one.
	 */
	private final String runId = TerminalPresentation.makeRunId();

	/**
	 * @param settings  the terminal mode and the default output limit, read on every create
	 * @param workspace the working directory of a request without one
	 * @param display   may be {@code null}: nothing is shown
	 * @param logger    may be {@code null}
	 */
	public TerminalService(Settings settings, Workspace workspace, TerminalDisplay display, Logger logger) {
		this(settings, workspace, display, logger, DRAIN_GRACE_MS);
	}

	TerminalService(Settings settings, Workspace workspace, TerminalDisplay display, Logger logger,
			long drainGraceMillis) {
		this.drainGraceMillis = drainGraceMillis;
		this.settings = settings;
		this.workspace = workspace;
		this.display = display != null ? display : TerminalDisplay.NONE;
		this.logger = logger != null ? logger : Logger.NONE;
	}

	// ---------------------------------------------------------------------
	// ClientServices
	// ---------------------------------------------------------------------

	/**
	 * ClientServices.createTerminal. Completes once the process is started. A
	 * process that cannot be started still yields a terminal: its output says
	 * why and it has ended without an exit code.
	 */
	public CompletableFuture<JsonNode> createTerminal(JsonNode request) {
		JsonNode req = request != null ? request : MissingNode.getInstance();
		if (!req.path("command").isTextual() || req.path("command").textValue().isEmpty()) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("terminal/create requires a command"));
		}
		String id = TerminalPresentation.makeTerminalId(runId, counter.incrementAndGet());
		CompletableFuture<JsonNode> result = new CompletableFuture<>();
		try {
			starter.execute(() -> {
				try {
					start(id, req);
					result.complete(JSON.objectNode().put("terminalId", id));
				} catch (Throwable e) {
					// Whatever it is, the agent must get an answer.
					result.completeExceptionally(e);
				}
			});
		} catch (RejectedExecutionException e) {
			result.completeExceptionally(closedFailure());
		}
		return result;
	}

	/** ClientServices.terminalOutput */
	public CompletableFuture<JsonNode> terminalOutput(JsonNode request) {
		Entry entry;
		try {
			entry = require(request);
		} catch (IllegalArgumentException e) {
			return CompletableFuture.failedFuture(e);
		}
		ObjectNode response = JSON.objectNode();
		response.put("output", ShellText.stripAnsi(entry.buffer.text()));
		response.put("truncated", entry.buffer.truncated());
		ExitStatus exit = entry.exit;
		if (exit != null) {
			response.set("exitStatus", exitJson(exit));
		}
		return CompletableFuture.completedFuture(response);
	}

	/** ClientServices.waitForTerminalExit */
	public CompletableFuture<JsonNode> waitForTerminalExit(JsonNode request) {
		Entry entry;
		try {
			entry = require(request);
		} catch (IllegalArgumentException e) {
			return CompletableFuture.failedFuture(e);
		}
		return entry.exitFuture.thenApply(TerminalService::exitJson);
	}

	/**
	 * ClientServices.killTerminal: the terminal stays valid for output/wait.
	 * Completes when the processes were told to end, not when they have.
	 */
	public CompletableFuture<JsonNode> killTerminal(JsonNode request) {
		Entry entry;
		try {
			entry = require(request);
		} catch (IllegalArgumentException e) {
			return CompletableFuture.failedFuture(e);
		}
		return kill(entry).thenApply(done -> JSON.objectNode());
	}

	/**
	 * Stop every command still running for {@code sessionId} (the user pressed
	 * Stop, reference D27): their {@code terminal/wait_for_exit} complete, so an
	 * agent waiting on one can end the turn. The terminals stay valid for
	 * output/wait like after {@code terminal/kill}.
	 *
	 * @return the ids that were killed
	 */
	public CompletableFuture<List<String>> killSession(String sessionId) {
		List<Entry> running = new ArrayList<>();
		synchronized (lock) {
			for (Entry entry : active.values()) {
				if (entry.sessionId.equals(sessionId) && entry.exit == null && !entry.killed.get()) {
					running.add(entry);
				}
			}
		}
		List<String> ids = running.stream().map(entry -> entry.id).toList();
		return CompletableFuture.allOf(running.stream().map(this::kill).toArray(CompletableFuture[]::new))
				.thenApply(done -> ids);
	}

	/**
	 * ClientServices.releaseTerminal: kills if running; the console stays open.
	 * In shared mode nothing the process (or a survivor of the kill) still
	 * writes reaches the session's console afterwards; only the exit footer does.
	 */
	public CompletableFuture<JsonNode> releaseTerminal(JsonNode request) {
		Entry entry;
		try {
			entry = require(request);
		} catch (IllegalArgumentException e) {
			return CompletableFuture.failedFuture(e);
		}
		entry.released = true;
		return kill(entry).thenApply(done -> {
			synchronized (lock) {
				// Not after disposeAll: that forgot the terminal, and nothing would forget it again.
				if (active.remove(entry.id, entry)) {
					released.put(entry.id, snapshotOf(entry));
				}
			}
			return JSON.objectNode();
		});
	}

	/** {@link #snapshot(String, String)} without the session check. */
	public TerminalSnapshot snapshot(String terminalId) {
		return snapshot(terminalId, null);
	}

	/**
	 * Output so far and exit code of a terminal (also after release), for the
	 * chat tool card.
	 *
	 * @param sessionId {@code null} to skip the session check
	 * @return {@code null} for unknown ids (including ids of a previous run) and, when
	 *         {@code sessionId} is given, for terminals of another session
	 */
	public TerminalSnapshot snapshot(String terminalId, String sessionId) {
		synchronized (lock) {
			if (!belongsTo(terminalId, sessionId)) {
				return null;
			}
			Entry entry = active.get(terminalId);
			if (entry != null) {
				return snapshotOf(entry);
			}
			return released.get(terminalId);
		}
	}

	// ---------------------------------------------------------------------
	// UI helpers
	// ---------------------------------------------------------------------

	/** {@link #showTerminal(String, String)} without the session check. */
	public boolean showTerminal(String terminalId) {
		return showTerminal(terminalId, null);
	}

	/**
	 * Reveal the console that showed {@code terminalId} (works after release
	 * too): its own in per-command mode, the session's in shared mode.
	 *
	 * @param sessionId {@code null} to skip the session check
	 * @return {@code false} when there is none (hidden mode, unknown id, the user closed the
	 *         console) or, when {@code sessionId} is given, the terminal belongs to another session
	 */
	public boolean showTerminal(String terminalId, String sessionId) {
		Mirror mirror;
		synchronized (lock) {
			Placement placement = placements.get(terminalId);
			if (placement == null || !belongsTo(terminalId, sessionId)) {
				return false;
			}
			mirror = placement.mode() == TerminalMode.SHARED ? shared.get(placement.sessionId()) : placement.own();
		}
		if (mirror == null) {
			return false;
		}
		try {
			mirror.console.reveal(true);
		} catch (RuntimeException e) {
			logger.error("Revealing a terminal console failed", e);
			return false;
		}
		return true;
	}

	/** Ids of terminals the agent still holds. */
	public List<String> activeIds() {
		synchronized (lock) {
			return List.copyOf(active.keySet());
		}
	}

	/** The current terminal mode (read on every {@code terminal/create}). */
	public TerminalMode currentMode() {
		TerminalMode mode = settings.terminalMode();
		return mode != null ? mode : TerminalPresentation.DEFAULT_TERMINAL_MODE;
	}

	/**
	 * Kill every running process and forget all terminals. Shared session
	 * consoles are closed; per-command consoles stay open. The processes are
	 * told to end before this returns. The service stays usable.
	 */
	public void disposeAll() {
		List<Entry> entries;
		List<Mirror> sharedConsoles;
		synchronized (lock) {
			entries = new ArrayList<>(active.values());
			active.clear();
			sharedConsoles = new ArrayList<>(shared.values());
			shared.clear();
			placements.clear();
			released.clear();
		}
		for (Entry entry : entries) {
			killNow(entry);
			Mirror mirror = entry.mirror;
			if (mirror != null) {
				mirror.closed = true;
			}
		}
		for (Mirror mirror : sharedConsoles) {
			mirror.closed = true;
			try {
				mirror.console.close();
			} catch (RuntimeException e) {
				logger.error("Closing a terminal console failed", e);
			}
		}
	}

	/** {@link #disposeAll()}, and no terminal can be created afterwards. */
	@Override
	public void close() {
		closed = true;
		disposeAll();
		starter.shutdown();
		killer.shutdown();
	}

	// ---------------------------------------------------------------------
	// Starting
	// ---------------------------------------------------------------------

	/** On the starter thread. */
	private void start(String id, JsonNode req) {
		String command = req.get("command").textValue();
		String sessionId = req.path("sessionId").isTextual() && !req.get("sessionId").textValue().isEmpty()
				? req.get("sessionId").textValue()
				: "default";
		TerminalMode mode = currentMode();
		String commandLine = ShellText.buildShellCommand(command, strings(req.get("args")));
		String cwd = req.path("cwd").isTextual() ? req.get("cwd").textValue() : workspace.defaultCwd().toString();
		Map<String, String> env = ShellText.mergeEnv(System.getenv(), EnvVariable.listOf(req.get("env")));
		// As the reference: a limit the request names is taken as it is, zero meaning unbounded.
		long limit = req.path("outputByteLimit").isNumber() ? req.get("outputByteLimit").longValue() : defaultLimit();

		Entry entry = new Entry(id, sessionId, mode, new OutputBuffer(limit));
		attachPresentation(entry, command);
		synchronized (lock) {
			if (closed) {
				throw closedFailure();
			}
			active.put(id, entry);
		}
		entry.write(TerminalPresentation.formatCommandHeader(commandLine, cwd));

		Process process;
		try {
			ProcessBuilder builder = new ProcessBuilder(shellArgv(commandLine, Platform.current(), env));
			builder.directory(new File(cwd));
			// One stream, so what the command wrote to either keeps its order.
			builder.redirectErrorStream(true);
			builder.environment().clear();
			builder.environment().putAll(env);
			process = builder.start();
		} catch (IOException | RuntimeException e) {
			spawnFailed(entry, e);
			return;
		}
		entry.process = process;
		try {
			// Nobody can type into an agent terminal: a command that reads its input
			// finds the end at once instead of waiting for ever.
			process.getOutputStream().close();
		} catch (IOException e) {
			// The process is already gone.
		}
		Workers.daemon("kiro-terminal-" + id, () -> pump(entry, process)).start();
		process.onExit().thenRun(() -> exitedWithOpenOutput(entry, process));
		logger.info("terminal " + id + " (" + mode + "): spawned \"" + commandLine + "\" in " + cwd);
		if (closed) {
			// close() ran while this one was starting and could not see it.
			killNow(entry);
		}
	}

	/**
	 * The command line of the platform shell running {@code commandLine}, as
	 * Node's {@code spawn(…, {shell: true})} builds it: {@code %ComSpec% /d /s /c
	 * "<line>"} with the line passed verbatim, {@code /bin/sh -c <line>} elsewhere.
	 */
	static List<String> shellArgv(String commandLine, Platform platform, Map<String, String> env) {
		if (platform != Platform.WIN32) {
			return List.of("/bin/sh", "-c", commandLine);
		}
		String shell = null;
		for (Map.Entry<String, String> variable : env.entrySet()) {
			if (variable.getKey().equalsIgnoreCase("ComSpec")) {
				shell = variable.getValue();
				break;
			}
		}
		// With /s, cmd.exe strips exactly the outer pair of quotes and runs the rest as
		// typed. Java passes an argument on untouched only if it starts and ends with a
		// quote and does not end in \" ; the space keeps a line ending in a backslash
		// from being quoted a second time.
		String whole = "\"" + commandLine + (commandLine.endsWith("\\") ? " \"" : "\"");
		return List.of(shell == null || shell.isEmpty() ? "cmd.exe" : shell, "/d", "/s", "/c", whole);
	}

	private void spawnFailed(Entry entry, Exception failure) {
		String message = "[spawn error: " + failure.getMessage() + "]";
		entry.buffer.append("\n" + message + "\n");
		if (TerminalPresentation.shouldMirrorOutput(entry)) {
			entry.write("\r\n" + message + "\r\n");
		}
		logger.error("terminal " + entry.id + ": " + failure.getMessage());
		// No footer: the command never ran.
		ExitStatus status = new ExitStatus(null, null);
		entry.exit = status;
		Mirror mirror = entry.mirror;
		if (mirror != null) {
			mirror.exited(entry.id, status);
		}
		entry.exitFuture.complete(status);
	}

	/** Wire the entry to a console according to its mode. On the starter thread. */
	private void attachPresentation(Entry entry, String command) {
		switch (entry.mode) {
		case HIDDEN:
			synchronized (lock) {
				placements.put(entry.id, new Placement(TerminalMode.HIDDEN, entry.sessionId, null));
			}
			return;
		case PER_COMMAND: {
			TerminalDisplay.Console console = open(new TerminalDisplay.Target(TerminalMode.PER_COMMAND, entry.sessionId,
					entry.id, TerminalPresentation.commandTerminalName(command)), () -> {
						// The user closed the console: stop the process.
						Entry current;
						synchronized (lock) {
							current = active.get(entry.id);
						}
						if (current != null) {
							kill(current);
						}
					});
			Mirror mirror = console != null ? new Mirror(console) : null;
			entry.mirror = mirror;
			synchronized (lock) {
				placements.put(entry.id, new Placement(TerminalMode.PER_COMMAND, entry.sessionId, mirror));
			}
			if (mirror != null) {
				reveal(mirror);
			}
			return;
		}
		case SHARED:
		default: {
			boolean concurrent;
			synchronized (lock) {
				concurrent = active.values().stream()
						.anyMatch(other -> other.sessionId.equals(entry.sessionId) && other.exit == null);
				placements.put(entry.id, new Placement(TerminalMode.SHARED, entry.sessionId, null));
			}
			if (concurrent) {
				entry.prefixer = new LinePrefixer(TerminalPresentation.concurrentPrefix(entry.id));
			}
			// The mirror stops taking text once it is no longer the session's console:
			// after the user closed it, late output of killed children must not leak
			// into a console created for a later command.
			entry.mirror = sharedConsoleFor(entry.sessionId);
			return;
		}
		}
	}

	/** The session's shared console, created (and revealed once) on demand; {@code null} when there is no display. */
	private Mirror sharedConsoleFor(String sessionId) {
		synchronized (lock) {
			Mirror existing = shared.get(sessionId);
			if (existing != null) {
				return existing;
			}
		}
		// Creation needs no lock: only the starter thread creates.
		Mirror[] created = new Mirror[1];
		AtomicBoolean closedEarly = new AtomicBoolean();
		TerminalDisplay.Console console = open(new TerminalDisplay.Target(TerminalMode.SHARED, sessionId, null,
				TerminalPresentation.sharedTerminalName(sessionId)), () -> {
					Mirror mirror = created[0];
					if (mirror != null) {
						onSharedClosed(sessionId, mirror);
					} else {
						closedEarly.set(true);
					}
				});
		if (console == null || closedEarly.get()) {
			return null;
		}
		Mirror mirror = new Mirror(console);
		created[0] = mirror;
		synchronized (lock) {
			shared.put(sessionId, mirror);
		}
		// Revealed only now, on the session's first command; later commands never re-show it.
		reveal(mirror);
		logger.info("terminal: opened shared terminal for session " + sessionId);
		return mirror;
	}

	/** The user closed a session's shared console: stop its running commands. */
	private void onSharedClosed(String sessionId, Mirror mirror) {
		List<Entry> running = new ArrayList<>();
		synchronized (lock) {
			if (shared.get(sessionId) != mirror) {
				return;
			}
			shared.remove(sessionId);
			for (Entry entry : active.values()) {
				if (entry.sessionId.equals(sessionId) && entry.mode == TerminalMode.SHARED) {
					running.add(entry);
				}
			}
		}
		mirror.closed = true;
		running.forEach(this::kill);
	}

	private TerminalDisplay.Console open(TerminalDisplay.Target target, Runnable closedByUser) {
		try {
			return display.open(target, closedByUser);
		} catch (RuntimeException e) {
			logger.error("Opening a terminal console failed", e);
			return null;
		}
	}

	private void reveal(Mirror mirror) {
		try {
			mirror.console.reveal(false);
		} catch (RuntimeException e) {
			logger.error("Revealing a terminal console failed", e);
		}
	}

	// ---------------------------------------------------------------------
	// Output and exit
	// ---------------------------------------------------------------------

	/** On the command's own thread: its output until the end, then its exit. */
	private void pump(Entry entry, Process process) {
		try (Reader reader = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
			char[] chunk = new char[8192];
			// A character outside the BMP may arrive as two reads; the buffer counts
			// bytes per chunk, so the halves are handed on together.
			String carried = "";
			for (int n = reader.read(chunk); n >= 0; n = reader.read(chunk)) {
				if (n == 0) {
					continue;
				}
				String text = carried + new String(chunk, 0, n);
				carried = "";
				if (Character.isHighSurrogate(text.charAt(text.length() - 1))) {
					carried = text.substring(text.length() - 1);
					text = text.substring(0, text.length() - 1);
				}
				output(entry, text);
			}
			output(entry, carried);
		} catch (IOException e) {
			// The stream was closed under the reader: the same as its end.
		}
		boolean interrupted = false;
		while (true) {
			try {
				exited(entry, process.waitFor());
				break;
			} catch (InterruptedException e) {
				interrupted = true;
			}
		}
		if (interrupted) {
			Thread.currentThread().interrupt();
		}
	}

	private void output(Entry entry, String text) {
		if (text.isEmpty()) {
			return;
		}
		entry.buffer.append(text);
		if (TerminalPresentation.shouldMirrorOutput(entry)) {
			entry.write(ShellText.toPtyText(text));
		}
	}

	/** The shell has ended; if something it started keeps its output open, do not wait for that for ever. */
	private void exitedWithOpenOutput(Entry entry, Process process) {
		if (entry.exit != null) {
			return;
		}
		later(drainGraceMillis, () -> exited(entry, process.exitValue()));
	}

	/** Runs the task after a delay, on a thread that may block; also after {@link #close()}. */
	private void later(long delayMillis, Runnable task) {
		CompletableFuture.delayedExecutor(delayMillis, TimeUnit.MILLISECONDS).execute(() -> {
			try {
				killer.execute(task);
			} catch (RejectedExecutionException e) {
				task.run();
			}
		});
	}

	private void exited(Entry entry, int exitValue) {
		ExitStatus status = statusOf(entry, exitValue);
		synchronized (entry) {
			if (entry.exit != null) {
				return;
			}
			entry.exit = status;
		}
		entry.write(TerminalPresentation.formatExitFooter(status));
		Mirror mirror = entry.mirror;
		if (mirror != null) {
			mirror.exited(entry.id, status);
		}
		entry.exitFuture.complete(status);
	}

	/**
	 * Java reports a process ended by a signal as exit value 128 + signal, which
	 * a command may also return by itself. Only for a process this service
	 * signalled is it read as the signal, as the reference reports it.
	 */
	private static ExitStatus statusOf(Entry entry, int exitValue) {
		if (Platform.current() == Platform.POSIX && entry.killed.get()) {
			if (exitValue == 128 + SIGTERM) {
				return new ExitStatus(null, "SIGTERM");
			}
			if (exitValue == 128 + SIGKILL) {
				return new ExitStatus(null, "SIGKILL");
			}
		}
		return new ExitStatus(exitValue, null);
	}

	// ---------------------------------------------------------------------
	// Stopping
	// ---------------------------------------------------------------------

	private CompletableFuture<Void> kill(Entry entry) {
		if (entry.exit != null || entry.killed.get()) {
			return CompletableFuture.completedFuture(null);
		}
		try {
			return CompletableFuture.runAsync(() -> killNow(entry), killer);
		} catch (RejectedExecutionException e) {
			killNow(entry);
			return CompletableFuture.completedFuture(null);
		}
	}

	/**
	 * End the command's whole process tree: the shell and everything it started
	 * (a started service, not just the shell). The descendants are listed before
	 * anything is killed, because elsewhere than on Windows nothing links them to
	 * this command once their parent is gone.
	 */
	private void killNow(Entry entry) {
		Process process = entry.process;
		if (entry.exit != null || process == null || !entry.killed.compareAndSet(false, true)) {
			return;
		}
		boolean windows = Platform.current() == Platform.WIN32;
		Set<ProcessHandle> tree = new LinkedHashSet<>();
		tree.add(process.toHandle());
		collectDescendants(entry, tree, tree);
		// Windows has no polite request for a console process: as taskkill /T /F.
		tree.forEach(windows ? ProcessHandle::destroyForcibly : ProcessHandle::destroy);
		if (windows) {
			try {
				// What the shell was starting at that moment exists once the shell is gone.
				process.waitFor(WINDOWS_EXIT_WAIT_MS, TimeUnit.MILLISECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		// A process started while the tree was being listed is not in the list. On
		// Windows it stays linked to its dead parent; elsewhere it is found only as
		// long as the parent has not acted on the signal yet.
		for (int sweep = 0; sweep < KILL_SWEEPS; sweep++) {
			Set<ProcessHandle> late = new LinkedHashSet<>();
			collectDescendants(entry, tree, late);
			late.removeAll(tree);
			if (late.isEmpty()) {
				break;
			}
			late.forEach(windows ? ProcessHandle::destroyForcibly : ProcessHandle::destroy);
			tree.addAll(late);
		}
		if (!windows) {
			later(POSIX_KILL_GRACE_MS,
					() -> tree.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly));
		}
	}

	private void collectDescendants(Entry entry, Set<ProcessHandle> of, Set<ProcessHandle> into) {
		for (ProcessHandle handle : List.copyOf(of)) {
			try {
				handle.descendants().forEach(into::add);
			} catch (RuntimeException e) {
				logger.warn("terminal " + entry.id + ": could not list child processes: " + e.getMessage());
			}
		}
	}

	// ---------------------------------------------------------------------
	// Internals
	// ---------------------------------------------------------------------

	/**
	 * Whether {@code terminalId} may be looked up on behalf of {@code sessionId}:
	 * every placement (all modes) records the session that created the terminal,
	 * so a card of one session never shows another session's command output.
	 * Without a session id the check is skipped; unknown ids fail it. Under the lock.
	 */
	private boolean belongsTo(String terminalId, String sessionId) {
		if (sessionId == null) {
			return true;
		}
		Placement placement = placements.get(terminalId);
		return placement != null && placement.sessionId().equals(sessionId);
	}

	private Entry require(JsonNode request) {
		String terminalId = request != null && request.path("terminalId").isTextual()
				? request.get("terminalId").textValue()
				: null;
		Entry entry;
		synchronized (lock) {
			entry = terminalId != null ? active.get(terminalId) : null;
		}
		if (entry == null) {
			throw new IllegalArgumentException("Unknown terminal: " + terminalId);
		}
		return entry;
	}

	/** For tests: the shell process of an active terminal, or {@code null}. */
	Process processOf(String terminalId) {
		synchronized (lock) {
			Entry entry = active.get(terminalId);
			return entry != null ? entry.process : null;
		}
	}

	private long defaultLimit() {
		int configured = settings.terminalOutputByteLimit();
		return configured > 0 ? configured : DEFAULT_LIMIT;
	}

	private static IllegalStateException closedFailure() {
		return new IllegalStateException("The terminal service is closed");
	}

	private static List<String> strings(JsonNode array) {
		if (array == null || !array.isArray()) {
			return null;
		}
		List<String> out = new ArrayList<>();
		for (JsonNode item : array) {
			out.add(item.asText());
		}
		return out;
	}

	private static ObjectNode exitJson(ExitStatus status) {
		ObjectNode json = JSON.objectNode();
		// Both always present: null is how ACP says "none".
		json.put("exitCode", status.exitCode());
		json.put("signal", status.signal());
		return json;
	}

	private static TerminalSnapshot snapshotOf(Entry entry) {
		ExitStatus exit = entry.exit;
		return new TerminalSnapshot(ShellText.stripAnsi(entry.buffer.text()), exit != null,
				exit != null ? exit.exitCode() : null);
	}
}
