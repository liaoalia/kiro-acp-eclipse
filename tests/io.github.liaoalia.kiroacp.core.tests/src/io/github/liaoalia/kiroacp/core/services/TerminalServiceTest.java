package io.github.liaoalia.kiroacp.core.services;

import static io.github.liaoalia.kiroacp.core.services.Fakes.failure;
import static io.github.liaoalia.kiroacp.core.services.Fakes.get;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.liaoalia.kiroacp.core.host.Settings.TerminalMode;
import io.github.liaoalia.kiroacp.core.pure.output.TerminalPresentation;
import io.github.liaoalia.kiroacp.core.pure.text.Platform;
import io.github.liaoalia.kiroacp.core.services.Fakes.RecordingDisplay.RecordingConsole;
import io.github.liaoalia.kiroacp.core.services.TerminalService.TerminalSnapshot;

/** Runs real commands through the platform shell; every test ends with all of them gone. */
class TerminalServiceTest {

	private static final boolean WINDOWS = Platform.current() == Platform.WIN32;
	/**
	 * Runs far longer than any test waits, and is always killed. It is the last
	 * process its shell starts, and says so: on Windows by ping's own first
	 * line, elsewhere by replacing the shell that announced it. A test that
	 * kills only after {@link #awaitRunning} therefore never races a process
	 * about to be started.
	 */
	private static final String SLEEP = WINDOWS ? "ping -n 60 127.0.0.1" : "exec sleep 60";

	@TempDir
	Path dir;

	private final Fakes.FakeSettings settings = new Fakes.FakeSettings();
	private final Fakes.RecordingDisplay display = new Fakes.RecordingDisplay();
	private final Fakes.RecordingLogger logger = new Fakes.RecordingLogger();
	private final List<ProcessHandle> started = new CopyOnWriteArrayList<>();
	private TerminalService service;

	@BeforeEach
	void setUp() {
		service = new TerminalService(settings, new Fakes.FakeWorkspace(dir), display, logger);
	}

	@AfterEach
	void tearDown() throws Exception {
		// Whatever a test left running, with what it started in turn.
		List<ProcessHandle> all = new ArrayList<>(started);
		for (ProcessHandle root : started) {
			root.descendants().forEach(all::add);
		}
		service.close();
		Fakes.await(() -> all.stream().noneMatch(ProcessHandle::isAlive), "every process to end after close: " + all);
	}

	/** Prints the word, then keeps running. */
	private static String announceAndSleep(String word) {
		return "echo " + word + (WINDOWS ? "& " : "; ") + SLEEP;
	}

	private static ObjectNode command(String sessionId, String command, String... args) {
		ObjectNode request = Fakes.MAPPER.createObjectNode().put("sessionId", sessionId).put("command", command);
		if (args.length > 0) {
			ArrayNode array = request.putArray("args");
			for (String arg : args) {
				array.add(arg);
			}
		}
		return request;
	}

	private static ObjectNode id(String terminalId) {
		return Fakes.MAPPER.createObjectNode().put("sessionId", "s1").put("terminalId", terminalId);
	}

	private String create(ObjectNode request) throws Exception {
		JsonNode response = get(service.createTerminal(request));
		String terminalId = response.path("terminalId").asText();
		Process process = service.processOf(terminalId);
		if (process != null) {
			started.add(process.toHandle());
		}
		return terminalId;
	}

	private JsonNode waitForExit(String terminalId) throws Exception {
		return get(service.waitForTerminalExit(id(terminalId)));
	}

	private String outputOf(String terminalId) throws Exception {
		return get(service.terminalOutput(id(terminalId))).path("output").asText();
	}

	/** Waits for the word as a line of output; the header's echo of the command line does not count. */
	private void awaitLine(RecordingConsole console, String word) throws InterruptedException {
		console.awaitText(word + "\r\n");
	}

	/** Waits until the sleeper of {@link #announceAndSleep} runs: nothing more will be started. */
	private void awaitRunning(String terminalId) throws InterruptedException {
		Fakes.await(() -> {
			String output = service.snapshot(terminalId).output();
			return WINDOWS ? output.contains("127.0.0.1") : !output.isBlank();
		}, "the sleeper of " + terminalId);
	}

	private static String firstLine(String output) {
		return output.lines().findFirst().orElse("").strip();
	}

	// ---------------------------------------------------------------------
	// Running a command
	// ---------------------------------------------------------------------

	/** Reference F6.2. */
	@Test
	void runsACommandAndKeepsItsOutputAndExitCode() throws Exception {
		settings.mode = TerminalMode.HIDDEN;

		String terminalId = create(command("s1", "echo", "hello"));

		assertTrue(terminalId.matches("term-[a-z0-9]+-1"), terminalId);
		assertEquals(Fakes.json("{\"exitCode\":0,\"signal\":null}"), waitForExit(terminalId));
		JsonNode output = get(service.terminalOutput(id(terminalId)));
		assertEquals("hello", output.path("output").asText().strip());
		assertFalse(output.path("truncated").asBoolean(true));
		assertEquals(Fakes.json("{\"exitCode\":0,\"signal\":null}"), output.path("exitStatus"));
		assertEquals(List.of(terminalId), service.activeIds());
		assertEquals(new TerminalSnapshot(output.path("output").asText(), true, 0), service.snapshot(terminalId, "s1"));
		assertTrue(display.consoles().isEmpty(), "hidden mode shows nothing");
		assertFalse(service.showTerminal(terminalId));
		assertTrue(logger.has("terminal " + terminalId + " (HIDDEN): spawned \"echo hello\" in " + dir), logger.lines.toString());
	}

	@Test
	void reportsTheExitCodeOfAFailingCommand() throws Exception {
		String terminalId = create(command("s1", "exit 3"));

		assertEquals(Fakes.json("{\"exitCode\":3,\"signal\":null}"), waitForExit(terminalId));
		assertEquals(3, service.snapshot(terminalId).exitCode());
		assertEquals(3, get(service.terminalOutput(id(terminalId))).path("exitStatus").path("exitCode").asInt());
	}

	@Test
	void aCommandWithoutArgumentsIsALineForTheShell() throws Exception {
		String terminalId = create(command("s1", "echo one&& echo two"));
		waitForExit(terminalId);
		assertEquals(List.of("one", "two"), outputOf(terminalId).lines().map(String::strip).toList());
	}

	@Test
	void argumentsReachTheCommandAsGiven() throws Exception {
		String terminalId = create(command("s1", "echo", "a b", "c&d", "50%"));
		waitForExit(terminalId);
		String output = outputOf(terminalId);
		assertEquals(1, output.strip().lines().count(), output);
		assertTrue(output.contains("a b") && output.contains("c&d") && output.contains("50%"), output);
	}

	@Test
	void theRequestsDirectoryAndVariablesApply() throws Exception {
		Path sub = java.nio.file.Files.createDirectory(dir.resolve("sub dir"));
		ObjectNode request = command("s1", WINDOWS ? "echo %KIRO_TEST_VAR%& cd" : "echo $KIRO_TEST_VAR; pwd");
		request.put("cwd", sub.toString());
		request.set("env", Fakes.json("[{\"name\":\"KIRO_TEST_VAR\",\"value\":\"from the request\"}]"));

		String terminalId = create(request);
		waitForExit(terminalId);

		String output = outputOf(terminalId);
		assertTrue(output.contains("from the request"), output);
		assertTrue(output.strip().endsWith("sub dir"), output);
	}

	@Test
	void theWorkspacesDirectoryIsUsedWhenTheRequestNamesNone() throws Exception {
		String terminalId = create(command("s1", WINDOWS ? "cd" : "pwd"));
		waitForExit(terminalId);
		assertTrue(outputOf(terminalId).strip().endsWith(dir.getFileName().toString()), outputOf(terminalId));
	}

	@Test
	void whatTheCommandWritesToStderrIsOutputToo() throws Exception {
		String terminalId = create(command("s1", "echo oops 1>&2"));
		waitForExit(terminalId);
		assertEquals("oops", outputOf(terminalId).strip());
	}

	@Test
	void colourSequencesReachTheConsoleButNotTheAgent() throws Exception {
		String terminalId = create(command("s1", "echo \u001b[31mred\u001b[0m"));
		waitForExit(terminalId);

		assertEquals("red", outputOf(terminalId).strip());
		assertEquals("red", service.snapshot(terminalId).output().strip());
		assertTrue(display.only().text().contains("\u001b[31mred\u001b[0m"), display.only().text());
	}

	@Test
	void aCommandThatReadsItsInputFindsTheEnd() throws Exception {
		String terminalId = create(command("s1", WINDOWS ? "findstr x" : "cat"));
		JsonNode exit = waitForExit(terminalId);
		assertTrue(exit.path("exitCode").isInt(), exit.toString());
	}

	@Test
	void aRequestWithoutACommandIsRefused() throws Exception {
		assertEquals("terminal/create requires a command",
				failure(service.createTerminal(Fakes.MAPPER.createObjectNode().put("sessionId", "s1"))).getMessage());
		assertEquals("terminal/create requires a command",
				failure(service.createTerminal(command("s1", ""))).getMessage());
		assertEquals("terminal/create requires a command", failure(service.createTerminal(null)).getMessage());
		assertTrue(service.activeIds().isEmpty());
	}

	@Test
	void anUnknownTerminalIsRefused() throws Exception {
		String message = "Unknown terminal: term-nope-1";
		ObjectNode request = id("term-nope-1");
		assertEquals(message, failure(service.terminalOutput(request)).getMessage());
		assertEquals(message, failure(service.waitForTerminalExit(request)).getMessage());
		assertEquals(message, failure(service.killTerminal(request)).getMessage());
		assertEquals(message, failure(service.releaseTerminal(request)).getMessage());
		assertEquals("Unknown terminal: null", failure(service.terminalOutput(null)).getMessage());
		assertNull(service.snapshot("term-nope-1"));
		assertFalse(service.showTerminal("term-nope-1"));
	}

	@Test
	void aCommandThatCannotBeStartedIsATerminalThatEndedWithoutACode() throws Exception {
		ObjectNode request = command("s1", "echo", "never");
		request.put("cwd", dir.resolve("no such directory").toString());

		String terminalId = create(request);

		assertEquals(Fakes.json("{\"exitCode\":null,\"signal\":null}"), waitForExit(terminalId));
		String output = outputOf(terminalId);
		assertTrue(output.startsWith("\n[spawn error: "), output);
		assertEquals(new TerminalSnapshot(output, true, null), service.snapshot(terminalId));
		RecordingConsole console = display.only();
		assertTrue(console.text().contains("\r\n[spawn error: "), console.text());
		assertFalse(console.text().contains("[exit code"), "no footer for a command that never ran");
		assertEquals(Fakes.json("{}"), get(service.killTerminal(id(terminalId))));
		assertEquals(Fakes.json("{}"), get(service.releaseTerminal(id(terminalId))));
		assertTrue(logger.has("error: terminal " + terminalId));
	}

	// ---------------------------------------------------------------------
	// Output limit
	// ---------------------------------------------------------------------

	@Test
	void theRequestsByteLimitKeepsTheEndOfTheOutput() throws Exception {
		ObjectNode request = command("s1", "echo", "0123456789abcdefghij");
		request.put("outputByteLimit", 10);

		String terminalId = create(request);
		waitForExit(terminalId);

		JsonNode output = get(service.terminalOutput(id(terminalId)));
		String text = output.path("output").asText();
		assertTrue(output.path("truncated").asBoolean(), output.toString());
		assertTrue(text.getBytes(StandardCharsets.UTF_8).length <= 10, text);
		assertTrue(text.strip().endsWith("efghij"), text);
		assertFalse(text.contains("0"), text);
		assertEquals(text, service.snapshot(terminalId).output());
		// The console is a mirror, not the buffer: it got everything.
		assertTrue(display.only().text().contains("0123456789abcdefghij\r\n"), display.only().text());
	}

	@Test
	void theSettingIsTheLimitOfARequestWithoutOne() throws Exception {
		settings.outputByteLimit = 8;
		String limited = create(command("s1", "echo", "0123456789abcdefghij"));
		waitForExit(limited);
		JsonNode output = get(service.terminalOutput(id(limited)));
		assertTrue(output.path("truncated").asBoolean(), output.toString());
		assertTrue(output.path("output").asText().getBytes(StandardCharsets.UTF_8).length <= 8, output.toString());

		// A limit of zero in the request means none, as in the reference.
		ObjectNode unbounded = command("s1", "echo", "0123456789abcdefghij");
		unbounded.put("outputByteLimit", 0);
		String all = create(unbounded);
		waitForExit(all);
		assertEquals("0123456789abcdefghij", outputOf(all).strip());
		assertFalse(get(service.terminalOutput(id(all))).path("truncated").asBoolean(true));

		// A setting that makes no sense falls back to the megabyte.
		settings.outputByteLimit = 0;
		String byDefault = create(command("s1", "echo", "0123456789abcdefghij"));
		waitForExit(byDefault);
		assertEquals("0123456789abcdefghij", outputOf(byDefault).strip());
	}

	// ---------------------------------------------------------------------
	// Waiting, killing, releasing
	// ---------------------------------------------------------------------

	@Test
	void waitingBeforeAndAfterTheExitGivesTheSameAnswer() throws Exception {
		String terminalId = create(command("s1", announceAndSleep("started")));
		awaitLine(display.only(), "started");
		awaitRunning(terminalId);

		CompletableFuture<JsonNode> early = service.waitForTerminalExit(id(terminalId));
		JsonNode running = get(service.terminalOutput(id(terminalId)));
		assertFalse(early.isDone());
		assertFalse(running.has("exitStatus"), running.toString());
		assertEquals("started", firstLine(running.path("output").asText()));
		TerminalSnapshot snapshot = service.snapshot(terminalId);
		assertFalse(snapshot.exited());
		assertNull(snapshot.exitCode());
		assertEquals("started", firstLine(snapshot.output()));

		assertEquals(Fakes.json("{}"), get(service.killTerminal(id(terminalId))));

		JsonNode exit = get(early);
		if (WINDOWS) {
			assertTrue(exit.path("exitCode").isInt() && exit.path("exitCode").asInt() != 0, exit.toString());
			assertTrue(exit.path("signal").isNull(), exit.toString());
		} else {
			assertEquals(Fakes.json("{\"exitCode\":null,\"signal\":\"SIGTERM\"}"), exit);
		}
		CompletableFuture<JsonNode> late = service.waitForTerminalExit(id(terminalId));
		assertTrue(late.isDone());
		assertEquals(exit, get(late));
		assertEquals(exit, get(service.terminalOutput(id(terminalId))).path("exitStatus"));
	}

	@Test
	void aKilledTerminalStaysValid() throws Exception {
		String terminalId = create(command("s1", announceAndSleep("started")));
		awaitRunning(terminalId);

		get(service.killTerminal(id(terminalId)));
		get(service.killTerminal(id(terminalId)));
		waitForExit(terminalId);

		assertEquals(List.of(terminalId), service.activeIds());
		assertEquals("started", firstLine(outputOf(terminalId)));
		assertTrue(service.snapshot(terminalId).exited());
		get(service.killTerminal(id(terminalId)));
	}

	@Test
	void killingEndsEverythingTheCommandStarted() throws Exception {
		// The shell starts another shell, which starts the sleeper (elsewhere than on
		// Windows: a third shell that becomes it).
		String nested = WINDOWS ? "cmd /c \"" + SLEEP + "\"" : "sh -c 'sh -c \"echo started; " + SLEEP + "\"; true'; true";
		String terminalId = create(command("s1", nested));
		Process shell = service.processOf(terminalId);
		awaitRunning(terminalId);
		List<ProcessHandle> tree = new ArrayList<>(shell.descendants().toList());
		assertTrue(tree.size() >= 2, tree.toString());
		if (WINDOWS) {
			assertTrue(tree.stream().anyMatch(handle -> handle.info().command()
					.map(command -> command.toLowerCase(Locale.ROOT).contains("ping")).orElse(false)), tree.toString());
		}
		tree.add(shell.toHandle());
		started.addAll(tree);

		get(service.killTerminal(id(terminalId)));

		waitForExit(terminalId);
		Fakes.await(() -> tree.stream().noneMatch(ProcessHandle::isAlive), "the whole tree to end: " + tree);
	}

	@Test
	void aCommandHasEndedWhenItsShellHasEvenIfSomethingItStartedKeepsTheOutputOpen() throws Exception {
		settings.mode = TerminalMode.HIDDEN;
		TerminalService impatient = new TerminalService(settings, new Fakes.FakeWorkspace(dir), display, logger, 300);
		ProcessHandle survivor = null;
		try {
			// The shell returns at once; the sleeper it leaves behind holds its output.
			JsonNode created = get(impatient
					.createTerminal(command("s1", WINDOWS ? "start /b ping -n 60 127.0.0.1" : "sleep 60 & echo $!")));
			String terminalId = created.path("terminalId").asText();
			Process shell = impatient.processOf(terminalId);
			started.add(shell.toHandle());

			assertEquals(Fakes.json("{\"exitCode\":0,\"signal\":null}"), get(impatient.waitForTerminalExit(created)));

			if (WINDOWS) {
				// Windows still knows whose child it was.
				survivor = shell.descendants().filter(handle -> handle.info().command()
						.map(command -> command.toLowerCase(Locale.ROOT).contains("ping")).orElse(false)).findFirst().orElseThrow();
			} else {
				Fakes.await(() -> !impatient.snapshot(terminalId).output().isBlank(), "the sleeper's process id");
				survivor = ProcessHandle.of(Long.parseLong(impatient.snapshot(terminalId).output().strip())).orElseThrow();
			}
			assertTrue(survivor.isAlive(), "what the command left running is not the command");
			assertTrue(impatient.snapshot(terminalId).exited());
		} finally {
			if (survivor != null) {
				survivor.destroyForcibly();
				started.add(survivor);
			}
			impatient.close();
		}
	}

	/** Reference F6.1, the part after {@code terminal/release}. */
	@Test
	void releasingForgetsTheTerminalButKeepsWhatTheCardShows() throws Exception {
		String terminalId = create(command("s1", "echo", "hello"));
		waitForExit(terminalId);
		TerminalSnapshot before = service.snapshot(terminalId, "s1");

		assertEquals(Fakes.json("{}"), get(service.releaseTerminal(id(terminalId))));

		assertTrue(service.activeIds().isEmpty());
		assertEquals("Unknown terminal: " + terminalId, failure(service.terminalOutput(id(terminalId))).getMessage());
		assertEquals("Unknown terminal: " + terminalId, failure(service.releaseTerminal(id(terminalId))).getMessage());
		assertEquals(before, service.snapshot(terminalId, "s1"));
		assertEquals(before, service.snapshot(terminalId));
		assertEquals(0, before.exitCode());
		assertNull(service.snapshot(terminalId, "some-other-session"), "snapshot is refused for another session");
		assertFalse(service.showTerminal(terminalId, "some-other-session"));
		assertTrue(service.showTerminal(terminalId, "s1"), "showTerminal works after release");
		assertTrue(service.showTerminal(terminalId));
	}

	@Test
	void releasingARunningCommandKillsIt() throws Exception {
		String terminalId = create(command("s1", announceAndSleep("started")));
		Process shell = service.processOf(terminalId);
		awaitRunning(terminalId);
		CompletableFuture<JsonNode> exit = service.waitForTerminalExit(id(terminalId));

		get(service.releaseTerminal(id(terminalId)));

		assertTrue(service.activeIds().isEmpty());
		get(exit);
		Fakes.await(() -> !shell.isAlive(), "the released command to end");
		TerminalSnapshot snapshot = service.snapshot(terminalId, "s1");
		assertEquals("started", firstLine(snapshot.output()));
		// The footer still reaches the session's console; it is not output.
		display.only().awaitText(WINDOWS ? "[exit code " : "[terminated by SIGTERM]");
	}

	/** Reference D27: Stop ends the commands of the session. */
	@Test
	void killSessionStopsTheRunningCommandsOfThatSessionOnly() throws Exception {
		String done = create(command("s1", "echo", "done"));
		waitForExit(done);
		String first = create(command("s1", announceAndSleep("first")));
		String second = create(command("s1", announceAndSleep("second")));
		String other = create(command("s2", announceAndSleep("other")));
		CompletableFuture<JsonNode> otherExit = service.waitForTerminalExit(id(other));
		awaitRunning(first);
		awaitRunning(second);
		awaitRunning(other);

		assertEquals(List.of(first, second), get(service.killSession("s1")));

		waitForExit(first);
		waitForExit(second);
		assertFalse(otherExit.isDone());
		assertEquals(List.of(done, first, second, other), service.activeIds());
		assertEquals(List.of(), get(service.killSession("s1")));
		assertEquals(List.of(), get(service.killSession("nobody")));
		assertEquals(List.of(other), get(service.killSession("s2")));
		get(otherExit);
	}

	@Test
	void disposeAllKillsAndForgetsEverythingAndTheServiceGoesOn() throws Exception {
		String finished = create(command("s1", "echo", "done"));
		waitForExit(finished);
		get(service.releaseTerminal(id(finished)));
		String running = create(command("s1", announceAndSleep("started")));
		Process shell = service.processOf(running);
		RecordingConsole console = display.only();
		awaitRunning(running);
		CompletableFuture<JsonNode> exit = service.waitForTerminalExit(id(running));

		service.disposeAll();

		get(exit);
		Fakes.await(() -> !shell.isAlive(), "the command to end");
		assertTrue(service.activeIds().isEmpty());
		assertNull(service.snapshot(running));
		assertNull(service.snapshot(finished));
		assertFalse(service.showTerminal(running));
		assertEquals("close", console.events().get(console.events().size() - 1));

		String next = create(command("s1", "echo", "again"));
		waitForExit(next);
		assertEquals("again", outputOf(next).strip());
		assertEquals(2, display.consoles().size(), "a closed shared console is not used again");
	}

	/** The agent is gone but its sessions are still shown: everything ends, nothing is forgotten. */
	@Test
	void killAllStopsTheRunningCommandsOfEverySessionAndKeepsTheTerminals() throws Exception {
		String done = create(command("s1", "echo", "done"));
		waitForExit(done);
		String first = create(command("s1", announceAndSleep("first")));
		String other = create(command("s2", announceAndSleep("other")));
		ObjectNode anonymous = command("unused", announceAndSleep("anonymous"));
		anonymous.remove("sessionId");
		String nobodys = create(anonymous);
		List<Process> shells = List.of(service.processOf(first), service.processOf(other), service.processOf(nobodys));
		awaitRunning(first);
		awaitRunning(other);
		awaitRunning(nobodys);

		assertEquals(List.of(first, other, nobodys), get(service.killAll()));

		waitForExit(first);
		waitForExit(other);
		waitForExit(nobodys);
		Fakes.await(() -> shells.stream().noneMatch(Process::isAlive), "the commands to end");
		assertEquals(List.of(done, first, other, nobodys), service.activeIds(), "the terminals stay valid");
		assertTrue(service.snapshot(first, "s1").exited());
		assertTrue(service.snapshot(first, "s1").output().contains("first"), "and readable");
		assertTrue(service.snapshot(other, "s2").exited());
		assertTrue(service.showTerminal(first, "s1"), "the console is still there");
		assertEquals(List.of(), get(service.killAll()));

		String next = create(command("s1", "echo", "again"));
		waitForExit(next);
		assertEquals("again", outputOf(next).strip());
	}

	/** A command whose console is slow to open is not started once the agent that asked for it is gone. */
	@Test
	void aCommandAskedForBeforeEverythingWasEndedIsNotStarted() throws Exception {
		CountDownLatch opening = new CountDownLatch(1);
		CountDownLatch opened = new CountDownLatch(1);
		TerminalService slow = new TerminalService(settings, new Fakes.FakeWorkspace(dir), (target, closedByUser) -> {
			opening.countDown();
			try {
				opened.await(Fakes.SECONDS, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return null;
		}, logger);
		try {
			CompletableFuture<JsonNode> asked = slow.createTerminal(command("s1", announceAndSleep("never")));
			assertTrue(opening.await(Fakes.SECONDS, TimeUnit.SECONDS), "the console to be asked for");

			assertEquals(List.of(), get(slow.killAll()), "nothing runs yet");
			opened.countDown();

			assertEquals("The agent's terminals were ended before the command was started", failure(asked).getMessage());
			assertEquals(List.of(), slow.activeIds());
			// What is asked for afterwards runs.
			JsonNode next = get(slow.createTerminal(command("s1", "echo", "again")));
			get(slow.waitForTerminalExit(id(next.path("terminalId").asText())));
		} finally {
			opened.countDown();
			slow.close();
		}
	}

	/** A session was closed: its commands end and its terminals are forgotten, the others are untouched. */
	@Test
	void disposeSessionKillsAndForgetsTheTerminalsOfThatSessionOnly() throws Exception {
		String finished = create(command("s1", "echo", "done"));
		waitForExit(finished);
		get(service.releaseTerminal(id(finished)));
		String running = create(command("s1", announceAndSleep("started")));
		Process shell = service.processOf(running);
		String other = create(command("s2", announceAndSleep("other")));
		Process otherShell = service.processOf(other);
		awaitRunning(running);
		awaitRunning(other);
		RecordingConsole console = display.consoles().get(0);
		RecordingConsole otherConsole = display.consoles().get(1);
		assertEquals("s1", console.target.sessionId());
		CompletableFuture<JsonNode> exit = service.waitForTerminalExit(id(running));

		service.disposeSession("s1");

		get(exit);
		Fakes.await(() -> !shell.isAlive(), "the command to end");
		assertEquals(List.of(other), service.activeIds());
		assertNull(service.snapshot(running));
		assertNull(service.snapshot(finished), "what the session released is forgotten too");
		assertFalse(service.showTerminal(running));
		assertEquals("close", console.events().get(console.events().size() - 1));
		assertTrue(otherShell.isAlive(), "the other session's command runs on");
		assertFalse(service.snapshot(other, "s2").exited());
		assertFalse(otherConsole.events().contains("close"));
		assertTrue(service.showTerminal(other, "s2"));
		// The agent may still let go of what it held: that is refused, and breaks nothing.
		assertEquals("Unknown terminal: " + running, failure(service.releaseTerminal(id(running))).getMessage());

		service.disposeSession("s1");
		service.disposeSession("nobody");
		// A cancel is only a request: the agent may still ask for a command of the closed session.
		assertEquals("The session was closed before the command was started",
				failure(service.createTerminal(command("s1", "echo", "late"))).getMessage());
		assertEquals(2, display.consoles().size(), "and no console is opened for it");
		assertEquals(List.of(other), service.activeIds());

		// The same id again, as when a session is loaded once more.
		service.admitSession("s1");
		String next = create(command("s1", "echo", "again"));
		waitForExit(next);
		assertEquals("again", outputOf(next).strip());
		assertEquals(3, display.consoles().size(), "a closed shared console is not used again");
	}

	/** Ending everything also forgets which sessions were closed: the next agent may use any id. */
	@Test
	void afterEverythingWasEndedAClosedSessionsIdIsFree() throws Exception {
		service.disposeSession("s1");
		service.disposeAll();

		String next = create(command("s1", "echo", "again"));
		waitForExit(next);
		assertEquals("again", outputOf(next).strip());
	}

	/**
	 * A display whose consoles are the recording ones but take until
	 * {@code opened} to open, the way a busy UI thread makes them.
	 */
	private TerminalService slowToOpen(CountDownLatch opening, CountDownLatch opened) {
		return new TerminalService(settings, new Fakes.FakeWorkspace(dir), (target, closedByUser) -> {
			opening.countDown();
			try {
				opened.await(Fakes.SECONDS, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return display.open(target, closedByUser);
		}, logger);
	}

	/** The session is closed while the console of its command is still opening: nothing starts, nothing stays. */
	@Test
	void aCommandAskedForBeforeItsSessionWasClosedIsNotStartedAndLeavesNoConsole() throws Exception {
		for (TerminalMode mode : List.of(TerminalMode.SHARED, TerminalMode.PER_COMMAND, TerminalMode.HIDDEN)) {
			settings.mode = mode;
			int before = display.consoles().size();
			CountDownLatch opening = new CountDownLatch(1);
			CountDownLatch opened = new CountDownLatch(1);
			TerminalService slow = slowToOpen(opening, opened);
			try {
				CompletableFuture<JsonNode> asked = slow.createTerminal(command("s1", announceAndSleep("never")));
				if (mode != TerminalMode.HIDDEN) {
					assertTrue(opening.await(Fakes.SECONDS, TimeUnit.SECONDS), mode + ": the console to be asked for");
				}

				slow.disposeSession("s1");
				opened.countDown();

				assertEquals("The session was closed before the command was started", failure(asked).getMessage(), mode.toString());
				assertEquals(List.of(), slow.activeIds(), mode.toString());
				List<RecordingConsole> consoles = display.consoles().subList(before, display.consoles().size());
				for (RecordingConsole console : consoles) {
					assertEquals("close", console.events().get(console.events().size() - 1), mode + ": " + console.events());
				}
				assertEquals(mode == TerminalMode.HIDDEN ? 0 : 1, consoles.size(), mode.toString());

				// Another session is not held up, and gets a console of its own.
				JsonNode next = get(slow.createTerminal(command("s2", "echo", "again")));
				get(slow.waitForTerminalExit(id(next.path("terminalId").asText())));
				if (mode == TerminalMode.SHARED) {
					assertEquals("s2", display.consoles().get(display.consoles().size() - 1).target.sessionId());
				}
			} finally {
				opened.countDown();
				slow.close();
			}
		}
	}

	/** Everything is ended while a console is opening: the console that was opened for nothing is closed again. */
	@Test
	void aCommandRefusedAfterItsConsoleWasOpenedLeavesNoConsoleBehind() throws Exception {
		for (TerminalMode mode : List.of(TerminalMode.SHARED, TerminalMode.PER_COMMAND)) {
			settings.mode = mode;
			int before = display.consoles().size();
			CountDownLatch opening = new CountDownLatch(1);
			CountDownLatch opened = new CountDownLatch(1);
			TerminalService slow = slowToOpen(opening, opened);
			try {
				CompletableFuture<JsonNode> asked = slow.createTerminal(command("s1", announceAndSleep("never")));
				assertTrue(opening.await(Fakes.SECONDS, TimeUnit.SECONDS), mode + ": the console to be asked for");

				get(slow.killAll());
				opened.countDown();

				assertEquals("The agent's terminals were ended before the command was started", failure(asked).getMessage());
				RecordingConsole console = display.consoles().get(before);
				assertEquals("close", console.events().get(console.events().size() - 1), mode + ": " + console.events());

				// The session goes on (the agent is gone, its sessions are not): its next command
				// gets a console that works, not the closed one.
				JsonNode next = get(slow.createTerminal(command("s1", "echo", "again")));
				String nextId = next.path("terminalId").asText();
				get(slow.waitForTerminalExit(id(nextId)));
				assertEquals(before + 2, display.consoles().size(), mode.toString());
				assertTrue(slow.showTerminal(nextId, "s1"), mode.toString());
			} finally {
				opened.countDown();
				slow.close();
			}
		}
	}

	/** A shared console that was there before a refused command is the session's, not the command's: it stays. */
	@Test
	void aRefusedCommandDoesNotCloseAConsoleItDidNotOpen() throws Exception {
		String earlier = create(command("s1", "echo", "earlier"));
		waitForExit(earlier);
		RecordingConsole console = display.only();
		CountDownLatch hold = new CountDownLatch(1);
		CountDownLatch held = new CountDownLatch(1);
		// The starter thread is busy opening a console for another session.
		Fakes.RecordingDisplay inner = display;
		TerminalService slow = new TerminalService(settings, new Fakes.FakeWorkspace(dir), (target, closedByUser) -> {
			if (target.sessionId().equals("s2")) {
				held.countDown();
				try {
					hold.await(Fakes.SECONDS, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
			return inner.open(target, closedByUser);
		}, logger);
		try {
			JsonNode first = get(slow.createTerminal(command("s1", "echo", "one")));
			get(slow.waitForTerminalExit(id(first.path("terminalId").asText())));
			RecordingConsole sessions = display.consoles().get(1);
			CompletableFuture<JsonNode> blocker = slow.createTerminal(command("s2", "echo", "two"));
			assertTrue(held.await(Fakes.SECONDS, TimeUnit.SECONDS));
			CompletableFuture<JsonNode> queued = slow.createTerminal(command("s1", "echo", "three"));

			get(slow.killAll());
			hold.countDown();

			failure(blocker);
			failure(queued);
			assertFalse(sessions.events().contains("close"), "the session's console was not the refused command's: " + sessions.events());
			assertTrue(slow.showTerminal(first.path("terminalId").asText(), "s1"));
		} finally {
			hold.countDown();
			slow.close();
		}
		assertFalse(console.events().contains("close"), "nor was the other service's console touched");
	}

	/** A per-command console outlives its session, and is told how the command ended so the display can count it as finished. */
	@Test
	void theConsoleOfACommandEndedWithItsSessionStillHearsOfTheEnd() throws Exception {
		settings.mode = TerminalMode.PER_COMMAND;
		String running = create(command("s1", announceAndSleep("started")));
		String other = create(command("s2", announceAndSleep("other")));
		awaitRunning(running);
		awaitRunning(other);
		RecordingConsole console = display.consoles().get(0);
		RecordingConsole otherConsole = display.consoles().get(1);

		service.disposeSession("s1");
		display.await(() -> console.events().stream().anyMatch(event -> event.startsWith("exited " + running + " ")),
				"the console to hear of the end");
		assertFalse(console.events().contains("close"), "a per-command console stays open");

		service.disposeAll();
		display.await(() -> otherConsole.events().stream().anyMatch(event -> event.startsWith("exited " + other + " ")),
				"the other console to hear of the end");
		assertFalse(otherConsole.events().contains("close"));
	}

	/** Ending a session or everything does not wait for the processes: the caller may be the UI thread. */
	@Test
	void disposingReturnsBeforeTheProcessesAreEnded() throws Exception {
		CountDownLatch release = new CountDownLatch(1);
		String running = create(command("s1", announceAndSleep("started")));
		awaitRunning(running);
		Process shell = service.processOf(running);
		// Keep both of the killer's ways busy is not possible from outside; what can be
		// seen is the thread: the kill must not happen on the caller's.
		Thread caller = Thread.currentThread();
		List<Thread> killers = new CopyOnWriteArrayList<>();
		shell.onExit().thenRun(release::countDown);
		service.killObserver = () -> killers.add(Thread.currentThread());

		service.disposeSession("s1");

		assertTrue(release.await(Fakes.SECONDS, TimeUnit.SECONDS), "the command to end");
		Fakes.await(() -> !killers.isEmpty(), "the kill to be seen");
		assertFalse(killers.contains(caller), "killed on the caller's thread");

		String again = create(command("s2", announceAndSleep("again")));
		awaitRunning(again);
		killers.clear();
		service.disposeAll();
		Fakes.await(() -> !killers.isEmpty(), "the second kill to be seen");
		assertFalse(killers.contains(caller), "killed on the caller's thread");
	}

	@Test
	void afterCloseNothingCanBeCreated() throws Exception {
		service.close();
		assertEquals("The terminal service is closed", failure(service.createTerminal(command("s1", "echo", "x"))).getMessage());
	}

	// ---------------------------------------------------------------------
	// Presentation
	// ---------------------------------------------------------------------

	/** Reference F6.1. */
	@Test
	void theCommandsOfASessionShareOneConsole() throws Exception {
		String first = create(command("s1", "echo", "hello"));
		waitForExit(first);

		RecordingConsole console = display.only();
		assertEquals(new TerminalDisplay.Target(TerminalMode.SHARED, "s1", null, TerminalPresentation.sharedTerminalName("s1")),
				console.target);
		String cwd = dir.toString();
		assertEquals(TerminalPresentation.formatCommandHeader("echo hello", cwd) + "hello\r\n"
				+ TerminalPresentation.formatExitFooter(new TerminalService.ExitStatus(0, null)), console.text());
		assertEquals(List.of("reveal false", "exited " + first + " 0 null"), console.events());

		get(service.releaseTerminal(id(first)));
		String second = create(command("s1", "echo", "again"));
		waitForExit(second);

		assertNotEquals(first, second);
		assertEquals(1, display.consoles().size(), "still one console after a second command");
		assertTrue(console.text().endsWith(TerminalPresentation.formatCommandHeader("echo again", cwd) + "again\r\n"
				+ TerminalPresentation.formatExitFooter(new TerminalService.ExitStatus(0, null))), console.text());
		assertEquals(List.of("reveal false", "exited " + first + " 0 null", "exited " + second + " 0 null"),
				console.events(), "later commands never re-show the console");

		assertTrue(service.showTerminal(first, "s1"));
		assertTrue(service.showTerminal(second));
		assertEquals(List.of("reveal true", "reveal true"), console.events().subList(3, 5));

		String elsewhere = create(command("s2", "echo", "other"));
		waitForExit(elsewhere);
		assertEquals(2, display.consoles().size());
		assertEquals(TerminalPresentation.sharedTerminalName("s2"), display.consoles().get(1).target.name());
		assertFalse(display.consoles().get(1).text().contains("hello"));
	}

	@Test
	void aRequestWithoutASessionUsesTheDefaultOne() throws Exception {
		String terminalId = create(Fakes.MAPPER.createObjectNode().put("command", "echo hi"));
		waitForExit(terminalId);
		assertEquals("default", display.only().target.sessionId());
		assertEquals("hi", service.snapshot(terminalId, "default").output().strip());
	}

	@Test
	void commandsRunningAtTheSameTimeKeepTheirOwnOutput() throws Exception {
		String first = create(command("s1", announceAndSleep("first")));
		String second = create(command("s1", announceAndSleep("second")));
		RecordingConsole console = display.only();
		awaitLine(console, "first");
		// The command started while another runs has its lines marked in the shared console.
		awaitLine(console, "[" + second + "] second");
		awaitRunning(first);
		awaitRunning(second);

		assertEquals("first", firstLine(outputOf(first)));
		assertEquals("second", firstLine(outputOf(second)));
		assertFalse(outputOf(first).contains("second") || outputOf(second).contains("first"));
		assertFalse(console.text().contains("[" + first + "] "), console.text());
		assertEquals(List.of("reveal false"), console.events());

		get(service.killTerminal(id(first)));
		waitForExit(first);
		assertFalse(service.waitForTerminalExit(id(second)).isDone());
		assertFalse(service.snapshot(second).exited());
		get(service.killTerminal(id(second)));
		waitForExit(second);
	}

	@Test
	void manyCommandsAtOnceEachGetTheirOwnTerminal() throws Exception {
		settings.mode = TerminalMode.HIDDEN;
		List<CompletableFuture<JsonNode>> created = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			created.add(service.createTerminal(command("s" + (i % 2), "echo", "number-" + i)));
		}
		List<String> ids = new ArrayList<>();
		for (CompletableFuture<JsonNode> future : created) {
			ids.add(get(future).path("terminalId").asText());
		}
		assertEquals(8, ids.stream().distinct().count());
		assertEquals(ids, service.activeIds());
		for (int i = 0; i < 8; i++) {
			assertEquals(0, waitForExit(ids.get(i)).path("exitCode").asInt());
			assertEquals("number-" + i, outputOf(ids.get(i)).strip());
			assertNull(service.snapshot(ids.get(i), "s" + ((i + 1) % 2)));
		}
	}

	@Test
	void closingTheSharedConsoleStopsItsCommandsAndTheNextCommandOpensANewOne() throws Exception {
		String running = create(command("s1", announceAndSleep("started")));
		String other = create(command("s2", announceAndSleep("other")));
		RecordingConsole console = display.consoles().get(0);
		awaitLine(console, "started");
		awaitRunning(running);
		awaitRunning(other);

		console.closedByUser.run();

		waitForExit(running);
		assertFalse(service.snapshot(other).exited(), "another session's command goes on");
		String shown = console.text();
		assertFalse(shown.contains("[exit code") || shown.contains("[terminated"),
				"nothing is written to a console the user closed: " + shown);
		assertFalse(service.showTerminal(running), "there is no console to show");
		assertEquals(List.of(running, other), service.activeIds());

		String next = create(command("s1", "echo", "again"));
		waitForExit(next);
		assertEquals(3, display.consoles().size());
		assertTrue(display.consoles().get(2).text().contains("again\r\n"));
		assertEquals(shown, console.text());
		assertTrue(service.showTerminal(running), "the session's console is the new one");
		assertTrue(display.consoles().get(2).events().contains("reveal true"));
		// A second notice about the old console changes nothing.
		console.closedByUser.run();
		assertTrue(service.showTerminal(next));
	}

	@Test
	void perCommandModeGivesEveryCommandAConsoleOfItsOwn() throws Exception {
		settings.mode = TerminalMode.PER_COMMAND;
		assertEquals(TerminalMode.PER_COMMAND, service.currentMode());

		String first = create(command("s1", "echo", "hello"));
		waitForExit(first);
		String second = create(command("s1", announceAndSleep("started")));

		assertEquals(2, display.consoles().size());
		RecordingConsole one = display.consoles().get(0);
		RecordingConsole two = display.consoles().get(1);
		assertEquals(new TerminalDisplay.Target(TerminalMode.PER_COMMAND, "s1", first, TerminalPresentation.commandTerminalName("echo")),
				one.target);
		assertEquals(second, two.target.terminalId());
		assertEquals(List.of("reveal false", "exited " + first + " 0 null"), one.events());
		assertTrue(one.text().contains("hello\r\n") && one.text().contains("[exit code 0]"), one.text());
		awaitLine(two, "started");
		awaitRunning(second);
		assertFalse(two.text().contains("[" + second + "]"), "no line marks in a console of its own");

		get(service.releaseTerminal(id(first)));
		assertTrue(service.showTerminal(first, "s1"), "the console stays after release");
		assertFalse(service.showTerminal(first, "s2"));
		assertEquals("reveal true", one.events().get(2));

		// The user closes the console of the running command: it is stopped.
		two.closedByUser.run();
		waitForExit(second);
		assertEquals(List.of(second), service.activeIds());
	}

	@Test
	void theModeIsReadForEveryCommand() throws Exception {
		settings.mode = TerminalMode.HIDDEN;
		String hidden = create(command("s1", "echo", "one"));
		settings.mode = TerminalMode.SHARED;
		String shared = create(command("s1", "echo", "two"));
		settings.mode = null;
		String byDefault = create(command("s1", "echo", "three"));
		waitForExit(hidden);
		waitForExit(shared);
		waitForExit(byDefault);

		RecordingConsole console = display.only();
		assertFalse(console.text().contains("one\r\n"));
		assertTrue(console.text().contains("two\r\n") && console.text().contains("three\r\n"), console.text());
		assertFalse(service.showTerminal(hidden));
		assertTrue(service.showTerminal(shared));
	}

	@Test
	void withoutADisplayCommandsRunAsInHiddenMode() throws Exception {
		display.unavailable = true;
		String shared = create(command("s1", "echo", "one"));
		settings.mode = TerminalMode.PER_COMMAND;
		String own = create(command("s1", "echo", "two"));
		waitForExit(shared);
		waitForExit(own);

		assertEquals("one", outputOf(shared).strip());
		assertEquals("two", outputOf(own).strip());
		assertFalse(service.showTerminal(shared));
		assertFalse(service.showTerminal(own));

		TerminalService bare = new TerminalService(settings, new Fakes.FakeWorkspace(dir), null, null);
		try {
			JsonNode response = get(bare.createTerminal(command("s1", "echo", "three")));
			assertEquals(0, get(bare.waitForTerminalExit(response)).path("exitCode").asInt());
		} finally {
			bare.close();
		}
	}

	// ---------------------------------------------------------------------
	// The shell's command line
	// ---------------------------------------------------------------------

	@Test
	void theShellIsStartedAsTheReferenceStartsIt() {
		assertEquals(List.of("/bin/sh", "-c", "echo 'a b' && ls"),
				TerminalService.shellArgv("echo 'a b' && ls", Platform.POSIX, Map.of()));
		assertEquals(List.of("C:\\Windows\\system32\\cmd.exe", "/d", "/s", "/c", "\"echo \"a b\" && dir\""),
				TerminalService.shellArgv("echo \"a b\" && dir", Platform.WIN32, Map.of("COMSPEC", "C:\\Windows\\system32\\cmd.exe")));
		assertEquals(List.of("cmd.exe", "/d", "/s", "/c", "\"dir C:\\ \""),
				TerminalService.shellArgv("dir C:\\", Platform.WIN32, Map.of("ComSpec", "")));
	}
}
