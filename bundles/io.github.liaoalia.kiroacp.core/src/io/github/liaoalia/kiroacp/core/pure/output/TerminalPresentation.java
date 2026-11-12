package io.github.liaoalia.kiroacp.core.pure.output;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

import io.github.liaoalia.kiroacp.core.host.Settings.TerminalMode;
import io.github.liaoalia.kiroacp.core.pure.text.JsText;

/**
 * How agent terminals are presented (D9 of the reference): the terminal mode
 * setting, the shared terminal's name, the header/footer written around each
 * command and which child receives keystrokes typed into a shared terminal.
 */
public final class TerminalPresentation {

	public static final TerminalMode DEFAULT_TERMINAL_MODE = TerminalMode.SHARED;

	/** Prefix of every terminal name this plugin creates ({@code Kiro · …}, {@code Kiro: …}). */
	public static final String TERMINAL_NAME_PREFIX = "Kiro";

	private static final String RUN_ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
	private static final Pattern SEPARATORS = Pattern.compile(JsText.WHITESPACE_CLASS + "|[_-]");
	private static final Pattern WHITESPACE_RUN = Pattern.compile(JsText.WHITESPACE_CLASS + "+");
	private static final Pattern UUID_HEAD = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-", Pattern.CASE_INSENSITIVE);

	private static final String BOLD_DIM = "\u001b[1;2m";
	private static final String DIM = "\u001b[2m";
	private static final String RESET = "\u001b[0m";

	private TerminalPresentation() {
	}

	/**
	 * Parse a setting value. Accepts the enum (case- and separator-insensitive:
	 * {@code per-command}, {@code PerCommand}, {@code per_command}), legacy
	 * booleans ({@code true} → per command, {@code false} → hidden) and a few
	 * synonyms ({@code session}, {@code single}, {@code none}, {@code off}).
	 * Anything else, {@code null} included, falls back to shared.
	 */
	public static TerminalMode parseTerminalMode(Object value) {
		if (Boolean.TRUE.equals(value)) {
			return TerminalMode.PER_COMMAND;
		}
		if (Boolean.FALSE.equals(value)) {
			return TerminalMode.HIDDEN;
		}
		if (!(value instanceof String text)) {
			return DEFAULT_TERMINAL_MODE;
		}
		String key = SEPARATORS.matcher(text.toLowerCase(Locale.ROOT)).replaceAll("");
		switch (key) {
		case "shared":
		case "session":
		case "persession":
		case "single":
			return TerminalMode.SHARED;
		case "percommand":
		case "command":
		case "separate":
		case "visible":
		case "always":
			return TerminalMode.PER_COMMAND;
		case "hidden":
		case "none":
		case "off":
		case "never":
			return TerminalMode.HIDDEN;
		default:
			return DEFAULT_TERMINAL_MODE;
		}
	}

	public static String makeRunId() {
		return makeRunId(6);
	}

	/**
	 * A short random id for one run of the terminal service, so terminal ids
	 * never repeat across restarts: a restored session's replayed {@code term-…}
	 * ids must not resolve to this run's terminals.
	 */
	public static String makeRunId(int length) {
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < length; i++) {
			out.append(RUN_ID_ALPHABET.charAt(ThreadLocalRandom.current().nextInt(RUN_ID_ALPHABET.length())));
		}
		return out.toString();
	}

	/** Terminal id of the {@code n}-th {@code terminal/create} of run {@code runId}: {@code term-<runId>-<n>}. */
	public static String makeTerminalId(String runId, long n) {
		return "term-" + runId + "-" + n;
	}

	/**
	 * Short form of an ACP session id for terminal names: the first group of a
	 * UUID ({@code 0b8e2f4a}), otherwise the first 12 characters ({@code sess-3}).
	 */
	public static String shortSessionId(String sessionId) {
		String trimmed = JsText.trim(sessionId);
		if (trimmed.isEmpty()) {
			return "session";
		}
		if (UUID_HEAD.matcher(trimmed).lookingAt()) {
			return trimmed.substring(0, 8);
		}
		return trimmed.substring(0, Math.min(12, trimmed.length()));
	}

	public static String sharedTerminalName(String sessionId) {
		return sharedTerminalName(sessionId, null);
	}

	/**
	 * Name of the session's shared terminal: {@code Kiro · <title or short id>}.
	 *
	 * @param title may be {@code null} or blank
	 */
	public static String sharedTerminalName(String sessionId, String title) {
		String label = title != null && !JsText.trim(title).isEmpty() ? oneLine(title, 40) : shortSessionId(sessionId);
		return TERMINAL_NAME_PREFIX + " · " + label;
	}

	/** Name of a per-command terminal: {@code Kiro: <command>}. */
	public static String commandTerminalName(String command) {
		return TERMINAL_NAME_PREFIX + ": " + oneLine(command, 40);
	}

	/**
	 * Header written before a command's output: a blank line, then the command
	 * line and its cwd in bold+dim. Uses {@code \r\n} (terminal line endings).
	 */
	public static String formatCommandHeader(String commandLine, String cwd) {
		String where = cwd.isEmpty() ? "" : "   [" + cwd + "]";
		return "\r\n" + BOLD_DIM + "$ " + commandLine + where + RESET + "\r\n";
	}

	/** How a command ended. */
	public interface ExitLike {
		/** {@code null} when unknown or terminated by a signal. */
		Integer exitCode();

		/** {@code null} unless terminated by a signal. */
		String signal();
	}

	/** Plain footer label: {@code [exit code N]} or {@code [terminated by SIGTERM]}. */
	public static String exitLabel(ExitLike exit) {
		if (exit.signal() != null && exit.exitCode() == null) {
			return "[terminated by " + exit.signal() + "]";
		}
		return "[exit code " + (exit.exitCode() != null ? exit.exitCode().toString() : "unknown") + "]";
	}

	/** Footer written after a command's output (dim), on its own line. */
	public static String formatExitFooter(ExitLike exit) {
		return "\r\n" + DIM + exitLabel(exit) + RESET + "\r\n";
	}

	/** A child process that may receive the keystrokes typed into a shared terminal. */
	public interface InputCandidate {
		String id();

		/** Monotonic start order (higher = started later). */
		long seq();

		boolean running();
	}

	/**
	 * Which child receives keystrokes typed into a shared terminal: the most
	 * recently started one that is still running, or {@code null}.
	 */
	public static <T extends InputCandidate> T pickInputTarget(Iterable<T> candidates) {
		T best = null;
		for (T candidate : candidates) {
			if (!candidate.running()) {
				continue;
			}
			if (best == null || candidate.seq() > best.seq()) {
				best = candidate;
			}
		}
		return best;
	}

	/** The bits of a terminal entry that decide whether its child's output is still mirrored. */
	public interface MirrorCandidate {
		TerminalMode mode();

		/** {@code terminal/release} happened (the agent no longer holds the terminal). */
		boolean released();
	}

	/**
	 * Whether output arriving from a command's child process should still be
	 * written into its terminal. In shared mode a released command is cut off:
	 * its process is killed on release, but a survivor (POSIX: the {@code sh -c}
	 * wrapper dies, a grandchild lives on) must not keep writing into the
	 * session's terminal where it would be unattributable and interleave with
	 * later commands. A per-command terminal is the command's own, so late
	 * output stays there; hidden mode mirrors nothing anyway. The exit footer is
	 * written regardless (it is not output).
	 */
	public static boolean shouldMirrorOutput(MirrorCandidate entry) {
		return entry.mode() != TerminalMode.SHARED || !entry.released();
	}

	/** Prefix for the output lines of a command that runs concurrently with another. */
	public static String concurrentPrefix(String terminalId) {
		return "[" + terminalId + "] ";
	}

	/**
	 * Inserts a prefix at the start of every line across chunk boundaries, so
	 * interleaved output of concurrent commands stays attributable. Not
	 * thread-safe: one instance serves one stream of chunks.
	 */
	public static final class LinePrefixer {

		private final String prefix;
		private boolean atLineStart = true;

		public LinePrefixer(String prefix) {
			this.prefix = prefix;
		}

		public String prefix() {
			return prefix;
		}

		/** Return {@code chunk} with the prefix inserted where lines begin. */
		public String apply(String chunk) {
			if (chunk.isEmpty() || prefix.isEmpty()) {
				return chunk;
			}
			StringBuilder out = new StringBuilder(chunk.length() + prefix.length());
			int pos = 0;
			while (pos < chunk.length()) {
				int newline = chunk.indexOf('\n', pos);
				int end = newline < 0 ? chunk.length() : newline;
				// A line break is "\r\n" or "\n"; a lone "\r" is ordinary text.
				int textEnd = newline > pos && chunk.charAt(newline - 1) == '\r' ? newline - 1 : end;
				if (textEnd > pos) {
					if (atLineStart) {
						out.append(prefix);
						atLineStart = false;
					}
					out.append(chunk, pos, textEnd);
				}
				if (newline < 0) {
					break;
				}
				out.append(chunk, textEnd, newline + 1);
				atLineStart = true;
				pos = newline + 1;
			}
			return out.toString();
		}
	}

	private static String oneLine(String s, int max) {
		String flat = JsText.trim(WHITESPACE_RUN.matcher(s).replaceAll(" "));
		return flat.length() <= max ? flat : flat.substring(0, max - 1) + "…";
	}
}
