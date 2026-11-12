package io.github.liaoalia.kiroacp.ui.services;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.github.liaoalia.kiroacp.core.pure.output.TerminalPresentation;

class TerminalTextTest {

	private static final String ESC = "\u001b";

	private final TerminalText text = new TerminalText();

	@Test
	void terminalLineEndsBecomePlainOnes() {
		assertEquals("one\ntwo\n", text.feed("one\r\ntwo\r\n"));
		assertEquals("already\nplain\n", text.feed("already\nplain\n"));
	}

	@Test
	void escapeSequencesAreStripped() {
		assertEquals("red and bold\n", text.feed(ESC + "[31mred" + ESC + "[0m and " + ESC + "[1;2mbold" + ESC + "[0m\r\n"));
		assertEquals("after a title", text.feed(ESC + "]0;window title\u0007after a title"));
	}

	@Test
	void theHeaderAndFooterOfTheServiceComeOutAsText() {
		assertEquals("\n$ npm test   [/work]\n", text.feed(TerminalPresentation.formatCommandHeader("npm test", "/work")));
		assertEquals("\n[exit code 0]\n", text.feed(TerminalPresentation.formatExitFooter(new TerminalPresentation.ExitLike() {
			@Override
			public Integer exitCode() {
				return 0;
			}

			@Override
			public String signal() {
				return null;
			}
		})));
	}

	@Test
	void aLineEndCutByTheChunkBoundaryIsOneLineEnd() {
		assertEquals("one", text.feed("one\r"));
		assertEquals("\ntwo", text.feed("\ntwo"));
	}

	@Test
	void aReturnAloneStartsANewLine() {
		assertEquals("10%\n20%\ndone\n", text.feed("10%\r20%\rdone\r\n"));
		assertEquals("30%", text.feed("30%\r"));
		assertEquals("\n40%", text.feed("40%"));
	}

	@Test
	void aTrailingReturnIsOwedUntilTheEnd() {
		assertEquals("last", text.feed("last\r"));
		assertEquals("\n", text.flush());
		assertEquals("", text.flush());
	}

	@Test
	void aSequenceCutByTheChunkBoundaryIsStrippedAll() {
		assertEquals("plain ", text.feed("plain " + ESC));
		assertEquals("", text.feed("[3"));
		assertEquals("red", text.feed("1mred"));
		assertEquals("", text.feed(ESC + "]0;a title in two"));
		assertEquals("shown", text.feed(" parts\u0007shown"));
	}

	@Test
	void whatLooksLikeAnEndlessSequenceIsNotHeldBackForEver() {
		String endless = ESC + "]" + "x".repeat(TerminalText.LONGEST_HELD_SEQUENCE);

		assertEquals("x".repeat(TerminalText.LONGEST_HELD_SEQUENCE), text.feed(endless));
		assertEquals("more", text.feed("more"));
	}

	@Test
	void otherControlCharactersAreDroppedAndTabsKept() {
		assertEquals("a\tbc", text.feed("a\tb\u0007\u0008c\u0000"));
	}

	@Test
	void anUnfinishedSequenceIsDroppedAtTheEnd() {
		assertEquals("text", text.feed("text" + ESC + "[3"));
		assertEquals("", text.flush());
		assertEquals("next", text.feed("next"));
	}
}
