package io.github.liaoalia.kiroacp.ui.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.filebuffers.ITextFileBufferManager;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.filesystem.IFileStore;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentListener;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.liaoalia.kiroacp.core.pure.text.Platform;
import io.github.liaoalia.kiroacp.core.services.FsService;

/**
 * Real file buffers over real projects of the test workspace. A buffer is what
 * an editor holds a file open with; connecting one here stands for opening the
 * file, without a workbench and without a window.
 */
class EclipseTextFilesTest {

	private static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(30);

	private final IWorkspace resources = ResourcesPlugin.getWorkspace();
	private final ITextFileBufferManager buffers = FileBuffers.getTextFileBufferManager();
	private final FakePrompts prompts = new FakePrompts();
	/** No display: documents are touched on the calling thread. */
	private final EclipseTextFiles files = new EclipseTextFiles(null, prompts, null);
	private final List<Runnable> disconnects = new ArrayList<>();
	private Display ownDisplay;

	@BeforeEach
	void emptyWorkspace() throws CoreException {
		deleteAllProjects();
	}

	@AfterEach
	void closeEverything() throws CoreException {
		disconnects.forEach(Runnable::run);
		deleteAllProjects();
		if (ownDisplay != null && !ownDisplay.isDisposed()) {
			ownDisplay.dispose();
		}
	}

	@Test
	void thereIsNoWorkbenchInThisRuntime() {
		assertFalse(PlatformUI.isWorkbenchRunning());
	}

	@Test
	void aReadPrefersTheUnsavedTextOfAnOpenFile() throws Exception {
		IFile file = file(project("alpha"), "notes.txt", "saved");
		ITextFileBuffer buffer = open(file);
		assertEquals("saved", files.read(path(file)), "open and unchanged");

		buffer.getDocument().set("typed but not saved");

		assertTrue(buffer.isDirty());
		assertEquals("typed but not saved", files.read(path(file)));
		assertEquals("saved", Files.readString(path(file)), "reading saves nothing");
	}

	@Test
	void aFileThatIsNotOpenIsReadFromDisk() throws Exception {
		IFile file = file(project("alpha"), "notes.txt", "on disk");
		// Changed behind the workspace's back: the read does not go through the resource.
		Files.writeString(path(file), "on disk, edited elsewhere");

		assertEquals("on disk, edited elsewhere", files.read(path(file)));
		assertThrows(IOException.class, () -> files.read(path(file).resolveSibling("missing.txt")));
	}

	@Test
	void aWriteToAnOpenFileReplacesTheDocumentAndSavesIt() throws Exception {
		IFile file = file(project("alpha"), "notes.txt", "one\ntwo\n");
		ITextFileBuffer buffer = open(file);
		List<DocumentEvent> edits = edits(buffer.getDocument());

		files.write(path(file), "one\nTWO\nthree\n");

		assertEquals("one\nTWO\nthree\n", buffer.getDocument().get(), "the editor shows the new text");
		assertFalse(buffer.isDirty(), "saved, not left dirty");
		assertEquals("one\nTWO\nthree\n", Files.readString(path(file)));
		assertTrue(file.isSynchronized(IResource.DEPTH_ZERO), "saved through the workspace");
		assertEquals(1, edits.size(), "one edit, so one step of the editor's undo");
		assertEquals(0, edits.get(0).getOffset());
		assertEquals("one\ntwo\n".length(), edits.get(0).getLength());
	}

	@Test
	void aWriteOverUnsavedEditsReplacesThemAndLeavesTheFileClean() throws Exception {
		IFile file = file(project("alpha"), "notes.txt", "saved");
		ITextFileBuffer buffer = open(file);
		buffer.getDocument().set("typed but not saved");

		files.write(path(file), "from the agent");

		assertEquals("from the agent", buffer.getDocument().get());
		assertFalse(buffer.isDirty());
		assertEquals("from the agent", Files.readString(path(file)));
	}

	@Test
	void writingTheTextTheEditorAlreadyShowsStillSavesIt() throws Exception {
		IFile file = file(project("alpha"), "notes.txt", "saved");
		ITextFileBuffer buffer = open(file);
		buffer.getDocument().set("typed but not saved");
		List<DocumentEvent> edits = edits(buffer.getDocument());

		files.write(path(file), "typed but not saved");

		assertEquals(List.of(), edits, "nothing to change in the document");
		assertFalse(buffer.isDirty());
		assertEquals("typed but not saved", Files.readString(path(file)));
	}

	@Test
	void aCleanBufferOverAFileChangedOnDiskDoesNotHideTheWrite() throws Exception {
		IFile file = file(project("alpha"), "notes.txt", "what the editor shows");
		ITextFileBuffer buffer = open(file);
		Files.writeString(path(file), "changed by another program, and longer");
		// The workspace tells by the time stamp, and the two writes may share a millisecond.
		Files.setLastModifiedTime(path(file), FileTime.fromMillis(System.currentTimeMillis() + 10_000));
		assertFalse(buffer.isDirty());
		assertFalse(buffer.isSynchronized());

		// The document already says this, so the buffer has nothing to save.
		files.write(path(file), "what the editor shows");

		assertEquals("what the editor shows", Files.readString(path(file)));
		assertTrue(file.isSynchronized(IResource.DEPTH_ZERO));
	}

	@Test
	void aWriteToAFileThatIsNotOpenReachesDiskAndTheWorkspaceKnows() throws Exception {
		IProject alpha = project("alpha");
		IFile existing = file(alpha, "notes.txt", "before");
		IFile created = alpha.getFile("new.txt");

		files.write(path(existing), "after, and longer than before");
		files.write(path(created), "brand new");

		assertEquals("after, and longer than before", Files.readString(path(existing)));
		assertTrue(existing.isSynchronized(IResource.DEPTH_ZERO), "an existing file is not left out of sync");
		assertEquals("after, and longer than before", contents(existing));
		assertTrue(created.exists(), "a new file appears in the workspace");
		assertEquals("brand new", contents(created));
	}

	@Test
	void foldersMadeForAFileAppearInTheWorkspaceToo() throws Exception {
		IProject alpha = project("alpha");
		IFile deep = alpha.getFile("src/main/deep/App.java");

		files.write(path(deep), "class App {}");

		assertEquals("class App {}", Files.readString(path(deep)));
		assertTrue(alpha.getFolder("src/main/deep").exists());
		assertTrue(deep.exists());
		assertTrue(deep.isSynchronized(IResource.DEPTH_ZERO));
	}

	@Test
	void aFileOutsideTheWorkspaceIsReadAndWrittenOnDisk(@TempDir Path outside) throws Exception {
		project("alpha");
		Path file = outside.resolve("sub").resolve("elsewhere.txt");

		files.write(file, "written outside");

		assertEquals("written outside", Files.readString(file));
		assertEquals("written outside", files.read(file));
	}

	@Test
	void aFileOutsideTheWorkspaceThatIsOpenGoesThroughItsBuffer(@TempDir Path outside) throws Exception {
		Path file = outside.resolve("elsewhere.txt");
		Files.writeString(file, "saved");
		IFileStore store = EFS.getLocalFileSystem().getStore(file.toUri());
		buffers.connectFileStore(store, null);
		disconnects.add(() -> {
			try {
				buffers.disconnectFileStore(store, null);
			} catch (CoreException e) {
				throw new IllegalStateException(e);
			}
		});
		ITextFileBuffer buffer = buffers.getFileStoreTextFileBuffer(store);
		buffer.getDocument().set("typed but not saved");

		assertEquals("typed but not saved", files.read(file));

		files.write(file, "from the agent");

		assertEquals("from the agent", buffer.getDocument().get());
		assertFalse(buffer.isDirty());
		assertEquals("from the agent", Files.readString(file));
	}

	@Test
	void onceTheFileIsClosedItIsWrittenOnDiskAgain() throws Exception {
		IFile file = file(project("alpha"), "notes.txt", "saved");
		open(file);
		disconnects.forEach(Runnable::run);
		disconnects.clear();
		assertNull(buffers.getTextFileBuffer(file.getFullPath(), LocationKind.IFILE));

		files.write(path(file), "after the editor closed");

		assertEquals("after the editor closed", Files.readString(path(file)));
		assertEquals("after the editor closed", files.read(path(file)));
		assertTrue(file.isSynchronized(IResource.DEPTH_ZERO));
	}

	@Test
	void onWindowsAPathInAnotherCaseStillFindsTheOpenFile() throws Exception {
		assumeTrue(Platform.current() == Platform.WIN32);
		IFile file = file(project("alpha"), "notes.txt", "saved");
		ITextFileBuffer buffer = open(file);
		buffer.getDocument().set("typed but not saved");
		String spelled = path(file).toString();
		Path other = Path.of(spelled.substring(0, 1).toLowerCase(Locale.ROOT) + spelled.substring(1).toUpperCase(Locale.ROOT));
		assertFalse(other.toString().equals(spelled));

		assertEquals("typed but not saved", files.read(other));
	}

	/**
	 * The arrangement of the product: the caller is a worker thread and another
	 * thread owns the display. The worker blocks on the UI thread, so this
	 * would hang if the hop could deadlock while the UI thread only runs its
	 * event loop.
	 */
	@Test
	void fromAWorkerThreadTheDocumentIsReadAndChangedOnTheUiThread() throws Exception {
		Display display = display();
		EclipseTextFiles onDisplay = new EclipseTextFiles(display, prompts, null);
		IFile file = file(project("alpha"), "notes.txt", "saved");
		IFile closed = file(file.getProject(), "closed.txt", "saved");
		ITextFileBuffer buffer = open(file);
		buffer.getDocument().set("typed but not saved");
		List<Thread> changedOn = new CopyOnWriteArrayList<>();
		buffer.getDocument().addDocumentListener(new IDocumentListener() {
			@Override
			public void documentAboutToBeChanged(DocumentEvent event) {
			}

			@Override
			public void documentChanged(DocumentEvent event) {
				changedOn.add(Thread.currentThread());
			}
		});

		CompletableFuture<List<String>> worked = new CompletableFuture<>();
		Thread worker = new Thread(() -> {
			try {
				String before = onDisplay.read(path(file));
				onDisplay.write(path(file), "from the agent");
				String after = onDisplay.read(path(file));
				// A file that is not open never needs the UI thread.
				onDisplay.write(path(closed), "on disk");
				worked.complete(List.of(before, after, onDisplay.read(path(closed))));
			} catch (Throwable e) {
				worked.completeExceptionally(e);
			} finally {
				display.wake();
			}
		}, "kiro-fs-test");
		worker.setDaemon(true);
		worker.start();

		long deadline = System.nanoTime() + TIMEOUT_NANOS;
		while (!worked.isDone()) {
			if (System.nanoTime() > deadline) {
				fail("The worker is stuck: " + List.of(worker.getStackTrace()));
			}
			if (!display.readAndDispatch()) {
				// Wake up again even if the worker's wake-up came before the sleep.
				display.timerExec(50, () -> {
				});
				display.sleep();
			}
		}

		assertEquals(List.of("typed but not saved", "from the agent", "on disk"), worked.get());
		assertEquals(List.of(display.getThread()), changedOn, "the document changed once, on the UI thread");
		assertEquals("from the agent", buffer.getDocument().get());
		assertFalse(buffer.isDirty());
		assertEquals("from the agent", Files.readString(path(file)));
	}

	@Test
	void onTheUiThreadItselfNothingHops() throws Exception {
		EclipseTextFiles onDisplay = new EclipseTextFiles(display(), prompts, null);
		IFile file = file(project("alpha"), "notes.txt", "saved");
		ITextFileBuffer buffer = open(file);

		onDisplay.write(path(file), "written on the UI thread");

		assertEquals("written on the UI thread", onDisplay.read(path(file)));
		assertFalse(buffer.isDirty());
	}

	@Test
	void aDisplayThatIsGoneIsNoObstacle() throws Exception {
		Display display = display();
		assumeTrue(ownDisplay != null, "the display belongs to this test");
		EclipseTextFiles onDisplay = new EclipseTextFiles(display, prompts, null);
		IFile file = file(project("alpha"), "notes.txt", "saved");
		ITextFileBuffer buffer = open(file);
		display.dispose();

		onDisplay.write(path(file), "written during shutdown");

		assertEquals("written during shutdown", buffer.getDocument().get());
		assertEquals("written during shutdown", Files.readString(path(file)));
	}

	@Test
	void aWriteOutsideTheWorkspaceIsAskedAboutWithoutWaiting() throws Exception {
		Path file = Path.of(System.getProperty("user.home"), "outside.txt");

		CompletableFuture<Boolean> allowed = files.confirmOutsideWorkspace(file).toCompletableFuture();

		assertFalse(allowed.isDone(), "the caller is not kept waiting for the user");
		assertEquals(1, prompts.asked.size());
		Prompts.Question question = prompts.asked.get(0).question();
		assertEquals(FsService.outsideWorkspaceQuestion(file), question.message());
		assertEquals("Write", question.accept());
		assertEquals("Deny", question.refuse());
		assertTrue(question.warning(), "refusing is the default");

		prompts.asked.get(0).answer().complete(Boolean.TRUE);
		assertEquals(Boolean.TRUE, allowed.get());

		CompletableFuture<Boolean> denied = files.confirmOutsideWorkspace(file).toCompletableFuture();
		prompts.asked.get(1).answer().complete(Boolean.FALSE);
		assertEquals(Boolean.FALSE, denied.get());
	}

	// ---------------------------------------------------------------------

	private Display display() {
		Display current = Display.getCurrent();
		if (current == null) {
			ownDisplay = new Display();
			current = ownDisplay;
		}
		return current;
	}

	/** Opens the file as an editor would. */
	private ITextFileBuffer open(IFile file) throws CoreException {
		buffers.connect(file.getFullPath(), LocationKind.IFILE, null);
		disconnects.add(() -> {
			try {
				buffers.disconnect(file.getFullPath(), LocationKind.IFILE, null);
			} catch (CoreException e) {
				throw new IllegalStateException(e);
			}
		});
		return buffers.getTextFileBuffer(file.getFullPath(), LocationKind.IFILE);
	}

	private static List<DocumentEvent> edits(IDocument document) {
		List<DocumentEvent> edits = new ArrayList<>();
		document.addDocumentListener(new IDocumentListener() {
			@Override
			public void documentAboutToBeChanged(DocumentEvent event) {
			}

			@Override
			public void documentChanged(DocumentEvent event) {
				edits.add(event);
			}
		});
		return edits;
	}

	private IProject project(String name) throws CoreException {
		IProject project = resources.getRoot().getProject(name);
		project.create(null);
		project.open(null);
		return project;
	}

	private static IFile file(IProject project, String name, String content) throws CoreException {
		IFile file = project.getFile(name);
		file.create(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), true, null);
		return file;
	}

	private static Path path(IFile file) {
		return file.getLocation().toFile().toPath().toAbsolutePath().normalize();
	}

	private static String contents(IFile file) throws CoreException, IOException {
		// Fails when the resource is out of sync with the file system.
		try (var in = file.getContents(false)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private void deleteAllProjects() throws CoreException {
		for (IProject project : resources.getRoot().getProjects()) {
			project.delete(true, true, null);
		}
	}
}
