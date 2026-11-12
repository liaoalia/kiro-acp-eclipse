package io.github.liaoalia.kiroacp.core.acp.process;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** The {@code shellWrappedCommand} case of the reference's {@code test/process.test.ts}, and the quoting rules. */
class AgentCommandLineTest {

	private static final String SHELL = "C:\\Windows\\System32\\cmd.exe";
	private static final Map<String, String> ENV = Map.of("ComSpec", SHELL);

	@Test
	void cmdAndBatFilesGoThroughTheShellOnWindowsOnly() {
		AgentCommandLine cmd = AgentCommandLine.of("C:\\tools\\kiro-cli.cmd", List.of("acp", "--agent-engine=v3"),
				Platform.WINDOWS, ENV);
		assertEquals(new AgentCommandLine(SHELL,
				List.of("/d", "/s", "/c", "\"\"C:\\tools\\kiro-cli.cmd\" \"acp\" \"--agent-engine=v3\"\""), true), cmd);

		AgentCommandLine bat = AgentCommandLine.of("C:\\tools\\KIRO.BAT", List.of(), Platform.WINDOWS, ENV);
		assertEquals(List.of("/d", "/s", "/c", "\"\"C:\\tools\\KIRO.BAT\"\""), bat.args());

		assertEquals(new AgentCommandLine("C:\\Program Files\\Kiro-Cli\\kiro-cli.exe", List.of("acp"), false),
				AgentCommandLine.of("C:\\Program Files\\Kiro-Cli\\kiro-cli.exe", List.of("acp"), Platform.WINDOWS, ENV));
		assertEquals(new AgentCommandLine("/opt/wrap/kiro-cli.cmd", List.of("acp"), false),
				AgentCommandLine.of("/opt/wrap/kiro-cli.cmd", List.of("acp"), Platform.LINUX, ENV));
	}

	@Test
	void theShellComesFromComSpecInAnyCaseOrIsCmdExe() {
		assertEquals(SHELL, AgentCommandLine.of("k.cmd", List.of(), Platform.WINDOWS, Map.of("COMSPEC", SHELL)).command());
		assertEquals("cmd.exe", AgentCommandLine.of("k.cmd", List.of(), Platform.WINDOWS, Map.of()).command());
		assertEquals("cmd.exe", AgentCommandLine.of("k.cmd", List.of(), Platform.WINDOWS, Map.of("ComSpec", "")).command());
	}

	@Test
	void quotesInsideAnArgumentAreDoubled() {
		AgentCommandLine line = AgentCommandLine.of("k.cmd", List.of("say \"hi\""), Platform.WINDOWS, ENV);

		assertEquals("\"\"k.cmd\" \"say \"\"hi\"\"\"\"", line.args().get(3));
	}

	/** Java would otherwise take the argument for unquoted and wrap it in a second pair of quotes. */
	@Test
	void aLineEndingInABackslashIsKeptFromEndingInAnEscapedQuote() {
		AgentCommandLine line = AgentCommandLine.of("k.cmd", List.of("C:\\dir\\"), Platform.WINDOWS, ENV);

		assertEquals("\"\"k.cmd\" \"C:\\dir\\\" \"", line.args().get(3));
	}
}
