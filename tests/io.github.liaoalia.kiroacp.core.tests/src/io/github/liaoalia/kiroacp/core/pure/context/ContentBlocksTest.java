package io.github.liaoalia.kiroacp.core.pure.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.liaoalia.kiroacp.core.model.ContextChip;
import io.github.liaoalia.kiroacp.core.model.ImageAttachment;
import io.github.liaoalia.kiroacp.core.pure.context.ContentBlocks.TextReader;
import io.github.liaoalia.kiroacp.core.pure.text.NodePath;
import io.github.liaoalia.kiroacp.core.pure.text.Platform;

/** The cases of the reference's {@code test/features-contentBlocks.test.ts}, file search included. */
class ContentBlocksTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final String ABS = NodePath.resolve("/ws/src/app.ts", null);
	private static final ContextChip FILE_CHIP = new ContextChip("file:" + ABS, ContextChip.Kind.FILE, ABS, "src/app.ts", null, null,
			true);
	private static final ContextChip SEL_CHIP = new ContextChip("sel:" + ABS + ":2-3", ContextChip.Kind.SELECTION, ABS, "src/app.ts",
			2, 3, false);
	private static final TextReader FAKE_READ = path -> {
		if (!path.equals(ABS)) {
			throw new IOException("ENOENT");
		}
		return "line1\nline2\nline3\nline4";
	};

	/** What Node's {@code pathToFileURL(path).href} gives, by the JDK's own conversion. */
	private static String url(String absPath) {
		return Path.of(absPath).toUri().toString();
	}

	private static JsonNode json(String text) {
		try {
			return MAPPER.readTree(text);
		} catch (JsonProcessingException e) {
			throw new IllegalArgumentException(text, e);
		}
	}

	private static String quoted(String text) {
		return MAPPER.valueToTree(text).toString();
	}

	@Test
	void textBlockFirstThenResourceLinkForFileChips() {
		List<JsonNode> blocks = ContentBlocks.buildPromptBlocks("hello", List.of(FILE_CHIP), FAKE_READ, true, List.of());
		assertEquals(2, blocks.size());
		assertEquals(json("{\"type\":\"text\",\"text\":\"hello\"}"), blocks.get(0));
		assertEquals(json("{\"type\":\"resource_link\",\"uri\":" + quoted(url(ABS))
				+ ",\"name\":\"src/app.ts\",\"mimeType\":\"text/typescript\"}"), blocks.get(1));
	}

	@Test
	void emptyTextProducesNoTextBlock() {
		List<JsonNode> blocks = ContentBlocks.buildPromptBlocks("", List.of(FILE_CHIP), FAKE_READ, false, List.of());
		assertEquals(1, blocks.size());
		assertEquals("resource_link", blocks.get(0).get("type").asText());
	}

	@Test
	void selectionBecomesAnEmbeddedResourceWhenEmbeddedContextIsAdvertised() {
		List<JsonNode> blocks = ContentBlocks.buildPromptBlocks("q", List.of(SEL_CHIP), FAKE_READ, true, List.of());
		assertEquals(2, blocks.size());
		assertEquals(json("{\"type\":\"resource\",\"resource\":{\"uri\":" + quoted(url(ABS) + "#L2-L3")
				+ ",\"text\":\"line2\\nline3\",\"mimeType\":\"text/typescript\"}}"), blocks.get(1));
	}

	@Test
	void selectionBecomesAFencedTextBlockOtherwise() {
		List<JsonNode> blocks = ContentBlocks.buildPromptBlocks("q", List.of(SEL_CHIP), FAKE_READ, false, List.of());
		assertEquals(2, blocks.size());
		assertEquals(json("{\"type\":\"text\",\"text\":\"Selection from src/app.ts:2-3:\\n```ts\\nline2\\nline3\\n```\"}"),
				blocks.get(1));
	}

	@Test
	void unreadableSelectionDegradesToAResourceLinkAndDuplicateChipsAreSkipped() {
		String other = NodePath.resolve("/ws/missing.py", null);
		ContextChip chip = new ContextChip("sel:" + other + ":1-1", ContextChip.Kind.SELECTION, other, "missing.py", 1, 1, false);
		List<JsonNode> blocks = ContentBlocks.buildPromptBlocks("", List.of(chip, chip, FILE_CHIP, FILE_CHIP), FAKE_READ, true,
				List.of());
		assertEquals(2, blocks.size());
		assertEquals(json("{\"type\":\"resource_link\",\"uri\":" + quoted(url(other))
				+ ",\"name\":\"missing.py\",\"mimeType\":\"text/x-python\"}"), blocks.get(0));
	}

	@Test
	void imagesGoAfterTheTextAndBeforeTheChipsOneImageBlockEach() {
		String shot = NodePath.resolve("/ws/shot.png", null);
		List<ImageAttachment> images = List.of(
				new ImageAttachment("a", "shot.png", "image/png", "iVBORw0K", 6, shot, "data:image/png;base64,AAAA"),
				new ImageAttachment("b", "Pasted image 1.png", "image/jpeg", "/9j/4A==", 4, null, null));
		List<JsonNode> blocks = ContentBlocks.buildPromptBlocks("look", List.of(FILE_CHIP), FAKE_READ, true, images);
		assertEquals(List.of("text", "image", "image", "resource_link"), blocks.stream().map(b -> b.get("type").asText()).toList());
		assertEquals(json("{\"type\":\"image\",\"mimeType\":\"image/png\",\"data\":\"iVBORw0K\",\"uri\":" + quoted(url(shot)) + "}"),
				blocks.get(1));
		assertEquals(json("{\"type\":\"image\",\"mimeType\":\"image/jpeg\",\"data\":\"/9j/4A==\"}"), blocks.get(2));

		List<JsonNode> imageOnly = ContentBlocks.buildPromptBlocks("", List.of(), FAKE_READ, false, images.subList(1, 2));
		assertEquals(List.of(json("{\"type\":\"image\",\"mimeType\":\"image/jpeg\",\"data\":\"/9j/4A==\"}")), imageOnly);
	}

	@Test
	void helpers() {
		assertEquals("application/json", ContentBlocks.guessMimeType("a.json"));
		assertNull(ContentBlocks.guessMimeType("a.unknownext"));
		assertEquals("python", ContentBlocks.languageForPath("x.PY"));
		assertEquals("", ContentBlocks.languageForPath("x.weird"));
		assertEquals("b\nc", ContentBlocks.extractLines("a\r\nb\r\nc", 2, 3));
		assertEquals("", ContentBlocks.extractLines("a\nb", 5, 9));
		assertTrue(ContentBlocks.fileUrlFor(ABS).startsWith("file:///"));
	}

	@Test
	void fileSearchGlobAndRanking() {
		assertEquals("src/app.ts", FileSearch.sanitizeQuery("src\\app*.ts"));
		assertEquals("**/*", FileSearch.globForQuery(""));
		assertEquals("**/*app*", FileSearch.globForQuery("app"));
		assertEquals(3, FileSearch.scoreFileMatch("app", "src/app.ts"));
		assertEquals(2, FileSearch.scoreFileMatch("app", "src/myapp.ts"));
		assertEquals(1, FileSearch.scoreFileMatch("src", "src/index.ts"));
		assertEquals(0, FileSearch.scoreFileMatch("zzz", "src/index.ts"));
		List<String> sorted = FileSearch.sortByScore("app", List.of("lib/other.ts", "src/myapp.ts", "src/app.ts", "app/index.ts"),
				label -> label);
		assertEquals(List.of("src/app.ts", "src/myapp.ts", "app/index.ts", "lib/other.ts"), sorted);
	}

	// --- beyond the reference's cases: what its Node and VS Code helpers do, which is ported here ---

	@Test
	void fileUrlsAreWrittenAsNodeWritesThem() {
		assertEquals("file:///C:/ws/src/app.ts", ContentBlocks.fileUrlFor("C:\\ws\\src\\app.ts", Platform.WIN32));
		assertEquals("file:///C:/ws/My%20Shot%20%231%20100%25.png", ContentBlocks.fileUrlFor("C:\\ws\\My Shot #1 100%.png", Platform.WIN32));
		assertEquals("file:///C:/ws/%C3%BCber/a.ts", ContentBlocks.fileUrlFor("C:/ws/sub/../\u00fcber/a.ts", Platform.WIN32));
		assertEquals("file://server/share/dir/a.ts", ContentBlocks.fileUrlFor("\\\\server\\share\\dir\\a.ts", Platform.WIN32));
		assertEquals("file:///ws/My%20Shot.png", ContentBlocks.fileUrlFor("/ws/My Shot.png", Platform.POSIX));
		assertEquals("file:///ws/a%5Cb%3Fc.ts", ContentBlocks.fileUrlFor("/ws/a\\b?c.ts", Platform.POSIX));
	}

	@Test
	void theExtensionIsTheOneOfTheFileName() {
		assertNull(ContentBlocks.guessMimeType("/ws/.json"), "a dot file has no extension");
		assertNull(ContentBlocks.guessMimeType("/ws/dir.ts/readme"));
		assertEquals("text/x-java", ContentBlocks.guessMimeType("C:\\ws\\src\\App.JAVA"));
		assertEquals("sql", ContentBlocks.languageForPath("db/schema.sql"));
		assertNull(ContentBlocks.guessMimeType("db/schema.sql"), "sql has a fence language but no MIME type, as in the reference");
	}

	@Test
	void linesAreCutAsTheReferenceCutsThem() {
		assertEquals("a", ContentBlocks.extractLines("a\nb\nc", 1, 1));
		assertEquals("a\nb", ContentBlocks.extractLines("a\nb\nc", 2, 1), "a range given backwards");
		assertEquals("a\nb\nc", ContentBlocks.extractLines("a\nb\nc", 0, 99), "a range beyond the text");
		assertEquals("c\n", ContentBlocks.extractLines("a\nb\nc\n", 3, 4), "the empty last line counts");
	}

	@Test
	void theGlobOfAQueryMatchesWithoutRegardToCase() {
		assertTrue(FileSearch.matches("", "anything/at/all.txt"));
		assertTrue(FileSearch.matches("samp", "src/sample.ts"));
		assertTrue(FileSearch.matches("SAMP", "src\\Sample.ts"));
		assertTrue(FileSearch.matches("ample", "src/sample.ts"));
		assertFalse(FileSearch.matches("src", "src/sample.ts"), "a star does not cross a folder: the name must contain the query");
		assertTrue(FileSearch.matches("src/sam", "src/sample.ts"));
		assertTrue(FileSearch.matches("rc/sam", "a/src/sample.ts"));
		assertFalse(FileSearch.matches("src/ample", "src/sample.ts"), "after a folder the name must start with the rest");
		assertTrue(FileSearch.matches("a/b/c", "xa/b/cx.ts"));
		assertFalse(FileSearch.matches("a/b/c", "xa/bb/cx.ts"));
		assertFalse(FileSearch.matches("a/b/c", "b/c.ts"), "more folders asked for than the path has");
		assertTrue(FileSearch.matches("s*a[m]p", "sample.ts"), "glob characters are dropped from the query");
		assertEquals(150, FileSearch.searchLimit(30));
		assertEquals(50, FileSearch.searchLimit(5));
	}

	@Test
	void equalScoresAreOrderedByLengthThenAlphabeticallyThenAsGiven() {
		assertEquals(List.of("b.ts", "a/b.ts", "c/b.ts", "long/b.ts"),
				FileSearch.sortByScore("b", List.of("long/b.ts", "c/b.ts", "a/b.ts", "b.ts"), label -> label));
		assertEquals(List.of("a.md", "B.md", "c.md"), FileSearch.sortByScore("", List.of("c.md", "B.md", "a.md"), label -> label));
	}

	@Test
	void theEchoShowsTheImagesThenTheChipsWithTheLinesOfASelection() {
		String shot = NodePath.resolve("/ws/shot.png", null);
		List<JsonNode> echo = ContentBlocks.echoBlocks(List.of(FILE_CHIP, SEL_CHIP),
				List.of(new ImageAttachment("a", "shot.png", "image/png", "iVBORw0K", 6, shot, "data:image/webp;base64,AAAA")));
		assertEquals(List.of(json("{\"type\":\"image\",\"mimeType\":\"image/webp\",\"data\":\"AAAA\",\"uri\":" + quoted(url(shot)) + "}"),
				json("{\"type\":\"resource_link\",\"uri\":" + quoted(url(ABS)) + ",\"name\":\"src/app.ts\"}"),
				json("{\"type\":\"resource_link\",\"uri\":" + quoted(url(ABS)) + ",\"name\":\"src/app.ts:2-3\"}")), echo);
	}
}
