package io.github.liaoalia.kiroacp.ui.services;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import io.github.liaoalia.kiroacp.core.acp.process.Platform;
import io.github.liaoalia.kiroacp.core.pure.output.ShellText;
import io.github.liaoalia.kiroacp.core.services.ExternalActions.SignIn;

/**
 * A sign-in command in a terminal window of the operating system: the platform
 * has no terminal the user could type into (TM Terminal is not part of it).
 *
 * <p>The command is not put on the terminal's command line. Each terminal
 * program parses that differently, and on Windows it would pass through
 * {@code cmd.exe} twice. Instead a small script is written to a private
 * temporary directory and the terminal is asked to run that one file, so the
 * only thing ever quoted for a terminal program is a path of our own making.
 * The script runs the command, then writes its exit code to a file next to
 * itself: no terminal program reports the exit code of what ran in its window
 * in a way that can be relied on (most hand the window to a process that is
 * already running and return at once), and the file works for all of them.
 *
 * <p>Everything here is a pure function of its arguments except
 * {@link #prepare}, which writes the files.
 */
final class SignInTerminal {

	/** The file the script leaves the command's exit code in. */
	static final String STATUS_FILE = "exit-code";

	/**
	 * Terminal programs of Linux desktops with what makes them run a program
	 * (some take it as their own arguments and need nothing): the system's
	 * choice first, then those of the desktops, then the ones people install
	 * themselves.
	 */
	private static final List<List<String>> LINUX_TERMINALS = List.of(List.of("x-terminal-emulator", "-e"),
			List.of("gnome-terminal", "--"), List.of("ptyxis", "--"), List.of("kgx", "-e"), List.of("konsole", "-e"),
			List.of("xfce4-terminal", "-x"), List.of("mate-terminal", "-x"), List.of("lxterminal", "-e"),
			List.of("tilix", "-e"), List.of("kitty"), List.of("alacritty", "-e"), List.of("foot"),
			List.of("wezterm", "start", "--"), List.of("xterm", "-e"));

	private static final Pattern ENV_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
	private static final Pattern NOT_TITLE = Pattern.compile("[^A-Za-z0-9 ._-]");
	private static final String CMD_SPECIAL = "()!^\"<>&|";

	private SignInTerminal() {
	}

	/**
	 * What to start.
	 *
	 * @param directory holds the script and, later, the status file; nothing else
	 * @param status    appears, complete, when the command ended: its exit code in decimal
	 * @param argv      the terminal program and its arguments
	 */
	record Plan(Path directory, Path script, Path status, List<String> argv) {
	}

	/**
	 * Writes the script for the sign-in and says how to start a terminal on it.
	 *
	 * @param env    the IDE's environment, for {@code ComSpec}
	 * @param onPath whether a program of that name can be started
	 * @throws IOException when the files cannot be written or the desktop has no terminal program we know
	 */
	static Plan prepare(Platform platform, SignIn signIn, Map<String, String> env, Predicate<String> onPath)
			throws IOException {
		List<String> terminal = platform == Platform.LINUX ? linuxTerminal(onPath) : List.of();
		if (terminal == null) {
			// Shown to the user as it is: what was tried, and the way out that needs none of it.
			throw new IOException("No terminal program found to run the sign-in in (looked for "
					+ String.join(", ", LINUX_TERMINALS.stream().map(each -> each.get(0)).toList())
					+ "). Run this in a terminal yourself, then restart the agent: " + displayLine(signIn));
		}
		Path directory = Files.createTempDirectory("kiro-signin");
		Path status = directory.resolve(STATUS_FILE);
		Path script;
		List<String> argv;
		switch (platform) {
		case WINDOWS:
			script = directory.resolve("signin.cmd");
			Files.writeString(script, windowsScript(signIn, status), StandardCharsets.UTF_8);
			argv = windowsCommand(signIn.title(), script, env);
			break;
		case MAC:
			// Terminal runs a file of this extension when asked to open it.
			script = ownerOnly(directory.resolve("signin.command"));
			Files.writeString(script, posixScript(signIn, status, false, script), StandardCharsets.UTF_8);
			argv = macCommand(script);
			break;
		default:
			script = ownerOnly(directory.resolve("signin.sh"));
			Files.writeString(script, posixScript(signIn, status, true, script), StandardCharsets.UTF_8);
			argv = linuxCommand(terminal, script);
			break;
		}
		// Not deleted when the sign-in is over: on Windows the window may still be reading
		// its script. The directory holds two small files and goes with the JVM.
		directory.toFile().deleteOnExit();
		script.toFile().deleteOnExit();
		return new Plan(directory, script, status, argv);
	}

	/**
	 * The script's file, empty, executable and its owner's alone from the
	 * moment it exists: it may come to hold the variables of the sign-in
	 * method. (The directory around it is the owner's alone as well.)
	 */
	private static Path ownerOnly(Path script) throws IOException {
		try {
			return Files.createFile(script, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
		} catch (UnsupportedOperationException notPosix) {
			// A file system without such permissions: whoever runs the script starts it through sh.
			return script;
		}
	}

	/** The sign-in as the user would type it, for a message. */
	private static String displayLine(SignIn signIn) {
		List<String> words = new ArrayList<>();
		words.add(signIn.command());
		words.addAll(signIn.args());
		return String.join(" ", words.stream().map(word -> word.isEmpty() || word.indexOf(' ') >= 0 ? '"' + word + '"' : word).toList());
	}

	/** Removes what {@link #prepare} wrote, once the script has run to its end. */
	static void discard(Plan plan) {
		try {
			Files.deleteIfExists(plan.status());
			Files.deleteIfExists(plan.script());
			Files.deleteIfExists(plan.directory());
		} catch (IOException | RuntimeException e) {
			// Left to the operating system's cleaning of its temporary directory.
		}
	}

	/** @return the exit code the script left behind; {@code null} while there is none */
	static Integer readStatus(Path status) {
		try {
			if (!Files.isRegularFile(status)) {
				return null;
			}
			return Integer.valueOf(Files.readString(status, StandardCharsets.US_ASCII).trim());
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	// ---------------------------------------------------------------------
	// Windows
	// ---------------------------------------------------------------------

	/**
	 * {@code cmd.exe /d /s /c "start "<title>" "<script>""}: a new console window
	 * running the script. {@code start} runs a batch file with {@code cmd /k}, so
	 * the window lasts until the script says {@code exit}.
	 *
	 * <p>The last element is passed to {@code cmd.exe} as it is: it begins and
	 * ends with a quote, which keeps Java from quoting it again, and with
	 * {@code /s} exactly that pair is stripped.
	 */
	static List<String> windowsCommand(String title, Path script, Map<String, String> env) {
		String shell = null;
		for (Map.Entry<String, String> each : env.entrySet()) {
			if (each.getKey().equalsIgnoreCase("ComSpec")) {
				shell = each.getValue();
			}
		}
		// The first quoted word after start is the window's title, so there must be one.
		String line = "start \"" + title(title) + "\" \"" + script + "\"";
		return List.of(shell == null || shell.isBlank() ? "cmd.exe" : shell, "/d", "/s", "/c", "\"" + line + "\"");
	}

	/**
	 * The batch file a sign-in runs from. The environment is not in it: the new
	 * window inherits that of the process that starts it.
	 *
	 * <p>The lines after the command are one line on purpose. {@code cmd.exe}
	 * reads a batch file line by line, and once the exit code is in the status
	 * file the script may be deleted; what is still to do has been read by then.
	 */
	static String windowsScript(SignIn signIn, Path status) {
		String target = batchPath(status.toString());
		StringBuilder script = new StringBuilder();
		script.append("@echo off\r\n");
		// The file is UTF-8; without this a path with other than ASCII letters is misread.
		script.append("chcp 65001 >nul\r\n");
		script.append("title ").append(title(signIn.title())).append("\r\n");
		script.append(windowsCommandLine(signIn)).append("\r\n");
		script.append("set KIRO_SIGNIN_EXIT=%errorlevel%\r\n");
		script.append("(echo %KIRO_SIGNIN_EXIT%)>\"").append(target).append(".tmp\"");
		script.append("& move /y \"").append(target).append(".tmp\" \"").append(target).append("\" >nul");
		// A failure stays readable until the user has seen it.
		script.append("& (if not \"%KIRO_SIGNIN_EXIT%\"==\"0\" pause)");
		script.append("& exit %KIRO_SIGNIN_EXIT%\r\n");
		return script.toString();
	}

	/** The line of the batch file that runs the command. */
	static String windowsCommandLine(SignIn signIn) {
		StringBuilder line = new StringBuilder();
		if (signIn.launch().viaShell()) {
			// A batch file: without call it would not come back to write the exit code. It
			// receives its arguments as text, so quotes in them are doubled, and call
			// expands percent signs a second time. (It also doubles every ^ in a quoted
			// argument; nothing written here can prevent that.)
			line.append("call ").append(batchArg(signIn.command(), "%%%%"));
			for (String arg : signIn.args()) {
				line.append(' ').append(batchArg(arg, "%%%%"));
			}
			return line.toString();
		}
		line.append(batchArg(signIn.command(), "%%"));
		for (String arg : signIn.args()) {
			line.append(' ').append(programArg(arg));
		}
		return line.toString();
	}

	private static String batchArg(String value, String percent) {
		return "\"" + value.replace("%", percent).replace("\"", "\"\"") + "\"";
	}

	/**
	 * An argument for a program that splits its command line by the C runtime's
	 * rules ({@code \"} is a quote), written so that {@code cmd.exe} takes none of
	 * it for its own syntax: every character special to it is escaped, the quotes
	 * included, so nothing in the argument can end a quoted stretch and expose
	 * what follows.
	 */
	private static String programArg(String arg) {
		String quoted = ShellText.quoteArg(arg, io.github.liaoalia.kiroacp.core.pure.text.Platform.WIN32);
		StringBuilder out = new StringBuilder(quoted.length() + 8);
		for (int i = 0; i < quoted.length(); i++) {
			char c = quoted.charAt(i);
			if (c == '%') {
				out.append("%%");
			} else {
				if (CMD_SPECIAL.indexOf(c) >= 0) {
					out.append('^');
				}
				out.append(c);
			}
		}
		return out.toString();
	}

	private static String batchPath(String path) {
		return path.replace("%", "%%");
	}

	private static String title(String title) {
		String plain = NOT_TITLE.matcher(title == null ? "" : title).replaceAll("").trim();
		return plain.isEmpty() ? "Kiro" : plain;
	}

	// ---------------------------------------------------------------------
	// macOS and Linux
	// ---------------------------------------------------------------------

	/** {@code open -a Terminal <script>}: Terminal opens a window and runs the file in it. */
	static List<String> macCommand(Path script) {
		return List.of("open", "-a", "Terminal", script.toString());
	}

	/** @return the first terminal program that can be started, with its option; {@code null} when there is none */
	static List<String> linuxTerminal(Predicate<String> onPath) {
		for (List<String> each : LINUX_TERMINALS) {
			if (onPath.test(each.get(0))) {
				return each;
			}
		}
		return null;
	}

	/**
	 * The terminal program, its option and the script as one word: some of
	 * these programs take the rest of the command line as the program's
	 * arguments and some take a single string, and one word is the same to both.
	 */
	static List<String> linuxCommand(List<String> terminal, Path script) {
		List<String> argv = new ArrayList<>(terminal);
		argv.add(script.toString());
		return List.copyOf(argv);
	}

	/**
	 * {@link #posixScript(SignIn, Path, boolean, Path)} for a script that
	 * stays where it is.
	 */
	static String posixScript(SignIn signIn, Path status, boolean holdOnFailure) {
		return posixScript(signIn, status, holdOnFailure, null);
	}

	/**
	 * The shell script a sign-in runs from. The variables of the sign-in
	 * method are in it: a terminal program usually hands the window to a
	 * process that is already running, and that one does not have the
	 * environment of whoever asked. Only those variables: nothing of the
	 * IDE's own environment is ever written.
	 *
	 * <p>A script that sets variables removes its own file before anything
	 * else. The shell that runs it has it open and reads on, so what an agent
	 * put into those variables is on disk until the window is up, not for as
	 * long as the sign-in takes or the IDE runs.
	 *
	 * @param holdOnFailure keep the window open after a failure until Enter is pressed, for
	 *                      terminals that close with their program
	 * @param self          where the script will be, for the removal; {@code null}: it stays
	 */
	static String posixScript(SignIn signIn, Path status, boolean holdOnFailure, Path self) {
		String target = status.toString();
		StringBuilder exports = new StringBuilder();
		for (Map.Entry<String, String> each : new TreeMap<>(signIn.env()).entrySet()) {
			if (ENV_NAME.matcher(each.getKey()).matches()) {
				exports.append("export ").append(each.getKey()).append('=').append(quote(each.getValue())).append('\n');
			}
		}
		StringBuilder script = new StringBuilder();
		script.append("#!/bin/sh\n");
		if (self != null && exports.length() > 0) {
			script.append("rm -f ").append(quote(self.toString())).append('\n');
		}
		script.append("printf '\\033]0;%s\\007' ").append(quote(title(signIn.title()))).append('\n');
		script.append(exports);
		script.append(quote(signIn.command()));
		for (String arg : signIn.args()) {
			script.append(' ').append(quote(arg));
		}
		script.append('\n');
		script.append("code=$?\n");
		// Written under another name first: the file is complete when it appears.
		script.append("printf '%s\\n' \"$code\" > ").append(quote(target + ".tmp")).append(" && mv -f ")
				.append(quote(target + ".tmp")).append(' ').append(quote(target)).append('\n');
		if (holdOnFailure) {
			script.append("if [ \"$code\" -ne 0 ]; then\n");
			script.append("\tprintf '\\n[exit code %s] Press Enter to close this window. ' \"$code\"\n");
			script.append("\tread -r _\n");
			script.append("fi\n");
		}
		script.append("exit \"$code\"\n");
		return script.toString();
	}

	/** Always quoted: an unquoted word with {@code =} in the command's place would be an assignment. */
	private static String quote(String value) {
		return "'" + value.replace("'", "'\\''") + "'";
	}

	/** @return whether a program of that name is in a directory of {@code PATH} */
	static boolean onPath(String program, Map<String, String> env) {
		String path = env.get("PATH");
		if (path == null) {
			return false;
		}
		for (String directory : path.split(Pattern.quote(File.pathSeparator))) {
			try {
				if (!directory.isEmpty() && Files.isExecutable(Path.of(directory, program))) {
					return true;
				}
			} catch (RuntimeException notAPath) {
				// An entry of PATH that names no directory.
			}
		}
		return false;
	}
}
