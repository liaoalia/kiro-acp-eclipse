package io.github.liaoalia.kiroacp.core.pure.output;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import io.github.liaoalia.kiroacp.core.pure.text.JsText;
import io.github.liaoalia.kiroacp.core.pure.text.Platform;

/**
 * Text helpers for agent terminals (D9 of the reference): ANSI stripping,
 * shell command assembly and environment merging. Methods without a
 * {@link Platform} parameter use the current platform, as does a {@code null}
 * platform.
 */
public final class ShellText {

	private static final Pattern ANSI = Pattern.compile("\\x1b\\[[0-?]*[ -/]*[@-~]" + "|\\x1b\\][^\\x07\\x1b]*(?:\\x07|\\x1b\\\\)"
			+ "|\\x1b[@-Z\\\\-_]" + "|\\x9b[0-?]*[ -/]*[@-~]");

	private static final Pattern WIN_NEEDS_QUOTES = Pattern.compile(JsText.WHITESPACE_CLASS + "|[\"&|<>^()%!]");
	private static final Pattern BACKSLASHES_BEFORE_QUOTE = Pattern.compile("(\\\\*)\"");
	private static final Pattern TRAILING_BACKSLASHES = Pattern.compile("(\\\\+)\\z");
	private static final Pattern POSIX_PLAIN = Pattern.compile("[A-Za-z0-9_./:=@%+,-]+");
	private static final Pattern NEWLINE = Pattern.compile("\\r?\\n");

	private ShellText() {
	}

	/** Remove ANSI CSI/OSC/ESC control sequences (colours, cursor moves, titles). */
	public static String stripAnsi(String s) {
		return ANSI.matcher(s).replaceAll("");
	}

	public static String quoteArg(String arg) {
		return quoteArg(arg, null);
	}

	/** Quote a single argument for the platform's default shell when needed. */
	public static String quoteArg(String arg, Platform platform) {
		if (Platform.orCurrent(platform) == Platform.WIN32) {
			if (!arg.isEmpty() && !WIN_NEEDS_QUOTES.matcher(arg).find()) {
				return arg;
			}
			// Backslashes are literal except before a quote, where each must be doubled.
			String escaped = BACKSLASHES_BEFORE_QUOTE.matcher(arg).replaceAll("$1$1\\\\\"");
			escaped = TRAILING_BACKSLASHES.matcher(escaped).replaceFirst("$1$1");
			return "\"" + escaped + "\"";
		}
		if (POSIX_PLAIN.matcher(arg).matches()) {
			return arg;
		}
		return "'" + arg.replace("'", "'\\''") + "'";
	}

	public static String buildShellCommand(String command, List<String> args) {
		return buildShellCommand(command, args, null);
	}

	/**
	 * Build the single command line handed to the platform shell. With no args
	 * the command is passed verbatim so compound commands ({@code a && b}) keep
	 * working; otherwise each arg is quoted.
	 *
	 * @param args may be {@code null}
	 */
	public static String buildShellCommand(String command, List<String> args, Platform platform) {
		if (args == null || args.isEmpty()) {
			return command;
		}
		Platform plat = Platform.orCurrent(platform);
		List<String> parts = new ArrayList<>(args.size() + 1);
		parts.add(JsText.containsWhitespace(command) ? quoteArg(command, plat) : command);
		for (String arg : args) {
			parts.add(quoteArg(arg, plat));
		}
		return String.join(" ", parts);
	}

	public static Map<String, String> mergeEnv(Map<String, String> base, List<EnvVariable> extra) {
		return mergeEnv(base, extra, null);
	}

	/**
	 * Merge ACP {@code {name, value}} pairs over a base environment, into a new
	 * map. On Windows names are case-insensitive, so an existing key with
	 * different casing is replaced.
	 *
	 * @param extra may be {@code null}
	 */
	public static Map<String, String> mergeEnv(Map<String, String> base, List<EnvVariable> extra, Platform platform) {
		Map<String, String> env = new LinkedHashMap<>(base);
		if (extra == null) {
			return env;
		}
		boolean win = Platform.orCurrent(platform) == Platform.WIN32;
		for (EnvVariable variable : extra) {
			String name = variable.name();
			if (win) {
				String lower = name.toLowerCase(Locale.ROOT);
				env.keySet().removeIf(key -> !key.equals(name) && key.toLowerCase(Locale.ROOT).equals(lower));
			}
			env.put(name, variable.value());
		}
		return env;
	}

	/** Convert bare {@code \n} to {@code \r\n}, the line ending of a terminal. */
	public static String toPtyText(String s) {
		return NEWLINE.matcher(s).replaceAll("\r\n");
	}
}
