package io.github.liaoalia.kiroacp.core.session.context;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.liaoalia.kiroacp.core.model.ContextChip;
import io.github.liaoalia.kiroacp.core.pure.text.Platform;
import io.github.liaoalia.kiroacp.core.session.context.EditorContext.TextSelection;

/** The pieces of the context feature that need no controller: the chips, the walk through the workspace, dropped URIs. */
class ContextPiecesTest {

	@TempDir
	Path dir;

	private Path file(String relative) throws IOException {
		Path file = dir.resolve(relative);
		Files.createDirectories(file.getParent());
		return Files.write(file, new byte[0]);
	}

	private List<String> found(List<Path> files) {
		return files.stream().map(file -> dir.relativize(file).toString().replace('\\', '/')).toList();
	}

	// ---------------------------------------------------------------------
	// Chips (the reference's ContextTracker)
	// ---------------------------------------------------------------------

	private final AtomicBoolean attach = new AtomicBoolean(true);
	private final List<List<ContextChip>> fired = new ArrayList<>();
	private final ContextTracker tracker = new ContextTracker(file -> "label/" + file.getFileName(), attach::get, fired::add);

	@Test
	void theLinesOfASelection() {
		Path a = Path.of("a.ts");
		assertArrayEquals(new int[] { 3, 5 }, ContextTracker.selectionLines(new TextSelection(a, 2, 0, 4, 3)));
		assertArrayEquals(new int[] { 3, 5 }, ContextTracker.selectionLines(new TextSelection(a, 2, 5, 5, 0)),
				"a selection that ends at the start of a line does not include it");
		assertArrayEquals(new int[] { 3, 3 }, ContextTracker.selectionLines(new TextSelection(a, 2, 0, 3, 0)));
		assertArrayEquals(new int[] { 3, 3 }, ContextTracker.selectionLines(new TextSelection(a, 2, 1, 2, 4)));
		assertArrayEquals(new int[] { 8, 8 }, ContextTracker.selectionLines(new TextSelection(a, 7, 2, 7, 2)),
				"the caret's line when nothing is selected");
		assertArrayEquals(new int[] { 1, 1 }, ContextTracker.selectionLines(new TextSelection(a, 0, 0, 0, 0)));
	}

	@Test
	void theImplicitChipComesFirstFollowsTheEditorAndIsHiddenByTheSettingOrAnExplicitChipOfTheSameFile() {
		Path a = Path.of("ws", "a.ts").toAbsolutePath();
		Path b = Path.of("ws", "b.ts").toAbsolutePath();
		assertEquals(List.of(), tracker.chips());

		tracker.activeFileChanged(a);
		ContextChip implicit = new ContextChip("file:" + a, ContextChip.Kind.FILE, a.toString(), "label/a.ts", null, null, true);
		assertEquals(List.of(implicit), tracker.chips());
		assertEquals(List.of(List.of(implicit)), fired);

		// The same editor again, or something that is not an editor of a file: nothing changes, nothing is fired.
		tracker.activeFileChanged(a);
		tracker.activeFileChanged(null);
		assertEquals(1, fired.size());

		ContextChip selection = tracker.addSelectionChip(new TextSelection(b, 0, 0, 1, 2));
		assertEquals(new ContextChip("sel:" + b + ":1-2", ContextChip.Kind.SELECTION, b.toString(), "label/b.ts", 1, 2, false), selection);
		assertEquals(List.of(implicit, selection), tracker.chips(), "the implicit chip first");

		// The same file attached by hand: one chip for it, the explicit one.
		ContextChip explicit = tracker.addFileChip(a);
		assertFalse(explicit.implicit());
		assertEquals(implicit.id(), explicit.id());
		assertEquals(List.of(selection, explicit), tracker.chips());
		// Both have the same id. Its remove button takes the one on show, the explicit one, and the
		// implicit chip is on show again; the button on that one removes it too.
		tracker.removeChip(explicit.id());
		assertEquals(List.of(implicit, selection), tracker.chips());
		tracker.removeChip(implicit.id());
		assertEquals(List.of(selection), tracker.chips());
		tracker.addFileChip(a);

		tracker.clearExplicit();
		assertEquals(List.of(implicit), tracker.chips(), "after a prompt the explicit chips are gone and the removed implicit one is back");

		attach.set(false);
		assertEquals(List.of(), tracker.chips(), "the setting decides whether the active file is a chip at all");
		attach.set(true);

		tracker.removeChip(implicit.id());
		assertEquals(List.of(), tracker.chips());
		int before = fired.size();
		tracker.removeChip("file:unknown");
		assertEquals(before, fired.size(), "removing a chip that is not there says nothing");
		tracker.activeFileChanged(a);
		assertEquals(List.of(implicit), tracker.chips(), "activating the editor again brings its removed chip back");
		assertEquals(tracker.chips(), fired.get(fired.size() - 1), "every change fires the full list");

		assertNull(tracker.addSelectionChip(null), "no editor, no chip");
	}

	// ---------------------------------------------------------------------
	// The walk through the workspace
	// ---------------------------------------------------------------------

	@Test
	void theWalkFindsMatchingFilesNearestTheRootFirstAndSkipsWhatTheReferenceExcludes() throws IOException {
		file("deep/er/app-three.txt");
		file("app-one.txt");
		file("src/app-two.txt");
		file("src/other.txt");
		file("node_modules/pkg/app.js");
		file("src/node_modules/app.js");
		file(".git/app");
		file("dist/app.js");
		file("out/app.js");
		file("build/app.class");

		assertEquals(List.of("app-one.txt", "src/app-two.txt", "deep/er/app-three.txt", "build/app.class").stream().sorted().toList(),
				found(FileScan.find(List.of(dir), "app", 100, null, () -> false)).stream().sorted().toList());
		assertEquals(List.of("app-one.txt", "build/app.class", "src/app-two.txt", "deep/er/app-three.txt"),
				found(FileScan.find(List.of(dir), "APP", 100, null, () -> false)), "breadth first, by name within a folder");
		assertEquals(List.of("app-one.txt", "build/app.class"), found(FileScan.find(List.of(dir), "app", 2, null, () -> false)),
				"the limit ends the walk");
		assertEquals(List.of("src/app-two.txt"), found(FileScan.find(List.of(dir), "src/app", 100, null, () -> false)));

		// What the IDE knows to be build output is left out as well.
		Path build = dir.resolve("build");
		assertEquals(List.of("app-one.txt", "src/app-two.txt", "deep/er/app-three.txt"),
				found(FileScan.find(List.of(dir), "app", 100, build::equals, () -> false)));
		assertEquals(List.of("app-one.txt", "build/app.class", "src/app-two.txt", "deep/er/app-three.txt"),
				found(FileScan.find(List.of(dir), "app", 100, folder -> {
					throw new IllegalStateException("the resource tree is locked");
				}, () -> false)), "a failing exclusion excludes nothing");
	}

	@Test
	void aRootInsideAnotherIsSearchedOnceAndAMissingRootIsPassedOver() throws IOException {
		file("parent.txt");
		file("module/child.txt");
		Path module = dir.resolve("module");
		List<Path> roots = List.of(module, dir, dir.resolve("module").resolve("..").resolve("module"), dir.resolveSibling("no-such-root"));

		assertEquals(List.of(dir, dir.resolveSibling("no-such-root")), FileScan.outermost(roots));
		assertEquals(List.of("parent.txt", "module/child.txt"), found(FileScan.find(roots, "", 100, null, () -> false)),
				"no file is listed twice");
		assertEquals(List.of("module/child.txt"), found(FileScan.find(List.of(module), "", 100, null, () -> false)));
		assertEquals(List.of(), FileScan.find(List.of(), "", 100, null, () -> false));
	}

	@Test
	void aCancelledWalkStopsBeforeTheNextFolder() throws IOException {
		file("a.txt");
		file("one/b.txt");
		file("two/c.txt");
		AtomicInteger asked = new AtomicInteger();
		assertEquals(List.of("a.txt"), found(FileScan.find(List.of(dir), "", 100, null, () -> asked.incrementAndGet() > 1)),
				"the root was listed, then the walk was told to stop");
		assertEquals(List.of(), FileScan.find(List.of(dir), "", 100, null, () -> true));
	}

	// ---------------------------------------------------------------------
	// Dropped URIs
	// ---------------------------------------------------------------------

	@Test
	void aDroppedUriNeedsASchemeAndNothingElse() {
		for (String none : new String[] { null, "", "   ", "not a uri", "/just/a/path.png", "1ab:x", ":x", "src/app.ts" }) {
			assertNull(DroppedUri.parse(none), String.valueOf(none));
		}
		DroppedUri web = DroppedUri.parse("  HTTPS://example.com/pics/My%20Shot.PNG?size=2#top \n");
		assertNotNull(web);
		assertEquals("https", web.scheme());
		assertEquals("/pics/My Shot.PNG", web.path());
		assertEquals("My Shot.PNG", web.name());
		assertNull(web.file(), "only file: URIs name a local file");

		DroppedUri bare = DroppedUri.parse("untitled:Untitled-1");
		assertEquals("untitled", bare.scheme());
		assertEquals("Untitled-1", bare.name());
		assertEquals("file:///", DroppedUri.parse("file:///").name(), "without a last segment the URI itself is the name");
	}

	@Test
	void aFileUriNamesALocalFile() throws IOException {
		Path real = file("some dir/\u00fcber shot.png");
		DroppedUri uri = DroppedUri.parse(real.toUri().toString());
		assertEquals("file", uri.scheme());
		assertEquals(real, uri.file(), "what the JDK encodes is decoded again");
		assertEquals("\u00fcber shot.png", uri.name());
		assertTrue(Files.isRegularFile(uri.file()));

		// Written without escapes, as some drag sources do.
		String lenient = "file:///" + real.toString().replace('\\', '/').replaceFirst("^/", "");
		assertEquals(real, DroppedUri.parse(lenient).file());

		if (Platform.current() == Platform.WIN32) {
			assertEquals(Path.of("C:\\ws\\a.png"), DroppedUri.parse("file:///C:/ws/a.png").file());
			assertEquals(Path.of("C:\\ws\\a.png"), DroppedUri.parse("file:///c%3A/ws/sub/../a.png").file().toAbsolutePath(),
					"the form VS Code writes");
			assertEquals(Path.of("\\\\server\\share\\a.png"), DroppedUri.parse("file://server/share/a.png").file());
			assertNull(DroppedUri.parse("file:///home/me/a.png").file(), "no drive: not a file of this computer");
			assertNull(DroppedUri.parse("file:///C:/ws/a%3Fb.png").file(), "not a path Windows could have");
		} else {
			assertEquals(Path.of("/home/me/a b.png"), DroppedUri.parse("file:///home/me/a%20b.png").file());
			assertEquals(Path.of("/home/me/a.png"), DroppedUri.parse("file://localhost/home/me/a.png").file());
		}
	}
}
