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

	/** An install on a network share is not supported: no spelling of its URL is the page. */
	@Test
	void aPageOnAShareIsNeverThePage() {
		assumeTrue(windows(), "UNC paths exist on Windows only");
		for (String server : new String[] { "server", "file_srv", "10.0.0.5" }) {
			Path shared = Path.of("\\\\" + server + "\\share\\eclipse\\web\\index.html");
			assertFalse(ChatPageLocation.isLocal(shared), server);
			for (String location : new String[] {
					"file://" + server + "/share/eclipse/web/index.html",
					"file://" + server + "/share/eclipse/web/index.html?view=x",
					"file:////" + server + "/share/eclipse/web/index.html",
					"file://///" + server + "/share/eclipse/web/index.html",
					"file:///%5C%5C" + server + "%5Cshare%5Ceclipse%5Cweb%5Cindex.html" }) {
				assertFalse(ChatPageLocation.matches(shared, location), location);
			}
		}
	}

	@Test
	void aLocalPageIsLocal() {
		assertTrue(ChatPageLocation.isLocal(page));
		assertFalse(ChatPageLocation.isLocal(null));
	}

	/**
	 * An authority java.net.URI cannot parse as a host name leaves getHost() null;
	 * it still names a machine, even when the rest of the URL is the page's own path.
	 */
	@Test
	void anAuthorityThatIsNotAHostNameIsStillAnotherMachine() {
		String path = page.toUri().getRawPath();
		for (String authority : new String[] { "file_srv", "evil_host", "a_b.example", "user@host", "host:8080",
				"LOCALHOST.evil", "localhost.", "127.0.0.1", "[::1]" }) {
			assertFalse(ChatPageLocation.matches(page, "file://" + authority + path), authority);
		}
	}

	@Test
	void localhostIsThisMachine() {
		String path = page.toUri().getRawPath();
		assertTrue(ChatPageLocation.matches(page, "file://localhost" + path));
		assertTrue(ChatPageLocation.matches(page, "file://LOCALHOST" + path + "?view=1"));
	}

	@Test
	void aLocalPageDoesNotMatchAShareUrl() {
		String path = page.toUri().getRawPath();
		assertFalse(ChatPageLocation.matches(page, "file://server" + path));
		assertFalse(ChatPageLocation.matches(page, "file:////server/share/web/index.html"));
	}

	/**
	 * Spellings of a path on another machine that carry no host and no leading
	 * double slash. They must be plain mismatches; the class has no way to look
	 * a path up, so none of them can make the host contact that machine.
	 */
	@Test
	void encodedSharePathsAreNotThePage() {
		for (String location : new String[] {
				"file:///%5C%5Cattacker%5Cshare%5Cx.html",
				"file:/%5C%5Cattacker%5Cshare/x.html",
				"file:///%5C/attacker/share/x.html",
				"file://localhost/%5C%5Cattacker%5Cshare%5Cx.html",
				"file:///%2F%2Fattacker/share/x.html",
				"file://attacker/share/x.html",
				"file:////attacker/share/x.html",
				"file://///attacker/share/x.html" }) {
			assertFalse(ChatPageLocation.matches(page, location), location);
		}
	}

	@Test
	void theSourceNeverConsultsTheFileSystem() throws IOException {
		// A guard on the property the class documents: matching is name comparison only.
		Path source = Path.of(System.getProperty("kiroacp.ui.src", ""), "io", "github", "liaoalia", "kiroacp", "ui",
				"chat", "ChatPageLocation.java");
		assumeTrue(Files.isRegularFile(source), "source tree not available: " + source);
		String code = Files.readString(source);
		for (String forbidden : new String[] { "java.nio.file.Files", "java.io.File", "toRealPath", "isSameFile",
				".exists(" }) {
			assertFalse(code.contains(forbidden), forbidden);
		}
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
