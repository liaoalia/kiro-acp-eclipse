package io.github.liaoalia.kiroacp.core.pure.output;

import static io.github.liaoalia.kiroacp.core.pure.output.OutputBuffer.dropLeadingBytes;
import static io.github.liaoalia.kiroacp.core.pure.output.ShellText.buildShellCommand;
import static io.github.liaoalia.kiroacp.core.pure.output.ShellText.mergeEnv;
import static io.github.liaoalia.kiroacp.core.pure.output.ShellText.quoteArg;
import static io.github.liaoalia.kiroacp.core.pure.output.ShellText.stripAnsi;
import static io.github.liaoalia.kiroacp.core.pure.output.ShellText.toPtyText;
import static io.github.liaoalia.kiroacp.core.pure.text.Platform.POSIX;
import static io.github.liaoalia.kiroacp.core.pure.text.Platform.WIN32;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/** The cases of the reference's {@code test/features-outputBuffer.test.ts}, then those only the port needs. */
class OutputBufferTest {

	private static final String EMOJI = "\uD83D\uDE00";

	@Test
	void keepsEverythingUnderTheLimit() {
		OutputBuffer buf = new OutputBuffer(100);
		buf.append("hello ");
		buf.append("world");
		assertEquals("hello world", buf.text());
		assertFalse(buf.truncated());
		assertEquals(11, buf.byteLength());
	}

	@Test
	void dropsTheOldestBytesWhenOverTheLimit() {
		OutputBuffer buf = new OutputBuffer(8);
		buf.append("abcd");
		buf.append("efgh");
		buf.append("ij");
		assertEquals("cdefghij", buf.text());
		assertTrue(buf.truncated());
		assertEquals(8, buf.byteLength());
	}

	@Test
	void dropsWholeOldChunksFirst() {
		OutputBuffer buf = new OutputBuffer(5);
		buf.append("12345");
		buf.append("67890");
		assertEquals("67890", buf.text());
		buf.append("ab");
		assertEquals("890ab", buf.text());
	}

	@Test
	void cutsOnACharacterBoundaryOfMultiByteUtf8() {
		OutputBuffer buf = new OutputBuffer(6);
		buf.append("é"); // 2 bytes
		buf.append("日本"); // 6 bytes → total 8, need to drop 2 → exactly the "é"
		assertEquals("日本", buf.text());
		buf.append("x"); // 7 bytes → drop 1 byte: must drop whole "日" (3 bytes)
		assertEquals("本x", buf.text());
		assertTrue(buf.byteLength() <= 6);
		assertTrue(buf.truncated());
	}

	@Test
	void aSingleOversizedChunkKeepsItsTail() {
		OutputBuffer buf = new OutputBuffer(3);
		buf.append("abcdef");
		assertEquals("def", buf.text());
	}

	@Test
	void aNonPositiveLimitIsUnbounded() {
		OutputBuffer buf = new OutputBuffer(0);
		buf.append("x".repeat(1000));
		assertEquals(1000, buf.text().length());
		assertFalse(buf.truncated());
	}

	@Test
	void dropLeadingBytesCases() {
		assertEquals("abc", dropLeadingBytes("abc", 0));
		assertEquals("bc", dropLeadingBytes("abc", 1));
		assertEquals("", dropLeadingBytes("abc", 5));
		assertEquals("本", dropLeadingBytes("日本", 1));
		assertEquals("a", dropLeadingBytes(EMOJI + "a", 2), "4-byte emoji dropped whole");
	}

	@Test
	void stripAnsiRemovesCsiAndOscSequences() {
		assertEquals("red plain", stripAnsi("\u001b[31mred\u001b[0m plain"));
		assertEquals("text", stripAnsi("\u001b]0;title\u0007text"));
		assertEquals("text", stripAnsi("\u001b]0;title\u001b\\text"));
		assertEquals("line", stripAnsi("\u001b[2K\u001b[1Gline"));
		assertEquals("no escapes", stripAnsi("no escapes"));
	}

	@Test
	void quoteArgCases() {
		assertEquals("plain", quoteArg("plain", POSIX));
		assertEquals("'has space'", quoteArg("has space", POSIX));
		assertEquals("'it'\\''s'", quoteArg("it's", POSIX));
		assertEquals("plain", quoteArg("plain", WIN32));
		assertEquals("\"has space\"", quoteArg("has space", WIN32));
		assertEquals("\"say \\\"hi\\\"\"", quoteArg("say \"hi\"", WIN32));
		assertEquals("\"\"", quoteArg("", WIN32));
		assertEquals("''", quoteArg("", POSIX));
	}

	@Test
	void buildShellCommandPassesBareCommandsThroughAndQuotesArgs() {
		assertEquals("npm test && echo ok", buildShellCommand("npm test && echo ok", null, POSIX));
		assertEquals("git commit -m 'two words'", buildShellCommand("git", List.of("commit", "-m", "two words"), POSIX));
		assertEquals("git commit -m \"two words\"", buildShellCommand("git", List.of("commit", "-m", "two words"), WIN32));
		assertEquals("\"C:\\Program Files\\x.exe\" a", buildShellCommand("C:\\Program Files\\x.exe", List.of("a"), WIN32));
	}

	@Test
	void mergeEnvOverridesAndIsCaseInsensitiveOnWin32() {
		Map<String, String> base = new LinkedHashMap<>();
		base.put("Path", "/a");
		base.put("HOME", "/h");
		Map<String, String> posix = mergeEnv(base, List.of(new EnvVariable("PATH", "/b")), POSIX);
		assertEquals("/a", posix.get("Path"));
		assertEquals("/b", posix.get("PATH"));
		Map<String, String> win = mergeEnv(base, List.of(new EnvVariable("PATH", "/b")), WIN32);
		assertNull(win.get("Path"));
		assertFalse(win.containsKey("Path"));
		assertEquals("/b", win.get("PATH"));
		assertEquals("/h", win.get("HOME"));
		assertEquals(base, mergeEnv(base, null, POSIX));
	}

	@Test
	void toPtyTextConvertsNewlines() {
		assertEquals("a\r\nb\r\nc\r\n", toPtyText("a\nb\r\nc\n"));
	}

	// --- beyond the reference's cases ---

	@Test
	void sizesAreUtf8BytesOfTheUtf16Text() {
		assertEquals(0, OutputBuffer.utf8Length(""));
		assertEquals(1 + 2 + 3 + 4, OutputBuffer.utf8Length("aé日" + EMOJI));
		for (String s : List.of("plain", "é日本", EMOJI + "a" + EMOJI, "\u07ff\u0800\uffff")) {
			assertEquals(s.getBytes(StandardCharsets.UTF_8).length, OutputBuffer.utf8Length(s), s);
		}
		// Where the JDK's encoder would write one "?", the reference counts the three bytes of U+FFFD.
		assertEquals(3, OutputBuffer.utf8Length("\uD83D"));
		assertEquals(3, OutputBuffer.utf8Length("\uDE00"));
		assertEquals(3 + 1 + 3, OutputBuffer.utf8Length("\uDE00a\uD83D"), "a reversed pair is two lone surrogates");
	}

	@Test
	void aSurrogatePairIsNeverSplit() {
		String text = "a" + EMOJI + "b" + EMOJI + "c";
		for (int bytes = 0; bytes <= 12; bytes++) {
			String kept = dropLeadingBytes(text, bytes);
			assertTrue(text.endsWith(kept), bytes + " → " + kept);
			assertTrue(OutputBuffer.utf8Length(text) - OutputBuffer.utf8Length(kept) >= Math.min(bytes, 11), "" + bytes);
			assertEquals(kept, new String(kept.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8), "valid text");
		}
		assertEquals("b" + EMOJI + "c", dropLeadingBytes(text, 2));
		assertEquals("b" + EMOJI + "c", dropLeadingBytes(text, 5));
		assertEquals(EMOJI + "c", dropLeadingBytes(text, 6));
		assertEquals("c", dropLeadingBytes(text, 7));
		assertEquals("", dropLeadingBytes(text, 11));

		OutputBuffer buf = new OutputBuffer(5);
		buf.append(EMOJI);
		buf.append(EMOJI); // 8 bytes: one emoji goes whole
		assertEquals(EMOJI, buf.text());
		assertEquals(4, buf.byteLength());
		buf.append("ab"); // 6 bytes: dropping one byte takes the whole emoji
		assertEquals("ab", buf.text());
		assertEquals(2, buf.byteLength());
	}

	@Test
	void aPairSplitAcrossChunksIsReunitedInTheTextAndCountedAsTwoLoneSurrogates() {
		OutputBuffer buf = new OutputBuffer(100);
		buf.append("a\uD83D");
		buf.append("\uDE00b");
		assertEquals("a" + EMOJI + "b", buf.text());
		assertEquals(8, buf.byteLength());
	}

	@Test
	void loneSurrogatesOfACutChunkComeBackAsReplacementCharacters() {
		String text = "ab\uD83Dc\uDE00";
		assertSame(text, dropLeadingBytes(text, 0), "nothing dropped, nothing changed");
		assertEquals("b\ufffdc\ufffd", dropLeadingBytes(text, 1));
		assertEquals("c\ufffd", dropLeadingBytes(text, 3), "a cut inside the three bytes moves past them");
		assertEquals("", dropLeadingBytes(text, 9));

		OutputBuffer buf = new OutputBuffer(7);
		buf.append(text); // 9 bytes
		assertEquals("\ufffdc\ufffd", buf.text());
		assertEquals(7, buf.byteLength());
	}

	@Test
	void theBufferStaysWithinItsLimitOverManyAppends() {
		OutputBuffer buf = new OutputBuffer(64);
		StringBuilder all = new StringBuilder();
		String[] pieces = { "line one\n", "é", "日本語\r\n", EMOJI, "", "x".repeat(70), "tail" };
		for (int i = 0; i < 200; i++) {
			String piece = pieces[i % pieces.length];
			buf.append(piece);
			all.append(piece);
			assertTrue(buf.byteLength() <= 64, "after " + i);
			assertEquals(OutputBuffer.utf8Length(buf.text()), buf.byteLength(), "after " + i);
			assertTrue(all.toString().endsWith(buf.text()), "after " + i);
		}
		assertTrue(buf.truncated());
		assertEquals(64, buf.limitBytes());
		assertSame(buf.text(), buf.text(), "the joined text is cached");
	}

	@Test
	void aNegativeLimitIsUnboundedToo() {
		OutputBuffer buf = new OutputBuffer(-1);
		buf.append("abc");
		buf.append("");
		assertEquals("abc", buf.text());
		assertFalse(buf.truncated());
		assertEquals(3, buf.byteLength());
	}

	@Test
	void stripAnsiAlsoRemovesTwoCharacterEscapesAndEightBitCsi() {
		assertEquals("ab", stripAnsi("a\u001bMb"));
		assertEquals("ab", stripAnsi("a\u001b\\b"));
		assertEquals("ab", stripAnsi("a\u009b1;31mb"));
		assertEquals("a\u001b", stripAnsi("a\u001b"), "an unfinished escape is left alone");
	}

	@Test
	void quoteArgDoublesBackslashesOnlyBeforeAQuoteOnWin32() {
		assertEquals("C:\\dir\\", quoteArg("C:\\dir\\", WIN32), "nothing to quote, nothing to escape");
		assertEquals("\"C:\\my dir\\\\\"", quoteArg("C:\\my dir\\", WIN32));
		assertEquals("\"a\\\\\\\"b c\"", quoteArg("a\\\"b c", WIN32));
		assertEquals("\"50%\"", quoteArg("50%", WIN32));
		assertEquals("\"a\u00a0b\"", quoteArg("a\u00a0b", WIN32), "white space as JavaScript sees it");
		assertEquals("'a\nb'", quoteArg("a\nb", POSIX));
		assertEquals("'plain\n'", quoteArg("plain\n", POSIX));
		assertEquals("a=b,c@d%e+f:/g.h-i_j", quoteArg("a=b,c@d%e+f:/g.h-i_j", POSIX));
		assertEquals("git", buildShellCommand("git", List.of(), POSIX));
		assertEquals("'my tool' ''", buildShellCommand("my tool", List.of(""), POSIX));
	}

	@Test
	void mergeEnvLeavesTheBaseAloneAndKeepsLaterDuplicates() {
		Map<String, String> base = new LinkedHashMap<>();
		base.put("Path", "/a");
		base.put("HOME", "/h");
		Map<String, String> copy = mergeEnv(base, null, WIN32);
		assertNotSame(base, copy);
		Map<String, String> win = mergeEnv(base,
				List.of(new EnvVariable("path", "/b"), new EnvVariable("PATH", "/c"), new EnvVariable("HOME", "/x")),
				WIN32);
		assertEquals(Map.of("PATH", "/c", "HOME", "/x"), win);
		assertEquals(List.of("HOME", "PATH"), new ArrayList<>(win.keySet()));
		assertEquals(Map.of("Path", "/a", "HOME", "/h"), base);
	}

	@Test
	void envVariablesAreReadFromTheRequestArray() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		assertEquals(List.of(new EnvVariable("A", "1"), new EnvVariable("B", "")),
				EnvVariable.listOf(mapper.readTree(
						"[{\"name\":\"A\",\"value\":\"1\"},{\"name\":\"B\",\"value\":\"\"},{\"name\":\"C\"},{\"name\":1,\"value\":\"x\"},7]")));
		assertEquals(List.of(), EnvVariable.listOf(null));
		assertEquals(List.of(), EnvVariable.listOf(mapper.readTree("null")));
		assertEquals(List.of(), EnvVariable.listOf(mapper.readTree("{}")));
	}
}
