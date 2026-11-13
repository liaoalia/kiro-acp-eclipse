package io.github.liaoalia.kiroacp.ui.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.bindings.keys.KeySequence;
import org.eclipse.jface.bindings.keys.ParseException;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.TextSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IPersistableElement;
import org.eclipse.ui.IURIEditorInput;
import org.eclipse.ui.PlatformUI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.liaoalia.kiroacp.core.host.ChatPage;
import io.github.liaoalia.kiroacp.core.model.FileSuggestion;
import io.github.liaoalia.kiroacp.core.model.FromWebview;
import io.github.liaoalia.kiroacp.core.model.ToWebview;
import io.github.liaoalia.kiroacp.core.services.DiskTextFiles;
import io.github.liaoalia.kiroacp.core.session.AttachOptions;
import io.github.liaoalia.kiroacp.core.session.ChatActions;
import io.github.liaoalia.kiroacp.core.session.ChatController;
import io.github.liaoalia.kiroacp.core.session.ChatHost;
import io.github.liaoalia.kiroacp.core.session.PageHandle;
import io.github.liaoalia.kiroacp.core.session.context.ChatContext;
import io.github.liaoalia.kiroacp.core.session.context.EditorContext;
import io.github.liaoalia.kiroacp.core.session.context.FileScan;
import io.github.liaoalia.kiroacp.ui.services.DisplayScheduler;
import io.github.liaoalia.kiroacp.ui.services.EclipseLogger;
import io.github.liaoalia.kiroacp.ui.services.EclipseWorkspace;
import io.github.liaoalia.kiroacp.ui.services.PreferenceSettings;

/**
 * The Eclipse side of the context feature in the test runtime: real
 * projects, the real display thread and the plug-in's own registrations.
 * There is no workbench, so no editor is ever active and no dialog is shown;
 * the agent is never started.
 */
class EclipseContextTest {

	private static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(30);
	private static final String SEND_SELECTION = "io.github.liaoalia.kiroacp.command.sendSelection";

	@TempDir
	Path temp;

	private final IWorkspace resources = ResourcesPlugin.getWorkspace();
	private final List<ToWebview> posted = new CopyOnWriteArrayList<>();
	private Display display;
	private boolean ownDisplay;
	private EclipseWorkspace workspace;
	private ChatController controller;

	@BeforeEach
	void createDisplay() throws CoreException {
		deleteAllProjects();
		display = Display.getCurrent();
		if (display == null) {
			display = new Display();
			ownDisplay = true;
		}
	}

	@AfterEach
	void close() throws CoreException {
		try {
			if (controller != null) {
				CompletableFuture<Void> down = controller.shutdown();
				await(down::isDone, "the controller to shut down");
			}
			if (workspace != null) {
				workspace.dispose();
			}
		} finally {
			if (ownDisplay && !display.isDisposed()) {
				display.dispose();
			}
			// Before the temporary folder is removed under them.
			deleteAllProjects();
		}
	}

	private void deleteAllProjects() throws CoreException {
		for (IProject project : resources.getRoot().getProjects()) {
			project.delete(false, true, null);
		}
	}

	private IProject projectAt(String name, Path folder) throws CoreException {
		IProject project = resources.getRoot().getProject(name);
		IProjectDescription description = resources.newProjectDescription(name);
		description.setLocationURI(folder.toUri());
		project.create(description, null);
		project.open(null);
		return project;
	}

	private static Path write(Path file) throws IOException {
		Files.createDirectories(file.getParent());
		return Files.write(file, new byte[0]);
	}

	/** A controller on the Eclipse services with the feature installed as the plug-in installs it, and one page. */
	private PageHandle chat() {
		assertFalse(PlatformUI.isWorkbenchRunning(), "these tests are written for a runtime without a workbench");
		PreferenceSettings settings = new PreferenceSettings();
		workspace = new EclipseWorkspace();
		workspace.start();
		ChatActions actions = new ChatActions() {
			@Override
			public void showLog() {
			}

			@Override
			public void openFile(String path, Integer line) {
			}
		};
		ChatHost host = new ChatHost(settings, workspace, new EclipseLogger(settings), new DisplayScheduler(display), new DiskTextFiles(),
				null, null, actions, "0.0.0-test");
		controller = ChatController.create(host, (options, services) -> {
			throw new AssertionError("no agent is started in these tests");
		});
		KiroContext.install(controller, host, display);
		ChatPage page = posted::add;
		return controller.attach(page, AttachOptions.ownGroup("context-test"));
	}

	private <T extends ToWebview> List<T> posted(Class<T> type) {
		return posted.stream().filter(type::isInstance).map(type::cast).toList();
	}

	/** Runs the event loop until the condition holds or the timeout passes. */
	private void await(BooleanSupplier condition, String what) {
		long deadline = System.nanoTime() + TIMEOUT_NANOS;
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > deadline) {
				fail("Timed out waiting for " + what + "; posted: " + posted);
			}
			if (!display.readAndDispatch()) {
				// Wake up again even when nothing else is due.
				display.timerExec(50, () -> {
				});
				display.sleep();
			}
		}
	}

	// ---------------------------------------------------------------------
	// The @ search in a workspace of projects
	// ---------------------------------------------------------------------

	@Test
	void theSearchListsEachFileOfNestedProjectsOnceAndLeavesOutWhatEclipseKnowsToBeBuildOutput() throws Exception {
		// The temp folder may be spelled with a short (8.3) name; Eclipse reports locations with the long one.
		Path outerFolder = temp.toRealPath().resolve("outer");
		Path moduleFolder = outerFolder.resolve("module");
		Path main = write(outerFolder.resolve("app-main.txt"));
		Path inner = write(moduleFolder.resolve("src").resolve("app-inner.txt"));
		write(moduleFolder.resolve("bin").resolve("app-inner.class"));
		write(outerFolder.resolve("target").resolve("app.jar"));
		write(outerFolder.resolve(".vcs").resolve("app-index"));
		write(outerFolder.resolve("node_modules").resolve("app.js"));
		IProject outer = projectAt("outer", outerFolder);
		// A module of a multi-module build: a project of its own inside its parent's folder.
		IProject module = projectAt("module", moduleFolder);
		outer.refreshLocal(IResource.DEPTH_INFINITE, null);
		module.refreshLocal(IResource.DEPTH_INFINITE, null);
		module.getFolder("bin").setDerived(true, null);
		outer.getFolder("target").setDerived(true, null);
		outer.getFolder(".vcs").setTeamPrivateMember(true);
		// Made behind Eclipse's back, as the agent's commands make files: the resource tree has not heard of it.
		Path fresh = write(outerFolder.resolve("fresh").resolve("app-fresh.txt"));
		assertFalse(outer.getFolder("fresh").exists());

		ResourceExclusions exclusions = new ResourceExclusions();
		assertTrue(exclusions.test(moduleFolder.resolve("bin")), "derived in the inner project");
		assertTrue(exclusions.test(outerFolder.resolve("target")), "derived");
		assertTrue(exclusions.test(outerFolder.resolve(".vcs")), "team-private");
		assertFalse(exclusions.test(moduleFolder.resolve("src")));
		assertFalse(exclusions.test(moduleFolder));
		assertFalse(exclusions.test(outerFolder.resolve("fresh")), "unknown to the resource tree");
		assertFalse(exclusions.test(temp.toRealPath().resolve("elsewhere")), "outside every project");

		PageHandle page = chat();
		assertEquals(List.of(main, fresh, inner),
				FileScan.find(workspace.roots(), "app", 100, exclusions, () -> false), "each file once, the nearest first");

		page.onMessage(new FromWebview.QueryFiles("app"));
		assertEquals(List.of(), posted(ToWebview.FileSuggestions.class), "the search does not run on the UI thread");
		await(() -> !posted(ToWebview.FileSuggestions.class).isEmpty(), "the file suggestions");

		ToWebview.FileSuggestions answer = posted(ToWebview.FileSuggestions.class).get(0);
		assertEquals("app", answer.query());
		assertEquals(List.of(new FileSuggestion(main.toString(), "outer/app-main.txt"),
				// The file of the nested project goes by the name of the project it belongs to.
				new FileSuggestion(inner.toString(), "module/src/app-inner.txt"),
				new FileSuggestion(fresh.toString(), "outer/fresh/app-fresh.txt")), answer.items());
	}

	// ---------------------------------------------------------------------
	// Without a workbench, and without an agent
	// ---------------------------------------------------------------------

	@Test
	void withoutAWorkbenchThereIsNoEditorAndWithoutAnAgentNoImageIsPicked() {
		PageHandle page = chat();
		ChatContext context = KiroContext.of(controller);
		assertNotNull(context, "the commands find the feature of the controller");

		WorkbenchEditorContext editor = new WorkbenchEditorContext();
		editor.start();
		assertNull(editor.activeFile());
		assertNull(editor.selection());
		editor.dispose();

		assertFalse(context.addSelectionChip(), "Send Selection has nothing to send");
		assertEquals(List.of(), context.chips());
		assertEquals(List.of(), posted, "and says nothing to the pages");

		// The attach button before the agent has said that it takes images: no dialog, a notice.
		page.onMessage(new FromWebview.PickImages());
		await(() -> !posted(ToWebview.Notice.class).isEmpty(), "the notice");
		assertEquals("Kiro does not accept images.", posted(ToWebview.Notice.class).get(0).notice().text());
		assertEquals(List.of(), posted(ToWebview.ImagesAttached.class));
	}

	// ---------------------------------------------------------------------
	// The command and its key
	// ---------------------------------------------------------------------

	@Test
	void sendSelectionIsACommandWithAHandlerAndCtrlAltKInTextEditors() throws Exception {
		IConfigurationElement command = element("org.eclipse.ui.commands", "command", "id", SEND_SELECTION);
		assertEquals("Send Selection to Kiro Chat", command.getAttribute("name"));

		IConfigurationElement handler = element("org.eclipse.ui.handlers", "handler", "commandId", SEND_SELECTION);
		assertTrue(handler.createExecutableExtension("class") instanceof SendSelectionHandler);

		IConfigurationElement key = element("org.eclipse.ui.bindings", "key", "commandId", SEND_SELECTION);
		assertEquals(KeySequence.getInstance("CTRL+ALT+K"), KeySequence.getInstance(key.getAttribute("sequence")));
		assertEquals("org.eclipse.ui.textEditorScope", key.getAttribute("contextId"));
		assertEquals("org.eclipse.ui.defaultAcceleratorConfiguration", key.getAttribute("schemeId"));
	}

	@Test
	void noOtherPlugInOfTheRuntimeBindsTheSameKey() throws Exception {
		KeySequence ours = KeySequence.getInstance("M1+M3+K");
		List<String> others = new ArrayList<>();
		int seen = 0;
		for (IConfigurationElement key : Platform.getExtensionRegistry().getConfigurationElementsFor("org.eclipse.ui.bindings")) {
			String sequence = key.getAttribute("sequence");
			String platform = key.getAttribute("platform");
			if (!"key".equals(key.getName()) || sequence == null || (platform != null && !platform.contains(SWT.getPlatform()))) {
				continue;
			}
			seen++;
			KeySequence parsed;
			try {
				parsed = KeySequence.getInstance(sequence);
			} catch (ParseException e) {
				continue;
			}
			// A binding that starts with the same stroke takes it as well.
			if (parsed.startsWith(ours, true) && !SEND_SELECTION.equals(key.getAttribute("commandId"))) {
				others.add(key.getContributor().getName() + ": " + sequence + " -> " + key.getAttribute("commandId") + " in "
						+ key.getAttribute("contextId"));
			}
		}
		assertTrue(seen > 50, "the bindings of the platform were looked at: " + seen);
		assertEquals(List.of(), others);
	}

	private static IConfigurationElement element(String point, String name, String attribute, String value) {
		for (IConfigurationElement element : Platform.getExtensionRegistry().getConfigurationElementsFor(point)) {
			if (name.equals(element.getName()) && value.equals(element.getAttribute(attribute))) {
				return element;
			}
		}
		return fail("no " + name + " with " + attribute + " " + value + " at " + point);
	}

	// ---------------------------------------------------------------------
	// What an editor shows and what is selected in it
	// ---------------------------------------------------------------------

	@Test
	void theFileOfAnEditorIsItsWorkspaceFileOrALocalFileOpenedFromOutside() throws Exception {
		Path folder = temp.toRealPath().resolve("project");
		Path file = write(folder.resolve("src").resolve("App.java"));
		IProject project = projectAt("project", folder);
		project.refreshLocal(IResource.DEPTH_INFINITE, null);

		assertEquals(file, WorkbenchEditorContext.fileOf(new Input(project.getFile("src/App.java"), null)));
		assertEquals(file, WorkbenchEditorContext.fileOf(new Input(null, file.toUri())), "a file opened with File > Open File");
		assertNull(WorkbenchEditorContext.fileOf(new Input(null, URI.create("jar:file:/lib/rt.jar!/java/lang/Object.class"))));
		assertNull(WorkbenchEditorContext.fileOf(new Input(null, URI.create("sftp://host/work/App.java"))));
		assertNull(WorkbenchEditorContext.fileOf(new Input(null, null)), "a compare editor, a welcome page");
		assertNull(WorkbenchEditorContext.fileOf(null));
	}

	@Test
	void aTextSelectionIsReadInTheLinesAndColumnsOfItsDocument() {
		Path file = Path.of("App.java").toAbsolutePath();
		Document document = new Document("line1\r\nline2\nline3\nline4\n");
		// "ne2\nline3\nlin": from line 1 column 2 into line 3.
		assertEquals(new EditorContext.TextSelection(file, 1, 2, 3, 3),
				WorkbenchEditorContext.selectionOf(file, new TextSelection(document, 9, 13), document));
		// Whole lines 2 and 3, line break included: ends at column 0 of line 4.
		assertEquals(new EditorContext.TextSelection(file, 1, 0, 3, 0),
				WorkbenchEditorContext.selectionOf(file, new TextSelection(document, 7, 12), document));
		// A caret.
		EditorContext.TextSelection caret = WorkbenchEditorContext.selectionOf(file, new TextSelection(document, 15, 0), document);
		assertEquals(new EditorContext.TextSelection(file, 2, 2, 2, 2), caret);
		assertTrue(caret.isEmpty());
		// The very end of the document.
		assertEquals(new EditorContext.TextSelection(file, 4, 0, 4, 0),
				WorkbenchEditorContext.selectionOf(file, new TextSelection(document, document.getLength(), 0), document));

		// Without the document, or past its end, the selection's own lines are all there is.
		assertEquals(new EditorContext.TextSelection(file, 1, 0, 3, 1),
				WorkbenchEditorContext.selectionOf(file, new TextSelection(document, 9, 13), null));
		assertEquals(new EditorContext.TextSelection(file, 2, 0, 2, 0),
				WorkbenchEditorContext.selectionOf(file, new TextSelection(document, 15, 0), null));
		assertNull(WorkbenchEditorContext.selectionOf(file, TextSelection.emptySelection(), document), "no selection at all");
		assertNull(WorkbenchEditorContext.selectionOf(file, new TextSelection(500, 3), document), "a selection the document does not have");
	}

	@Test
	void theFileDialogOffersTheImageTypesAndNamesTheChosenFilesInFull() {
		assertEquals("Images (*.png, *.jpg)", ImageFileDialog.filterName(List.of("png", "jpg")));
		assertEquals("*.png;*.jpg;*.webp", ImageFileDialog.filterPattern(List.of("png", "jpg", "webp")));

		Path folder = temp.resolve("pics");
		assertEquals(List.of(folder.resolve("a.png"), folder.resolve("b c.jpg")),
				ImageFileDialog.files(folder.toString(), new String[] { "a.png", "b c.jpg" }));
		Path absolute = temp.resolve("elsewhere").resolve("d.gif");
		assertEquals(List.of(absolute), ImageFileDialog.files(folder.toString(), new String[] { absolute.toString() }));
		assertEquals(List.of(), ImageFileDialog.files(folder.toString(), null));
	}

	/** An editor input as the workbench has them: one that adapts to its workspace file, or one that names a URI. */
	private record Input(IFile file, URI uri) implements IURIEditorInput {

		@Override
		public <T> T getAdapter(Class<T> adapter) {
			return adapter == IFile.class || adapter == IResource.class ? adapter.cast(file) : null;
		}

		@Override
		public URI getURI() {
			return uri;
		}

		@Override
		public boolean exists() {
			return true;
		}

		@Override
		public ImageDescriptor getImageDescriptor() {
			return null;
		}

		@Override
		public String getName() {
			return "input";
		}

		@Override
		public IPersistableElement getPersistable() {
			return null;
		}

		@Override
		public String getToolTipText() {
			return "input";
		}
	}
}
