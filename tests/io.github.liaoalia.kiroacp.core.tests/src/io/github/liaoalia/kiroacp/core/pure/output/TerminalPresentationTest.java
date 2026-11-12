package io.github.liaoalia.kiroacp.core.pure.output;

import static io.github.liaoalia.kiroacp.core.host.Settings.TerminalMode.HIDDEN;
import static io.github.liaoalia.kiroacp.core.host.Settings.TerminalMode.PER_COMMAND;
import static io.github.liaoalia.kiroacp.core.host.Settings.TerminalMode.SHARED;
import static io.github.liaoalia.kiroacp.core.pure.output.ShellText.stripAnsi;
import static io.github.liaoalia.kiroacp.core.pure.output.TerminalPresentation.commandTerminalName;
import static io.github.liaoalia.kiroacp.core.pure.output.TerminalPresentation.concurrentPrefix;
import static io.github.liaoalia.kiroacp.core.pure.output.TerminalPresentation.exitLabel;
import static io.github.liaoalia.kiroacp.core.pure.output.TerminalPresentation.formatCommandHeader;
import static io.github.liaoalia.kiroacp.core.pure.output.TerminalPresentation.formatExitFooter;
import static io.github.liaoalia.kiroacp.core.pure.output.TerminalPresentation.makeRunId;
import static io.github.liaoalia.kiroacp.core.pure.output.TerminalPresentation.makeTerminalId;
import static io.github.liaoalia.kiroacp.core.pure.output.TerminalPresentation.parseTerminalMode;
import static io.github.liaoalia.kiroacp.core.pure.output.TerminalPresentation.pickInputTarget;
import static io.github.liaoalia.kiroacp.core.pure.output.TerminalPresentation.sharedTerminalName;
import static io.github.liaoalia.kiroacp.core.pure.output.TerminalPresentation.shortSessionId;
import static io.github.liaoalia.kiroacp.core.pure.output.TerminalPresentation.shouldMirrorOutput;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.liaoalia.kiroacp.core.host.Settings.TerminalMode;
import io.github.liaoalia.kiroacp.core.pure.output.TerminalPresentation.ExitLike;
import io.github.liaoalia.kiroacp.core.pure.output.TerminalPresentation.InputCandidate;
import io.github.liaoalia.kiroacp.core.pure.output.TerminalPresentation.LinePrefixer;
import io.github.liaoalia.kiroacp.core.pure.output.TerminalPresentation.MirrorCandidate;

/**
 * The cases of the reference's {@code test/features-terminalPresentation.test.ts}
 * (its modes {@code shared} / {@code perCommand} / {@code hidden} are the
 * {@link TerminalMode} constants here), then those only the port needs.
 */
class TerminalPresentationTest {

	private record Exit(Integer exitCode, String signal) implements ExitLike {
	}

	private record Child(String id, long seq, boolean running) implements InputCandidate {
	}

	private record Entry(TerminalMode mode, boolean released) implements MirrorCandidate {
	}

	@Test
	void makeTerminalIdIsTermRunIdN() {
		assertEquals("term-ab12cd-1", makeTerminalId("ab12cd", 1));
		assertEquals("term-ab12cd-42", makeTerminalId("ab12cd", 42));
		assertTrue(makeTerminalId(makeRunId(), 7).matches("term-[A-Za-z0-9]+-\\d+"));
	}

	@Test
	void makeRunIdYieldsDistinctIdsPerRunSoTwoServiceInstancesNeverShareTerminalIds() {
		String runA = makeRunId();
		String runB = makeRunId();
		assertTrue(runA.matches("[a-z0-9]{6}"), runA);
		assertNotEquals(runA, runB);
		// The same counter value in two runs (a restart) is a different terminal id.
		assertNotEquals(makeTerminalId(runA, 1), makeTerminalId(runB, 1));
		assertNotEquals("term-1", makeTerminalId(runA, 1));
		assertEquals(10, makeRunId(10).length());
	}

	@Test
	void parseTerminalModeAcceptsTheEnumValues() {
		assertEquals(SHARED, parseTerminalMode("shared"));
		assertEquals(PER_COMMAND, parseTerminalMode("perCommand"));
		assertEquals(HIDDEN, parseTerminalMode("hidden"));
	}

	@Test
	void parseTerminalModeIsLenientAboutCaseAndSeparators() {
		assertEquals(PER_COMMAND, parseTerminalMode("per-command"));
		assertEquals(PER_COMMAND, parseTerminalMode("PER_COMMAND"));
		assertEquals(SHARED, parseTerminalMode("Shared "));
		assertEquals(HIDDEN, parseTerminalMode("Hidden"));
	}

	@Test
	void parseTerminalModeMapsLegacyBooleansAndSynonyms() {
		assertEquals(PER_COMMAND, parseTerminalMode(true));
		assertEquals(HIDDEN, parseTerminalMode(false));
		assertEquals(HIDDEN, parseTerminalMode("none"));
		assertEquals(HIDDEN, parseTerminalMode("off"));
		assertEquals(SHARED, parseTerminalMode("session"));
		assertEquals(PER_COMMAND, parseTerminalMode("visible"));
	}

	@Test
	void parseTerminalModeFallsBackToSharedForUnknownValues() {
		// JavaScript's undefined and null are both null here.
		assertEquals(SHARED, parseTerminalMode(null));
		assertEquals(SHARED, parseTerminalMode(42));
		assertEquals(SHARED, parseTerminalMode("banana"));
		assertEquals(SHARED, parseTerminalMode(""));
	}

	@Test
	void shortSessionIdKeepsTheFirstUuidGroupOrTwelveCharacters() {
		assertEquals("0b8e2f4a", shortSessionId("0b8e2f4a-1234-5678-9abc-def012345678"));
		assertEquals("0B8E2F4A", shortSessionId("0B8E2F4A-1234-5678-9ABC-DEF012345678"));
		assertEquals("sess_abcdefg", shortSessionId("sess_abcdefghijkl"));
		assertEquals("sess-3", shortSessionId("sess-3"));
		assertEquals("sess-12", shortSessionId("sess-12"));
		assertEquals("session", shortSessionId("   "));
	}

	@Test
	void sharedTerminalNameUsesTheTitleWhenGivenElseTheShortId() {
		assertEquals("Kiro · 0b8e2f4a", sharedTerminalName("0b8e2f4a-1234-5678-9abc-def012345678"));
		assertEquals("Kiro · sess-7", sharedTerminalName("sess-7"));
		assertEquals("Kiro · Fix the build", sharedTerminalName("0b8e2f4a-1234-", "Fix the  build\n"));
		assertEquals("Kiro · 0b8e2f4a", sharedTerminalName("0b8e2f4a-1234-", "   "));
		assertEquals("Kiro · 0b8e2f4a", sharedTerminalName("0b8e2f4a-1234-", null));
		String longName = sharedTerminalName("x", "a".repeat(60));
		assertEquals("Kiro · " + "a".repeat(39) + "…", longName);
	}

	@Test
	void commandTerminalNameCollapsesWhitespaceAndTruncates() {
		assertEquals("Kiro: npm test", commandTerminalName("npm   test\n"));
		assertEquals("Kiro: " + "x".repeat(39) + "…", commandTerminalName("x".repeat(50)));
	}

	@Test
	void formatCommandHeaderWritesAStyledLineWithTheCommandAndCwd() {
		String header = formatCommandHeader("npm test", "C:\\repo");
		assertTrue(header.startsWith("\r\n"), "starts with a blank line");
		assertTrue(header.endsWith("\r\n"), "ends with a line break");
		assertEquals("\r\n$ npm test   [C:\\repo]\r\n", stripAnsi(header));
		assertTrue(header.contains("\u001b[1;2m"), "bold+dim style");
		assertTrue(header.contains("\u001b[0m"), "style reset");
		assertEquals("\r\n$ ls\r\n", stripAnsi(formatCommandHeader("ls", "")));
	}

	@Test
	void exitLabelAndFormatExitFooterDescribeExitCodesAndSignals() {
		assertEquals("[exit code 0]", exitLabel(new Exit(0, null)));
		assertEquals("[exit code 2]", exitLabel(new Exit(2, null)));
		assertEquals("[terminated by SIGTERM]", exitLabel(new Exit(null, "SIGTERM")));
		assertEquals("[exit code unknown]", exitLabel(new Exit(null, null)));
		assertEquals("\r\n[exit code 1]\r\n", stripAnsi(formatExitFooter(new Exit(1, null))));
		assertTrue(formatExitFooter(new Exit(1, null)).contains("\u001b[2m"), "dim style");
	}

	@Test
	void pickInputTargetChoosesTheMostRecentlyStartedRunningChild() {
		Child a = new Child("term-1", 1, true);
		Child b = new Child("term-2", 2, true);
		Child c = new Child("term-3", 3, false);
		assertSame(b, pickInputTarget(List.of(a, b, c)));
		assertSame(b, pickInputTarget(List.of(c, b, a)));
		assertSame(a, pickInputTarget(List.of(a)));
		assertNull(pickInputTarget(List.of(c)));
		assertNull(pickInputTarget(List.<Child>of()));
	}

	@Test
	void linePrefixerPrefixesLineStartsAcrossChunkBoundaries() {
		LinePrefixer p = new LinePrefixer(concurrentPrefix("term-2"));
		assertEquals("[term-2] one\r\n[term-2] two", p.apply("one\r\ntwo"));
		assertEquals(" continued\n", p.apply(" continued\n"));
		assertEquals("[term-2] three", p.apply("three"));
		assertEquals("", p.apply(""));
		assertEquals("\r\n\r\n", p.apply("\r\n\r\n"));
		assertEquals("[term-2] four", p.apply("four"));
	}

	@Test
	void linePrefixerWithAnEmptyPrefixIsTheIdentity() {
		LinePrefixer p = new LinePrefixer("");
		assertEquals("a\nb", p.apply("a\nb"));
	}

	@Test
	void shouldMirrorOutputCutsAReleasedCommandOffTheSharedTerminalOnly() {
		assertTrue(shouldMirrorOutput(new Entry(SHARED, false)));
		assertTrue(!shouldMirrorOutput(new Entry(SHARED, true)));
		// A per-command terminal belongs to the command: a survivor's late output stays attributable there.
		assertTrue(shouldMirrorOutput(new Entry(PER_COMMAND, false)));
		assertTrue(shouldMirrorOutput(new Entry(PER_COMMAND, true)));
		// Hidden mode writes nowhere anyway; the flag does not matter.
		assertTrue(shouldMirrorOutput(new Entry(HIDDEN, false)));
		assertTrue(shouldMirrorOutput(new Entry(HIDDEN, true)));
	}

	// --- beyond the reference's cases ---

	@Test
	void parseTerminalModeKnowsEverySynonymAndIgnoresAnySeparator() {
		for (String value : List.of("persession", "single", "per session", "Per-Session")) {
			assertEquals(SHARED, parseTerminalMode(value), value);
		}
		for (String value : List.of("command", "separate", "always", "per command", "per\tcommand", "per\u00a0command")) {
			assertEquals(PER_COMMAND, parseTerminalMode(value), value);
		}
		assertEquals(HIDDEN, parseTerminalMode("never"));
		assertEquals(HIDDEN, parseTerminalMode(Boolean.FALSE));
		assertEquals(SHARED, parseTerminalMode(SHARED), "only strings and booleans are parsed");
		assertSame(SHARED, TerminalPresentation.DEFAULT_TERMINAL_MODE);
	}

	@Test
	void anExitCodeWinsOverASignal() {
		assertEquals("[exit code 137]", exitLabel(new Exit(137, "SIGKILL")));
	}

	@Test
	void aLoneCarriageReturnIsTextAndASplitLineBreakStillStartsALine() {
		LinePrefixer p = new LinePrefixer("> ");
		assertEquals("> 10%\r20%\r\n", p.apply("10%\r20%\r\n"), "progress redraws stay on their line");
		assertEquals("> a\r", p.apply("a\r"));
		assertEquals("\n> b", p.apply("\nb"), "the break was split over two chunks");
		assertEquals("\n\n> c\n", p.apply("\n\nc\n"));
		assertEquals("> ", p.prefix());
	}

	@Test
	void namesAreOneLineOfAtMostFortyCharacters() {
		assertEquals("Kiro: " + "y".repeat(40), commandTerminalName("y".repeat(40)));
		assertEquals("Kiro: a b", commandTerminalName("\u00a0a\u2028\u3000b\t"));
		assertEquals("Kiro · 0b8e2f4a", sharedTerminalName("  0b8e2f4a-1234-5678-9abc-def012345678  ", ""));
		assertEquals("0b8e2f4a-123", shortSessionId("0b8e2f4a-123"), "not the head of a UUID");
	}
}
