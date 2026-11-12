package io.github.liaoalia.kiroacp.ui.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.liaoalia.kiroacp.core.acp.process.AgentCommandLine;
import io.github.liaoalia.kiroacp.core.acp.process.Platform;
import io.github.liaoalia.kiroacp.core.services.ExternalActions.SignIn;

/**
 * The scripts and command lines of a sign-in, as text, and run for real where
 * the machine has the shell for them. A run is the script alone, with its
 * input closed: the terminal window around it is never started.
 */
class SignInTerminalTest {

	private static final Map<String, String> WINDOWS_ENV = Map.of("ComSpec", "C:\\Windows\\system32\\cmd.exe");

	// ---------------------------------------------------------------------
	// Windows
	// ---------------------------------------------------------------------

	@Test
	void onWindowsANewConsoleWindowRunsTheScript() {
		Path script = Path.of("C:\\Users\\Jo Doe\\AppData\\Local\\Temp\\kiro-signin1\\signin.cmd");

		assertEquals(List.of("C:\\Windows\\system32\\cmd.exe", "/d", "/s", "/c", "\"start \"Kiro login\" \"" + script + "\"\""),
				SignInTerminal.windowsCommand("Kiro login", script, WINDOWS_ENV));
		// Windows does not care how the variable is spelled; without it the shell is found on the PATH.
		assertEquals("c:\\other\\cmd.exe",
				SignInTerminal.windowsCommand("Kiro login", script, Map.of("COMSPEC", "c:\\other\\cmd.exe")).get(0));
		assertEquals("cmd.exe", SignInTerminal.windowsCommand("Kiro login", script, Map.of()).get(0));
	}

	@Test
	void theWindowTitleCannotBreakTheCommandLine() {
		Path script = Path.of("signin.cmd");

		String line = SignInTerminal.windowsCommand("Kiro \"login\" & calc | %PATH%", script, WINDOWS_ENV).get(4);

		assertEquals("\"start \"Kiro login  calc  PATH\" \"signin.cmd\"\"", line);
		// start takes its first quoted word for the title, so there always is one.
		assertEquals("\"start \"Kiro\" \"signin.cmd\"\"", SignInTerminal.windowsCommand("", script, WINDOWS_ENV).get(4));
	}

	@Test
	void aWindowsProgramGetsItsArgumentsEscapedForCmdAndForItself() {
		assertEquals("\"C:\\Program Files\\Kiro\\kiro-cli.exe\" login --use-device-flow",
				SignInTerminal.windowsCommandLine(windows("C:\\Program Files\\Kiro\\kiro-cli.exe", "login", "--use-device-flow")));
		assertEquals("\"kiro-cli\" ^\"two words^\" ^\"a^&b^|c^\" ^\"^\"",
				SignInTerminal.windowsCommandLine(windows("kiro-cli", "two words", "a&b|c", "")));
		assertEquals("\"kiro-cli\" ^\"say \\^\"hi\\^\" ^& bye^\" ^\"100%%^\" ^\"^(x^) ^^ ^<y^> ^!z^!^\"",
				SignInTerminal.windowsCommandLine(windows("kiro-cli", "say \"hi\" & bye", "100%", "(x) ^ <y> !z!")));
		assertEquals("\"C:\\100%% sure\\kiro-cli.exe\" c:\\dir\\ ^\"c:\\my dir\\\\^\"",
				SignInTerminal.windowsCommandLine(windows("C:\\100% sure\\kiro-cli.exe", "c:\\dir\\", "c:\\my dir\\")));
	}

	@Test
	void aWindowsBatchLauncherIsCalledSoThatTheScriptGoesOn() {
		SignIn signIn = windows("C:\\Users\\Jo Doe\\AppData\\Roaming\\npm\\kiro-cli.cmd", "login", "two words", "say \"hi\"",
				"100%");
		assertTrue(signIn.launch().viaShell(), "the launch says it is a batch file");

		assertEquals("call \"C:\\Users\\Jo Doe\\AppData\\Roaming\\npm\\kiro-cli.cmd\" \"login\" \"two words\" \"say \"\"hi\"\"\" \"100%%%%\"",
				SignInTerminal.windowsCommandLine(signIn));
		assertTrue(SignInTerminal.windowsCommandLine(windows("C:\\tools\\KIRO.BAT", "login")).startsWith("call \"C:\\tools\\KIRO.BAT\""));
	}

	@Test
	void theWindowsScriptRunsTheCommandAndLeavesItsExitCode() {
		Path status = Path.of("C:\\Temp\\50% off\\exit-code");

		String script = SignInTerminal.windowsScript(windows("kiro-cli", "login"), status);

		assertEquals(String.join("\r\n", "@echo off", "chcp 65001 >nul", "title Kiro login", "\"kiro-cli\" login",
				"set KIRO_SIGNIN_EXIT=%errorlevel%",
				"(echo %KIRO_SIGNIN_EXIT%)>\"C:\\Temp\\50%% off\\exit-code.tmp\""
						+ "& move /y \"C:\\Temp\\50%% off\\exit-code.tmp\" \"C:\\Temp\\50%% off\\exit-code\" >nul"
						+ "& (if not \"%KIRO_SIGNIN_EXIT%\"==\"0\" pause)& exit %KIRO_SIGNIN_EXIT%",
				""), script);
	}

	@Test
	void theWindowsScriptReallyRunsABatchLauncher(@TempDir Path temp) throws Exception {
		assumeTrue(Platform.current() == Platform.WINDOWS);
		Path cli = Files.createDirectories(temp.resolve("dir with space & more")).resolve("my cli.cmd");
		Files.writeString(cli, String.join("\r\n", "@echo off", "> \"%KIRO_OUT%\" echo [%1] [%2] [%3] [%4] env=%KIRO_X%",
				"exit /b %KIRO_CODE%", ""));
		List<String> args = List.of("login", "two words", "a&b|c \"quoted\" (x) <y> 100% %KIRO_X%", "é ü");

		for (int code : new int[] { 0, 3 }) {
			Run run = runWindows(temp, windows(cli.toString(), args.toArray(String[]::new)), code);

			assertEquals(code, run.exit, "the script ends with the command's exit code");
			assertEquals(Integer.valueOf(code), run.status, "and leaves it in the status file");
			// A batch file sees its arguments as written, quotes and all.
			assertEquals("[\"login\"] [\"two words\"] [\"a&b|c \"\"quoted\"\" (x) <y> 100% %KIRO_X%\"] [\"é ü\"] env=from the env",
					run.received);
		}
	}

	@Test
	void theWindowsScriptReallyRunsAProgram(@TempDir Path temp) throws Exception {
		assumeTrue(Platform.current() == Platform.WINDOWS);
		Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
		assumeTrue(Files.isRegularFile(java));
		Path echo = Files.createDirectories(temp.resolve("dir with space")).resolve("Echo.java");
		Files.writeString(echo, """
				import java.nio.charset.StandardCharsets;
				import java.nio.file.Files;
				import java.nio.file.Path;

				public class Echo {
					public static void main(String[] args) throws Exception {
						StringBuilder out = new StringBuilder();
						for (String arg : args) {
							out.append('[').append(arg).append("] ");
						}
						out.append("env=").append(System.getenv("KIRO_X"));
						Files.writeString(Path.of(System.getenv("KIRO_OUT")), out.toString(), StandardCharsets.UTF_8);
						System.exit(Integer.parseInt(System.getenv("KIRO_CODE")));
					}
				}
				""");
		List<String> args = List.of("say \"hi\" & bye", "", "tail\\", "c:\\my dir\\", "back\\\"slash", "(x) ^ <y> !z! 100% %KIRO_X%",
				"é ü");
		List<String> all = new ArrayList<>(List.of(echo.toString()));
		all.addAll(args);

		for (int code : new int[] { 0, 3 }) {
			Run run = runWindows(temp, windows(java.toString(), all.toArray(String[]::new)), code);

			assertEquals(code, run.exit);
			assertEquals(Integer.valueOf(code), run.status);
			assertEquals("[say \"hi\" & bye] [] [tail\\] [c:\\my dir\\] [back\\\"slash] [(x) ^ <y> !z! 100% %KIRO_X%] [é ü] env=from the env",
					run.received, "every argument arrives as it was given");
		}
	}

	// ---------------------------------------------------------------------
	// macOS and Linux
	// ---------------------------------------------------------------------

	@Test
	void theShellScriptSetsTheEnvironmentRunsTheCommandAndLeavesItsExitCode() {
		Map<String, String> env = new LinkedHashMap<>();
		env.put("ZED", "last");
		env.put("KIRO_HOME", "/opt/it's here");
		env.put("not a name; rm -rf /", "skipped");
		SignIn signIn = new SignIn("Kiro login", "/opt/kiro cli/kiro-cli", List.of("login", "two words", "it's", "$HOME", ""),
				new AgentCommandLine("/opt/kiro cli/kiro-cli", List.of("login"), false), env);

		String script = SignInTerminal.posixScript(signIn, Path.of("/tmp/kiro-signin1/exit-code"), true);

		String status = Path.of("/tmp/kiro-signin1/exit-code").toString();
		assertEquals(String.join("\n", "#!/bin/sh", "printf '\\033]0;%s\\007' 'Kiro login'",
				"export KIRO_HOME='/opt/it'\\''s here'", "export ZED='last'",
				"'/opt/kiro cli/kiro-cli' 'login' 'two words' 'it'\\''s' '$HOME' ''", "code=$?",
				"printf '%s\\n' \"$code\" > '" + status + ".tmp' && mv -f '" + status + ".tmp' '" + status + "'",
				"if [ \"$code\" -ne 0 ]; then", "\tprintf '\\n[exit code %s] Press Enter to close this window. ' \"$code\"",
				"\tread -r _", "fi", "exit \"$code\"", ""), script);
	}

	@Test
	void aTerminalThatStaysOpenNeedsNoHolding() {
		String script = SignInTerminal.posixScript(posix("kiro-cli", Map.of(), "login"), Path.of("exit-code"), false);

		assertFalse(script.contains("read -r"), script);
		assertTrue(script.endsWith("exit \"$code\"\n"), script);
	}

	@Test
	void theShellScriptReallyRuns(@TempDir Path temp) throws Exception {
		Path shell = shell();
		assumeTrue(shell != null, "no sh on this machine");
		Path out = temp.resolve("out.txt");
		Path status = Files.createDirectories(temp.resolve("it's a dir")).resolve(SignInTerminal.STATUS_FILE);

		for (int code : new int[] { 0, 3 }) {
			Files.deleteIfExists(out);
			Files.deleteIfExists(status);
			Map<String, String> env = Map.of("KIRO_X", "from 'the' $env", "KIRO_OUT", out.toString(), "KIRO_CODE",
					Integer.toString(code));
			// The sign-in command is the shell itself, told to report what it was given.
			SignIn signIn = posix("sh", env, "-c", "printf '%s|%s|%s|%s' \"$KIRO_X\" \"$1\" \"$2\" \"$3\" > \"$KIRO_OUT\"; exit \"$KIRO_CODE\"",
					"arg0", "two words", "it's \"quoted\" $HOME `id`", "");
			Path script = temp.resolve("signin.sh");
			Files.writeString(script, SignInTerminal.posixScript(signIn, status, true), StandardCharsets.UTF_8);

			int exit = run(List.of(shell.toString(), script.toString()), Map.of());

			assertEquals(code, exit);
			assertEquals(Integer.valueOf(code), SignInTerminal.readStatus(status));
			assertEquals("from 'the' $env|two words|it's \"quoted\" $HOME `id`|", Files.readString(out));
		}
	}

	@Test
	void onMacTheTerminalOpensTheScript() {
		Path script = Path.of("/var/folders/x y/kiro-signin1/signin.command");

		assertEquals(List.of("open", "-a", "Terminal", script.toString()), SignInTerminal.macCommand(script));
	}

	@Test
	void onLinuxTheFirstTerminalFoundIsUsed() {
		Path script = Path.of("/tmp/kiro-signin1/signin.sh");

		assertEquals(List.of("x-terminal-emulator", "-e"), SignInTerminal.linuxTerminal(program -> true));
		assertEquals(List.of("gnome-terminal", "--", script.toString()), SignInTerminal
				.linuxCommand(SignInTerminal.linuxTerminal(Set.of("xterm", "gnome-terminal", "konsole")::contains), script));
		assertEquals(List.of("konsole", "-e", script.toString()),
				SignInTerminal.linuxCommand(SignInTerminal.linuxTerminal(Set.of("xterm", "konsole")::contains), script));
		assertEquals(List.of("xfce4-terminal", "-x"), SignInTerminal.linuxTerminal(Set.of("xfce4-terminal")::contains));
		assertEquals(List.of("xterm", "-e"), SignInTerminal.linuxTerminal(Set.of("xterm")::contains));
		assertNull(SignInTerminal.linuxTerminal(program -> false));
	}

	// ---------------------------------------------------------------------
	// The files
	// ---------------------------------------------------------------------

	@Test
	void prepareWritesTheScriptOfThePlatformAndNamesItToTheTerminal() throws Exception {
		SignIn signIn = posix("kiro-cli", Map.of("KIRO_X", "1"), "login");
		List<SignInTerminal.Plan> plans = new ArrayList<>();
		try {
			SignInTerminal.Plan windows = SignInTerminal.prepare(Platform.WINDOWS, windows("kiro-cli", "login"), WINDOWS_ENV,
					program -> false);
			plans.add(windows);
			assertEquals("signin.cmd", windows.script().getFileName().toString());
			assertEquals(SignInTerminal.windowsScript(windows("kiro-cli", "login"), windows.status()), Files.readString(windows.script()));
			assertEquals(SignInTerminal.windowsCommand("Kiro login", windows.script(), WINDOWS_ENV), windows.argv());

			SignInTerminal.Plan mac = SignInTerminal.prepare(Platform.MAC, signIn, Map.of(), program -> false);
			plans.add(mac);
			assertEquals("signin.command", mac.script().getFileName().toString());
			assertEquals(SignInTerminal.posixScript(signIn, mac.status(), false), Files.readString(mac.script()));
			assertEquals(List.of("open", "-a", "Terminal", mac.script().toString()), mac.argv());

			SignInTerminal.Plan linux = SignInTerminal.prepare(Platform.LINUX, signIn, Map.of(), "xterm"::equals);
			plans.add(linux);
			assertEquals(SignInTerminal.posixScript(signIn, linux.status(), true), Files.readString(linux.script()));
			assertEquals(List.of("xterm", "-e", linux.script().toString()), linux.argv());

			for (SignInTerminal.Plan plan : plans) {
				assertEquals(plan.directory(), plan.script().getParent());
				assertEquals(plan.directory().resolve(SignInTerminal.STATUS_FILE), plan.status());
				assertNull(SignInTerminal.readStatus(plan.status()), "no exit code before the command ran");
			}
			assertEquals(3, plans.stream().map(SignInTerminal.Plan::directory).distinct().count(), "a directory per sign-in");
		} finally {
			plans.forEach(SignInTerminal::discard);
		}
		for (SignInTerminal.Plan plan : plans) {
			assertFalse(Files.exists(plan.directory()), "discarded");
		}
	}

	@Test
	void aLinuxDesktopWithoutATerminalProgramIsAnErrorAndLeavesNothingBehind() {
		IOException failure = assertThrows(IOException.class,
				() -> SignInTerminal.prepare(Platform.LINUX, posix("kiro-cli", Map.of(), "login"), Map.of(), program -> false));

		assertTrue(failure.getMessage().contains("x-terminal-emulator"), failure.getMessage());
	}

	@Test
	void theStatusFileIsReadOnlyWhenItHoldsAnExitCode(@TempDir Path temp) throws Exception {
		Path status = temp.resolve(SignInTerminal.STATUS_FILE);
		assertNull(SignInTerminal.readStatus(status));

		Files.writeString(status, "3\r\n");
		assertEquals(Integer.valueOf(3), SignInTerminal.readStatus(status));
		Files.writeString(status, "-1073741510 \n");
		assertEquals(Integer.valueOf(-1073741510), SignInTerminal.readStatus(status));
		Files.writeString(status, "");
		assertNull(SignInTerminal.readStatus(status));
		Files.writeString(status, "not a number");
		assertNull(SignInTerminal.readStatus(status));
		assertNull(SignInTerminal.readStatus(temp), "a directory is no status");
	}

	@Test
	void aProgramIsOnThePathWhenOneOfItsDirectoriesHasIt(@TempDir Path temp) throws Exception {
		Path bin = Files.createDirectories(temp.resolve("bin"));
		Path program = Files.writeString(bin.resolve("xterm"), "#!/bin/sh\n");
		program.toFile().setExecutable(true);
		String path = temp.resolve("empty") + File.pathSeparator + File.pathSeparator + bin;

		assertTrue(SignInTerminal.onPath("xterm", Map.of("PATH", path)));
		assertFalse(SignInTerminal.onPath("konsole", Map.of("PATH", path)));
		assertFalse(SignInTerminal.onPath("xterm", Map.of()));
	}

	// ---------------------------------------------------------------------

	private static SignIn windows(String command, String... args) {
		List<String> list = List.of(args);
		return new SignIn("Kiro login", command, list, AgentCommandLine.of(command, list, Platform.WINDOWS, WINDOWS_ENV), Map.of());
	}

	private static SignIn posix(String command, Map<String, String> env, String... args) {
		List<String> list = List.of(args);
		return new SignIn("Kiro login", command, list, AgentCommandLine.of(command, list, Platform.LINUX, Map.of()), env);
	}

	private record Run(int exit, Integer status, String received) {
	}

	/** Runs the script as the new window would, minus the window. */
	private static Run runWindows(Path temp, SignIn signIn, int code) throws Exception {
		Path work = Files.createTempDirectory(temp, "run");
		Path status = work.resolve(SignInTerminal.STATUS_FILE);
		Path script = work.resolve("signin.cmd");
		Path out = work.resolve("out.txt");
		Files.writeString(script, SignInTerminal.windowsScript(signIn, status), StandardCharsets.UTF_8);
		int exit = run(List.of("cmd.exe", "/d", "/c", script.toString()),
				Map.of("KIRO_OUT", out.toString(), "KIRO_X", "from the env", "KIRO_CODE", Integer.toString(code)));
		return new Run(exit, SignInTerminal.readStatus(status),
				Files.exists(out) ? Files.readString(out, StandardCharsets.UTF_8).trim() : null);
	}

	/** Input is closed, so the pause after a failure returns at once; nothing is left running. */
	private static int run(List<String> argv, Map<String, String> env) throws Exception {
		ProcessBuilder builder = new ProcessBuilder(argv);
		builder.environment().putAll(env);
		builder.redirectErrorStream(true);
		builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
		Process process = builder.start();
		process.getOutputStream().close();
		try {
			assertTrue(process.waitFor(120, TimeUnit.SECONDS), "the script did not end: " + argv);
			return process.exitValue();
		} finally {
			process.descendants().forEach(ProcessHandle::destroyForcibly);
			process.destroyForcibly();
		}
	}

	/** A POSIX shell: the system's, or on Windows the one that comes with Git when it is on the PATH. */
	private static Path shell() {
		if (Platform.current() != Platform.WINDOWS) {
			Path sh = Path.of("/bin/sh");
			return Files.isExecutable(sh) ? sh : null;
		}
		String path = System.getenv("PATH");
		for (String directory : (path == null ? "" : path).split(File.pathSeparator)) {
			try {
				Path sh = Path.of(directory, "sh.exe");
				if (Files.isRegularFile(sh)) {
					return sh;
				}
			} catch (RuntimeException notAPath) {
				// An entry of PATH that names no directory.
			}
		}
		return null;
	}
}
