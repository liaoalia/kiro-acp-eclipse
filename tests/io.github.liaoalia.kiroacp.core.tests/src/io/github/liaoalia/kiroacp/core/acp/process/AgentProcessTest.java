package io.github.liaoalia.kiroacp.core.acp.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.liaoalia.kiroacp.core.acp.FakeAgent;
import io.github.liaoalia.kiroacp.core.host.Logger;

class AgentProcessTest {

	private static final long SECONDS = 30;
	private static final String INITIALIZE = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":1}}\n";

	@TempDir
	Path dir;

	private AgentProcess process;
	private final List<String> warnings = new CopyOnWriteArrayList<>();
	private final List<String> stderr = new CopyOnWriteArrayList<>();
	private final Logger logger = new Logger() {
		@Override
		public void info(String message) {
		}

		@Override
		public void warn(String message) {
			synchronized (warnings) {
				warnings.add(message);
				warnings.notifyAll();
			}
		}

		@Override
		public void error(String message, Throwable cause) {
		}

		@Override
		public boolean traces() {
			return true;
		}

		@Override
		public void trace(Direction direction, String payload) {
			stderr.add(direction + " " + payload);
		}
	};

	@AfterEach
	void end() throws Exception {
		if (process != null) {
			process.terminate().get(SECONDS, TimeUnit.SECONDS);
		}
	}

	private static String script() {
		return FakeAgent.fixtures().resolve("fake-agent").resolve("agent.mjs").toString();
	}

	private static String node() {
		return CliPathResolver.resolve("node", System.getenv());
	}

	private String firstLine() throws IOException {
		process.stdin().write(INITIALIZE.getBytes(StandardCharsets.UTF_8));
		process.stdin().flush();
		return new BufferedReader(new InputStreamReader(process.stdout(), StandardCharsets.UTF_8)).readLine();
	}

	@Test
	void theAgentRunsInTheGivenDirectoryWithPipedStdioAndIsTerminated() throws Exception {
		process = AgentProcess.start(node(), List.of(script(), "acp", "--scenario", "chat"), dir, System.getenv(), logger);

		assertTrue(firstLine().contains("fake-kiro"));
		assertTrue(process.isAlive());
		assertFalse(process.onExit().isDone());

		assertSame(process.onExit(), process.terminate());
		process.onExit().get(SECONDS, TimeUnit.SECONDS);
		assertFalse(process.isAlive());
		assertSame(process.onExit(), process.terminate(), "terminating twice is harmless");
	}

	@Test
	void theExitCodeIsReportedAndStderrGoesToTheLogLineByLine() throws Exception {
		process = AgentProcess.start(node(),
				List.of("-e", "process.stderr.write('first\\n\\nsecond \\u00e4\\n', () => process.exit(7))"), dir,
				System.getenv(), logger);

		assertEquals(7, process.onExit().get(SECONDS, TimeUnit.SECONDS));
		// The copying thread ends with the stream; its last line is the signal that all arrived.
		assertTrue(awaitWarning("[kiro-cli] second ä"), warnings.toString());
		assertEquals(List.of("[kiro-cli] first", "[kiro-cli] second ä"), warnings);
		assertEquals(List.of("STDERR first", "STDERR ", "STDERR second ä"), stderr);
	}

	@Test
	void theEnvironmentIsExactlyTheOneGiven() throws Exception {
		Map<String, String> env = new java.util.HashMap<>(System.getenv());
		env.put("KIRO_ACP_TEST_MARK", "from the test");
		process = AgentProcess.start(node(),
				List.of("-e", "console.log(process.env.KIRO_ACP_TEST_MARK + '|' + process.cwd())"), dir, env, logger);

		String line = new BufferedReader(new InputStreamReader(process.stdout(), StandardCharsets.UTF_8)).readLine();

		assertEquals("from the test", line.substring(0, line.indexOf('|')));
		assertTrue(Files.isSameFile(dir, Path.of(line.substring(line.indexOf('|') + 1))), line);
		assertEquals(0, process.onExit().get(SECONDS, TimeUnit.SECONDS));
	}

	@Test
	void aMissingExecutableFailsTheStart() {
		assertThrows(IOException.class, () -> AgentProcess.start("definitely-not-a-real-binary-xyz", List.of("acp"), dir,
				System.getenv(), logger));
	}

	@Test
	void aCmdFileRunsThroughTheShellWithItsArgumentsIntact() throws Exception {
		assumeTrue(Platform.current() == Platform.WINDOWS, "cmd.exe is Windows only");
		Path tools = Files.createDirectories(dir.resolve("my tools"));
		Path echo = Files.writeString(tools.resolve("echo args.cmd"),
				"@node -e \"console.log(JSON.stringify(process.argv.slice(1)))\" %*\r\n");
		process = AgentProcess.start(echo.toString(), List.of("acp", "two words", "--flag=a b", "say \"hi\"", "50%", "a&b"),
				dir, System.getenv(), logger);

		String line = new BufferedReader(new InputStreamReader(process.stdout(), StandardCharsets.UTF_8)).readLine();

		assertEquals("[\"acp\",\"two words\",\"--flag=a b\",\"say \\\"hi\\\"\",\"50%\",\"a&b\"]", line);
		assertEquals(0, process.onExit().get(SECONDS, TimeUnit.SECONDS));
	}

	@Test
	void aLastArgumentEndingInABackslashStillReachesTheBatchFile() throws Exception {
		assumeTrue(Platform.current() == Platform.WINDOWS, "cmd.exe is Windows only");
		Path echo = Files.writeString(dir.resolve("echo.cmd"), "@echo %1 %2\r\n");
		process = AgentProcess.start(echo.toString(), List.of("acp", "C:\\some dir\\"), dir, System.getenv(), logger);

		String line = new BufferedReader(new InputStreamReader(process.stdout(), StandardCharsets.UTF_8)).readLine();

		assertEquals("\"acp\" \"C:\\some dir\\\"", line.strip());
	}

	@Test
	void terminatingACmdWrapperEndsTheAgentBehindIt() throws Exception {
		assumeTrue(Platform.current() == Platform.WINDOWS, "cmd.exe is Windows only");
		Path wrapper = Files.writeString(dir.resolve("kiro-cli.cmd"), "@node \"" + script() + "\" %*\r\n");
		process = AgentProcess.start(wrapper.toString(), List.of("acp", "--scenario", "chat"), dir, System.getenv(), logger);
		// An answer proves the agent behind the shell is up, so it is among the descendants.
		assertTrue(firstLine().contains("fake-kiro"));
		List<ProcessHandle> behind = process.descendants();
		assertFalse(behind.isEmpty());

		process.terminate().get(SECONDS, TimeUnit.SECONDS);

		for (ProcessHandle handle : behind) {
			handle.onExit().get(SECONDS, TimeUnit.SECONDS);
			assertFalse(handle.isAlive());
		}
	}

	private boolean awaitWarning(String text) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SECONDS);
		synchronized (warnings) {
			while (!warnings.contains(text)) {
				long left = deadline - System.nanoTime();
				if (left <= 0) {
					return false;
				}
				TimeUnit.NANOSECONDS.timedWait(warnings, left);
			}
		}
		return true;
	}
}
