package io.github.liaoalia.kiroacp.ui.services;

import java.util.regex.Pattern;

import io.github.liaoalia.kiroacp.core.pure.output.ShellText;

/**
 * Turns what a terminal would receive into what a console of the platform can
 * show: plain text with {@code \n} line ends. The console renders neither ANSI
 * escape sequences nor carriage returns (it would print the former and break
 * the line at the latter).
 *
 * <p>Output arrives in chunks cut anywhere, so a sequence or a {@code \r\n}
 * may be split over two calls; what is incomplete at the end of a chunk is
 * kept for the next. Not thread-safe: one instance serves one console.
 */
final class TerminalText {

	private static final char ESC = '\u001b';
	/** Longer than any colour or cursor sequence; a window title (OSC) fits as well. */
	static final int LONGEST_HELD_SEQUENCE = 256;
	/** Control characters a console has no use for; tab and the line ends stay. */
	private static final Pattern CONTROLS = Pattern.compile("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f\\x7f]");

	private String held = "";
	private boolean pendingReturn;

	/** @return the printable text of the chunk, possibly empty */
	String feed(String chunk) {
		String text = held + chunk;
		held = "";
		int cut = unfinishedSequence(text);
		if (cut >= 0) {
			held = text.substring(cut);
			text = text.substring(0, cut);
		}
		text = CONTROLS.matcher(ShellText.stripAnsi(text)).replaceAll("");
		StringBuilder out = new StringBuilder(text.length() + 1);
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (pendingReturn) {
				pendingReturn = false;
				out.append('\n');
				if (c == '\n') {
					continue;
				}
			}
			// A return alone (a progress line redrawn in place) cannot be shown as
			// such in a console that only appends: each redraw gets its own line.
			if (c == '\r') {
				pendingReturn = true;
			} else {
				out.append(c);
			}
		}
		return out.toString();
	}

	/** What is still owed when no more text follows: the line end of a trailing return. */
	String flush() {
		held = "";
		if (pendingReturn) {
			pendingReturn = false;
			return "\n";
		}
		return "";
	}

	/** Start of an escape sequence the chunk ends in the middle of; -1 for none. */
	private static int unfinishedSequence(String text) {
		int start = text.lastIndexOf(ESC);
		if (start < 0 || text.length() - start > LONGEST_HELD_SEQUENCE) {
			return -1;
		}
		String tail = text.substring(start);
		if (tail.length() == 1) {
			return start;
		}
		char kind = tail.charAt(1);
		// Only sequences with parameters span more than two characters.
		if (kind == ']') {
			// A title or the like (OSC) runs up to a BEL; its other terminator starts
			// with an ESC of its own, which would then have been the last one.
			return tail.indexOf('\u0007') < 0 ? start : -1;
		}
		// A complete CSI is stripped whole; an unfinished one is left as it is.
		return kind == '[' && ShellText.stripAnsi(tail).indexOf(ESC) == 0 ? start : -1;
	}
}
