package io.github.liaoalia.kiroacp.core.pure.text;

import static io.github.liaoalia.kiroacp.core.pure.text.Platform.POSIX;
import static io.github.liaoalia.kiroacp.core.pure.text.Platform.WIN32;
import static io.github.liaoalia.kiroacp.core.pure.text.TextRange.isAbsolutePath;
import static io.github.liaoalia.kiroacp.core.pure.text.TextRange.isWithinRoot;
import static io.github.liaoalia.kiroacp.core.pure.text.TextRange.normalizeFsPath;
import static io.github.liaoalia.kiroacp.core.pure.text.TextRange.sliceLines;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The cases of the reference's {@code test/features-textRange.test.ts}, then those only the port needs. */
class TextRangeTest {

	private static final String TEXT = "one\ntwo\r\nthree\nfour";

	@Test
	void sliceLinesReturnsTheWholeTextWhenNoRangeIsGiven() {
		assertEquals(TEXT, sliceLines(TEXT));
		assertEquals(TEXT, sliceLines(TEXT, null, null));
		assertEquals(TEXT, sliceLines(TEXT, 1));
	}

	@Test
	void sliceLinesAppliesOneBasedStartAndLimitPreservingLineEndings() {
		assertEquals("two\r\nthree\nfour", sliceLines(TEXT, 2));
		assertEquals("two\r\nthree\n", sliceLines(TEXT, 2, 2));
		assertEquals("one\n", sliceLines(TEXT, 1, 1));
		assertEquals("four", sliceLines(TEXT, 4, 10));
		assertEquals("one\n", sliceLines(TEXT, null, 1));
	}

	@Test
	void sliceLinesBeyondTheEndOrWithANonPositiveLimitYieldsEmpty() {
		assertEquals("", sliceLines(TEXT, 5));
		assertEquals("", sliceLines(TEXT, 99, 3));
		assertEquals("", sliceLines(TEXT, 1, 0));
		assertEquals("", sliceLines("", 1, 5));
	}

	@Test
	void sliceLinesWithATrailingNewlineKeepsTheTerminator() {
		assertEquals("b\n", sliceLines("a\nb\n", 2));
		assertEquals("", sliceLines("a\nb\n", 3));
	}

	@Test
	void normalizeFsPathLowerCasesTheDriveAndUsesBackslashesOnWin32() {
		assertEquals("c:\\Users\\Me\\file.txt", normalizeFsPath("C:/Users/Me/file.txt", WIN32));
		assertEquals("c:\\Users\\file.txt", normalizeFsPath("c:\\Users\\Me\\..\\file.txt", WIN32));
		assertEquals("c:\\dir", normalizeFsPath("C:\\dir\\", WIN32));
		assertEquals("c:\\", normalizeFsPath("C:\\", WIN32));
		assertEquals(normalizeFsPath("c:\\a\\b", WIN32), normalizeFsPath("C:/a/b", WIN32),
				"equal paths normalise identically");
	}

	@Test
	void normalizeFsPathResolvesDotSegmentsOnPosix() {
		assertEquals("/home/me/file.ts", normalizeFsPath("/home/me/./src/../file.ts", POSIX));
		assertEquals("/home/me", normalizeFsPath("/home/me/", POSIX));
		assertEquals("/", normalizeFsPath("/", POSIX));
	}

	@Test
	void isAbsolutePathCases() {
		assertTrue(isAbsolutePath("C:\\x", WIN32));
		assertFalse(isAbsolutePath("x\\y", WIN32));
		assertTrue(isAbsolutePath("/x", POSIX));
		assertFalse(isAbsolutePath("x/y", POSIX));
		assertFalse(isAbsolutePath("", POSIX));
	}

	@Test
	void isWithinRootCases() {
		assertTrue(isWithinRoot("C:\\ws\\src\\a.ts", "c:/ws", WIN32));
		assertTrue(isWithinRoot("C:\\ws", "C:\\ws\\", WIN32));
		assertFalse(isWithinRoot("C:\\ws2\\a.ts", "C:\\ws", WIN32), "sibling prefix");
		assertFalse(isWithinRoot("D:\\ws\\a.ts", "C:\\ws", WIN32));
		assertTrue(isWithinRoot("/ws/src/a.ts", "/ws", POSIX));
		assertFalse(isWithinRoot("/wsx/a.ts", "/ws", POSIX));
	}

	// --- beyond the reference's cases: what JavaScript gives for free ---

	@Test
	void sliceLinesFloorsFractionsAndTreatsAStartBelowOneAsOne() {
		assertSame(TEXT, sliceLines(TEXT, 0));
		assertSame(TEXT, sliceLines(TEXT, -3, null));
		assertEquals("two\r\nthree\nfour", sliceLines(TEXT, 2.9));
		assertEquals("two\r\n", sliceLines(TEXT, 2, 1.5));
		assertEquals("", sliceLines(TEXT, 2, 0.5), "a limit below one line is no line");
		assertEquals("", sliceLines(TEXT, 1, -1));
		assertEquals("three\nfour", sliceLines(TEXT, 3, Long.MAX_VALUE));
		assertEquals("", sliceLines(TEXT, Long.MAX_VALUE, 1));
	}

	@Test
	void consecutiveSlicesReproduceTheInput() {
		String text = "a\r\n\nb\n\n";
		StringBuilder joined = new StringBuilder();
		for (int line = 1; line <= 5; line++) {
			joined.append(sliceLines(text, line, 1));
		}
		assertEquals(text, joined.toString());
		assertEquals("\n", sliceLines(text, 2, 1));
	}

	@Test
	void win32PathsFollowNodeForRootsUncAndForeignSeparators() {
		assertEquals("\\\\server\\share\\a", normalizeFsPath("//server/share/x/../a/", WIN32));
		assertEquals("\\\\server\\share", normalizeFsPath("\\\\server\\share\\", WIN32));
		assertEquals("c:\\", normalizeFsPath("C:\\a\\..\\..\\..", WIN32), "never above the root");
		assertEquals("/", normalizeFsPath("/a/../../..", POSIX));
		assertEquals("/a\\b", normalizeFsPath("/a\\b", POSIX), "a backslash is an ordinary character on posix");
		assertTrue(isAbsolutePath("\\x", WIN32), "rooted without a drive");
		assertTrue(isAbsolutePath("C:/x", WIN32));
		assertFalse(isAbsolutePath("C:x", WIN32), "drive-relative");
		assertFalse(isAbsolutePath("C:", WIN32));
		assertFalse(isAbsolutePath(null, WIN32));
		assertFalse(isAbsolutePath("C:\\x", POSIX));
		assertTrue(isWithinRoot("c:\\WS\\a", "C:\\ws", WIN32), "case-insensitive on win32");
		assertFalse(isWithinRoot("/WS/a", "/ws", POSIX));
		assertTrue(isWithinRoot("C:\\a", "C:\\", WIN32), "a drive root keeps its separator");
		assertTrue(isWithinRoot("/a", "/", POSIX));
	}

	@Test
	void aRelativePathIsResolvedAgainstTheWorkingDirectory() {
		Platform here = Platform.current();
		String resolved = normalizeFsPath("some-file.txt", here);
		assertTrue(isAbsolutePath(resolved, here), resolved);
		assertTrue(resolved.endsWith(here == WIN32 ? "\\some-file.txt" : "/some-file.txt"), resolved);
		assertEquals(normalizeFsPath(System.getProperty("user.dir"), here), normalizeFsPath(".", here));
	}

	@Test
	void basenameIsTheLastSegment() {
		assertEquals("hello.txt", NodePath.basename("/work/src/hello.txt", POSIX));
		assertEquals("src", NodePath.basename("/work/src/", POSIX));
		assertEquals("", NodePath.basename("/", POSIX));
		assertEquals("tasks.json", NodePath.basename("C:\\Work\\tasks.json", WIN32));
		assertEquals("tasks.json", NodePath.basename("C:/Work/tasks.json", WIN32));
		assertEquals("", NodePath.basename("C:\\", WIN32));
		assertEquals("C:\\Work\\tasks.json", NodePath.basename("C:\\Work\\tasks.json", POSIX));
	}

	@Test
	void jsTextKeepsJavaScriptSemantics() {
		assertEquals("a b", JsText.trim("\u00a0\ufeff a b\u2028\t"));
		assertEquals("\u0001a", JsText.trim("\u0001a"), "control characters are not white space");
		assertTrue(JsText.containsWhitespace("a\u3000b"));
		assertFalse(JsText.containsWhitespace("a\u200bb"));
		assertTrue("\u00a0".matches(JsText.WHITESPACE_CLASS));
		assertEquals("\"npm test\"", JsText.jsonQuote("npm test"));
		assertEquals("\"say \\\"hi\\\" \\\\ \\n\\t\\u001b\"", JsText.jsonQuote("say \"hi\" \\ \n\t\u001b"));
		assertEquals("\"\uD83D\uDE00 \\ud83d\"", JsText.jsonQuote("\uD83D\uDE00 \uD83D"));
	}
}
