package io.github.liaoalia.kiroacp.core.session;

import io.github.liaoalia.kiroacp.core.pure.text.JsText;

/** The fallback title of a session: its first prompt, shortened. */
final class Titles {

	static final int MAX = 40;

	private Titles() {
	}

	/** First line of the prompt, whitespace collapsed, cut to about {@value #MAX} characters. */
	static String truncate(String text) {
		String first = text.split("\r?\n", 2)[0];
		String line = JsText.trim(first.replaceAll(JsText.WHITESPACE_CLASS + "+", " "));
		if (line.length() <= MAX) {
			return line;
		}
		return line.substring(0, MAX - 1).stripTrailing() + "\u2026";
	}
}
