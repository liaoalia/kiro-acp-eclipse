package io.github.liaoalia.kiroacp.core.acp.process;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The command line the agent is started with.
 *
 * @param viaShell whether the command was wrapped in {@code cmd.exe}, so the agent is a grandchild
 */
public record AgentCommandLine(String command, List<String> args, boolean viaShell) {

	public AgentCommandLine {
		args = List.copyOf(args);
	}

	/**
	 * A {@code .cmd} or {@code .bat} file (an npm-style wrapper around the CLI)
	 * is run by {@code cmd.exe /d /s /c} with every argument quoted, so that what
	 * the batch file receives does not depend on how the launcher guesses at
	 * quoting. Anything else is started directly, which keeps stdio a plain pipe.
	 */
	public static AgentCommandLine of(String command, List<String> args, Platform platform, Map<String, String> env) {
		String lower = command.toLowerCase(Locale.ROOT);
		if (platform != Platform.WINDOWS || !(lower.endsWith(".cmd") || lower.endsWith(".bat"))) {
			return new AgentCommandLine(command, args, false);
		}
		String line = Stream.concat(Stream.of(command), args.stream()).map(AgentCommandLine::quote)
				.collect(Collectors.joining(" "));
		// With /s, cmd.exe strips exactly the outer pair of quotes and runs the rest as typed.
		// Java passes an argument on untouched only if it starts and ends with a quote and does
		// not end in \" ; the space keeps a last argument ending in a backslash from being
		// quoted a second time.
		String whole = "\"" + line + (line.endsWith("\\\"") ? " \"" : "\"");
		String shell = CliPathResolver.lookup(env, "ComSpec");
		return new AgentCommandLine(shell == null || shell.isEmpty() ? "cmd.exe" : shell, List.of("/d", "/s", "/c", whole),
				true);
	}

	private static String quote(String value) {
		return "\"" + value.replace("\"", "\"\"") + "\"";
	}
}
