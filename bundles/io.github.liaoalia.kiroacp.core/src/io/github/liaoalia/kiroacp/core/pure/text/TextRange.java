package io.github.liaoalia.kiroacp.core.pure.text;

import java.util.Locale;

/**
 * Helpers for {@code fs/read_text_file} / {@code fs/write_text_file}: line
 * slicing and path normalisation.
 */
public final class TextRange {

	private TextRange() {
	}

	/** The whole text. */
	public static String sliceLines(String text) {
		return sliceLines(text, null, null);
	}

	/** The text from 1-based {@code line} on. */
	public static String sliceLines(String text, Number line) {
		return sliceLines(text, line, null);
	}

	/**
	 * Return the lines of {@code text} starting at 1-based {@code line} (default
	 * 1), at most {@code limit} lines (default: all). Line endings are preserved
	 * exactly, so joining consecutive slices reproduces the input. A start line
	 * beyond the end yields an empty string.
	 *
	 * @param line  {@code null} for the first line; values below 1 mean 1
	 * @param limit {@code null} for no limit; zero or less yields an empty string
	 */
	public static String sliceLines(String text, Number line, Number limit) {
		// Doubles, because the values come from the agent's JSON and the reference
		// floors them: 2.9 is line 2.
		double start = Math.max(1, Math.floor(line == null ? 1 : line.doubleValue()));
		boolean hasLimit = limit != null;
		if (start == 1 && !hasLimit) {
			return text;
		}
		if (hasLimit && limit.doubleValue() <= 0) {
			return "";
		}
		double from = start - 1;
		double to = hasLimit ? from + Math.floor(limit.doubleValue()) : Double.POSITIVE_INFINITY;
		// A line never takes less than one character, so indexes are bounded by the length.
		int max = text.length();
		int fromIndex = index(from, max);
		int toIndex = index(to, max);
		int begin = lineOffset(text, 0, fromIndex);
		if (begin >= text.length() || toIndex <= fromIndex) {
			return "";
		}
		int end = lineOffset(text, begin, toIndex - fromIndex);
		return text.substring(begin, end);
	}

	/** A slice index as JavaScript's {@code Array.prototype.slice} reads it: NaN is 0. */
	private static int index(double value, int max) {
		if (Double.isNaN(value) || value <= 0) {
			return 0;
		}
		return value >= max ? max : (int) value;
	}

	/** Offset of the start of the line {@code lines} lines after {@code offset}; the length when there is none. */
	private static int lineOffset(String text, int offset, int lines) {
		int pos = offset;
		for (int i = 0; i < lines; i++) {
			int newline = text.indexOf('\n', pos);
			if (newline < 0) {
				return text.length();
			}
			pos = newline + 1;
		}
		return pos;
	}

	/** {@link #normalizeFsPath(String, Platform)} for the current platform. */
	public static String normalizeFsPath(String p) {
		return normalizeFsPath(p, null);
	}

	/**
	 * Normalise an absolute path for comparisons: resolves {@code .}/{@code ..},
	 * and on Windows lower-cases the drive letter and uses backslashes
	 * consistently. The result is suitable as a map key (two paths normalise
	 * identically iff both name the same file, modulo case on NTFS which is not
	 * folded).
	 *
	 * @param platform {@code null} for the current platform
	 */
	public static String normalizeFsPath(String p, Platform platform) {
		if (Platform.orCurrent(platform) == Platform.WIN32) {
			String resolved = NodePath.resolve(p.replace('/', '\\'), Platform.WIN32);
			if (resolved.length() >= 2 && isAsciiLetter(resolved.charAt(0)) && resolved.charAt(1) == ':') {
				resolved = Character.toLowerCase(resolved.charAt(0)) + resolved.substring(1);
			}
			// Strip a trailing separator except for the drive root ("c:\").
			if (resolved.length() > 3 && resolved.endsWith("\\")) {
				resolved = resolved.substring(0, resolved.length() - 1);
			}
			return resolved;
		}
		String resolved = NodePath.resolve(p, Platform.POSIX);
		if (resolved.length() > 1 && resolved.endsWith("/")) {
			resolved = resolved.substring(0, resolved.length() - 1);
		}
		return resolved;
	}

	/** {@link #isAbsolutePath(String, Platform)} for the current platform. */
	public static boolean isAbsolutePath(String p) {
		return isAbsolutePath(p, null);
	}

	/**
	 * True when {@code p} is absolute on the given platform; false for
	 * {@code null} and the empty string.
	 *
	 * @param platform {@code null} for the current platform
	 */
	public static boolean isAbsolutePath(String p, Platform platform) {
		if (p == null || p.isEmpty()) {
			return false;
		}
		return NodePath.isAbsolute(p, platform);
	}

	/** {@link #isWithinRoot(String, String, Platform)} for the current platform. */
	public static boolean isWithinRoot(String child, String root) {
		return isWithinRoot(child, root, null);
	}

	/**
	 * True when {@code child} is {@code root} or lies beneath it. Both are
	 * normalised with {@link #normalizeFsPath}; on Windows the comparison is
	 * case-insensitive.
	 *
	 * @param platform {@code null} for the current platform
	 */
	public static boolean isWithinRoot(String child, String root, Platform platform) {
		Platform plat = Platform.orCurrent(platform);
		String c = normalizeFsPath(child, plat);
		String r = normalizeFsPath(root, plat);
		if (plat == Platform.WIN32) {
			c = c.toLowerCase(Locale.ROOT);
			r = r.toLowerCase(Locale.ROOT);
		}
		if (c.equals(r)) {
			return true;
		}
		String sep = plat == Platform.WIN32 ? "\\" : "/";
		String prefix = r.endsWith(sep) ? r : r + sep;
		return c.startsWith(prefix);
	}

	private static boolean isAsciiLetter(char c) {
		return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
	}
}
