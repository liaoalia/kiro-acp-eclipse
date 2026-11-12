package io.github.liaoalia.kiroacp.ui.chat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChatPageLocationTest {

	@TempDir
	Path dir;

	private Path page;

	@BeforeEach
	void createPage() throws IOException {
		Path web = Files.createDirectories(dir.resolve("with space").resolve("web"));
		page = Files.writeString(web.resolve("index.html"), "<html></html>");
		Files.writeString(web.resolve("bridge.js"), "");
		Files.writeString(dir.resolve("index.html"), "<html></html>");
	}

	/** {@code file:///C:/...} as browsers report it. */
	private String url(Path file) {
		return file.toUri().toString();
	}

	@Test
	void thePageItself() {
		assertTrue(ChatPageLocation.matches(page, url(page)));
	}

	@Test
	void javaStyleSingleSlashForm() {
		// java.io.File.toURI() prints file:/C:/..., browsers print file:///C:/...
		String single = page.toFile().toURI().toString();
		assertTrue(single.startsWith("file:/") && !single.startsWith("file://"), single);
		assertTrue(ChatPageLocation.matches(page, single));
		assertTrue(ChatPageLocation.matches(page, single.replaceFirst("^file:/", "file:///")));
	}

	@Test
	void queryAndFragmentAreIgnored() {
		assertTrue(ChatPageLocation.matches(page, url(page) + "?view=main"));
		assertTrue(ChatPageLocation.matches(page, url(page) + "#top"));
		assertTrue(ChatPageLocation.matches(page, url(page) + "?view=a%20b#x"));
	}

	@Test
	void dotSegmentsResolveToThePage() {
		String viaParent = url(page).replace("/web/index.html", "/web/../web/index.html");
		assertTrue(ChatPageLocation.matches(page, viaParent));
	}

	@Test
	void anotherLocalFileIsNotThePage() {
		assertFalse(ChatPageLocation.matches(page, url(page.resolveSibling("bridge.js"))));
		assertFalse(ChatPageLocation.matches(page, url(dir.resolve("index.html"))));
		assertFalse(ChatPageLocation.matches(page, url(page.resolveSibling("missing.html"))));
		assertFalse(ChatPageLocation.matches(page, url(page.getParent())));
	}

	@Test
	void remoteAndOtherSchemesAreNotThePage() {
		String path = page.toUri().getRawPath();
		assertFalse(ChatPageLocation.matches(page, "https://example.com" + path));
		assertFalse(ChatPageLocation.matches(page, "http://localhost" + path));
		assertFalse(ChatPageLocation.matches(page, "about:blank"));
		assertFalse(ChatPageLocation.matches(page, "data:text/html,<p>x</p>"));
		assertFalse(ChatPageLocation.matches(page, "javascript:void(0)"));
	}

	@Test
	void aFileUrlNamingAnotherHostIsNotThePage() {
		String path = page.toUri().getRawPath();
		assertFalse(ChatPageLocation.matches(page, "file://evil.example" + path));
	}

	private static boolean windows() {
		return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
	}

	/** An install on a network share: both spellings of the page's own URL are the page. */
	@Test
	void aPageOnAShareMatchesItsOwnUrl() {
		assumeTrue(windows(), "UNC paths exist on Windows only");
		Path shared = Path.of("\\\\server\\share\\eclipse\\web\\index.html");
		// As WebView2 reports it, and as java.io.File.toURI() prints it.
		assertTrue(ChatPageLocation.matches(shared, "file://server/share/eclipse/web/index.html"));
		assertTrue(ChatPageLocation.matches(shared, "file://server/share/eclipse/web/index.html?view=x"));
		assertTrue(ChatPageLocation.matches(shared, "file:////server/share/eclipse/web/index.html"));
		assertTrue(ChatPageLocation.matches(shared, shared.toFile().toURI().toString()));
		assertTrue(ChatPageLocation.matches(shared, shared.toUri().toString()));
	}

	@Test
	void aPageOnAShareDoesNotMatchAnotherHostShareOrFile() {
		assumeTrue(windows(), "UNC paths exist on Windows only");
		Path shared = Path.of("\\\\server\\share\\eclipse\\web\\index.html");
		assertFalse(ChatPageLocation.matches(shared, "file://other/share/eclipse/web/index.html"));
		assertFalse(ChatPageLocation.matches(shared, "file://server/other/eclipse/web/index.html"));
		assertFalse(ChatPageLocation.matches(shared, "file://server/share/eclipse/web/bridge.js"));
		assertFalse(ChatPageLocation.matches(shared, "file:////other/share/eclipse/web/index.html"));
		assertFalse(ChatPageLocation.matches(shared, "file:///share/eclipse/web/index.html"));
	}

	@Test
	void aLocalPageDoesNotMatchAShareUrl() {
		String path = page.toUri().getRawPath();
		assertFalse(ChatPageLocation.matches(page, "file://server" + path));
		assertFalse(ChatPageLocation.matches(page, "file:////server/share/web/index.html"));
	}

	@Test
	void garbageIsNotThePage() {
		assertFalse(ChatPageLocation.matches(page, null));
		assertFalse(ChatPageLocation.matches(page, ""));
		assertFalse(ChatPageLocation.matches(page, "   "));
		assertFalse(ChatPageLocation.matches(page, "not a url"));
		assertFalse(ChatPageLocation.matches(page, "file:"));
		assertFalse(ChatPageLocation.matches(page, "file:///"));
		assertFalse(ChatPageLocation.matches(page, "file:///%ZZ"));
	}

	@Test
	void noPageMeansNothingMatches() {
		assertFalse(ChatPageLocation.matches(null, url(page)));
	}
}
