package io.github.liaoalia.kiroacp.core.pure.text;

/**
 * String operations with the semantics of the reference's JavaScript, where the
 * JDK's own differ: its notion of white space ({@code \s}, {@code trim()}) is
 * wider than both {@link String#trim()} and the default {@code \s} of
 * {@link java.util.regex.Pattern}, and {@code JSON.stringify} escapes
 * differently from Jackson. Labels and keys built here are compared with those
 * of the reference, so the difference would be observable.
 */
public final class JsText {

	/**
	 * A regular-expression character class equal to JavaScript's {@code \s}
	 * (white space and line terminators, including U+00A0 and U+FEFF).
	 */
	public static final String WHITESPACE_CLASS = "[\\t\\n\\x0B\\f\\r \\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff]";

	private JsText() {
	}

	/** True for the characters JavaScript's {@code \s} and {@code trim()} treat as white space. */
	public static boolean isWhitespace(char c) {
		return (c >= '\t' && c <= '\r') || c == ' ' || c == ' ' || c == ' ' || (c >= ' ' && c <= ' ')
				|| c == ' ' || c == ' ' || c == ' ' || c == ' ' || c == '　' || c == '﻿';
	}

	/** {@code String.prototype.trim}. */
	public static String trim(String s) {
		int start = 0;
		int end = s.length();
		while (start < end && isWhitespace(s.charAt(start))) {
			start++;
		}
		while (end > start && isWhitespace(s.charAt(end - 1))) {
			end--;
		}
		return s.substring(start, end);
	}

	/** True when {@code s} contains a JavaScript white-space character ({@code /\s/.test(s)}). */
	public static boolean containsWhitespace(String s) {
		for (int i = 0; i < s.length(); i++) {
			if (isWhitespace(s.charAt(i))) {
				return true;
			}
		}
		return false;
	}

	/** {@code JSON.stringify} of a string: the quoted form, with its escapes. */
	public static String jsonQuote(String s) {
		StringBuilder out = new StringBuilder(s.length() + 2);
		out.append('"');
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
			case '"' -> out.append("\\\"");
			case '\\' -> out.append("\\\\");
			case '\b' -> out.append("\\b");
			case '\f' -> out.append("\\f");
			case '\n' -> out.append("\\n");
			case '\r' -> out.append("\\r");
			case '\t' -> out.append("\\t");
			default -> {
				if (c < 0x20) {
					appendEscape(out, c);
				} else if (Character.isHighSurrogate(c) && i + 1 < s.length()
						&& Character.isLowSurrogate(s.charAt(i + 1))) {
					out.append(c).append(s.charAt(++i));
				} else if (Character.isSurrogate(c)) {
					// Well-formed JSON.stringify: a lone surrogate is escaped, never emitted raw.
					appendEscape(out, c);
				} else {
					out.append(c);
				}
			}
			}
		}
		return out.append('"').toString();
	}

	private static void appendEscape(StringBuilder out, char c) {
		out.append("\\u");
		String hex = Integer.toHexString(c);
		out.append("0000", hex.length(), 4).append(hex);
	}
}
