package io.github.liaoalia.kiroacp.ui.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.text.TextSelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IPersistableElement;
import org.eclipse.ui.PlatformUI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real projects in the test runtime's workspace; there is no workbench. */
class EclipseWorkspaceTest {

	private final IWorkspace resources = ResourcesPlugin.getWorkspace();
	private final EclipseWorkspace workspace = new EclipseWorkspace();

	@BeforeEach
	@AfterEach
	void deleteAllProjects() throws CoreException {
		for (IProject project : resources.getRoot().getProjects()) {
			project.delete(true, true, null);
		}
	}

	@Test
	void theRootsAreTheLocationsOfTheOpenProjects() throws CoreException {
		IProject alpha = project("alpha");
		IProject beta = project("beta");
		IProject closed = project("closed");
		closed.close(null);

		assertEquals(List.of(location(alpha), location(beta)), workspace.roots());

		closed.open(null);
		assertEquals(List.of(location(alpha), location(beta), location(closed)), workspace.roots());
	}

	@Test
	void aWorkspaceWithoutProjectsHasNoRoots() {
		assertEquals(List.of(), workspace.roots());
		assertFalse(workspace.contains(Path.of(System.getProperty("user.home"))));
	}

	@Test
	void containsMeansInsideARoot() throws CoreException {
		IProject alpha = project("alpha");
		Path root = location(alpha);

		assertTrue(workspace.contains(root));
		assertTrue(workspace.contains(root.resolve("src/app.ts")));
		assertTrue(workspace.contains(root.resolve("src/../not/there/yet.txt")));
		assertFalse(workspace.contains(root.getParent()));
		assertFalse(workspace.contains(root.resolve("../alpha-sibling/app.ts")));
		// A folder whose name merely starts like the project's is not inside it.
		assertFalse(workspace.contains(root.resolveSibling("alphabet").resolve("app.ts")));
	}

	@Test
	void aClosedProjectContainsNothing() throws CoreException {
		IProject alpha = project("alpha");
		Path file = location(alpha).resolve("a.txt");
		alpha.close(null);

		assertFalse(workspace.contains(file));
		assertEquals("a.txt", workspace.label(file));
	}

	@Test
	void theLabelIsTheProjectNameAndThePathInsideIt() throws CoreException {
		IProject alpha = project("alpha");
		Path root = location(alpha);

		assertEquals("alpha/src/app.ts", workspace.label(root.resolve("src").resolve("app.ts")));
		assertEquals("alpha/README.md", workspace.label(root.resolve("docs/../README.md")));
		assertEquals("alpha", workspace.label(root));
	}

	@Test
	void outsideEveryRootTheLabelIsTheFileName() throws CoreException {
		project("alpha");

		assertEquals("notes.txt", workspace.label(Path.of(System.getProperty("user.home"), "notes.txt")));
	}

	@Test
	void aProjectKeptElsewhereIsLabelledByItsNameNotItsFolder(@TempDir Path temp) throws CoreException, IOException {
		// The temp folder may be spelled with a short (8.3) name; Eclipse reports locations with the long one.
		Path elsewhere = temp.toRealPath();
		Path folder = elsewhere.resolve("checkout-folder");
		IProject linked = resources.getRoot().getProject("named");
		IProjectDescription description = resources.newProjectDescription("named");
		description.setLocationURI(folder.toUri());
		linked.create(description, null);
		linked.open(null);
		try {
			assertEquals(List.of(folder.toAbsolutePath().normalize()), workspace.roots());
			assertTrue(workspace.contains(folder.resolve("src/Main.java")));
			assertEquals("named/src/Main.java", workspace.label(folder.resolve("src/Main.java")));
		} finally {
			// Before the temporary folder is removed under it.
			linked.delete(true, true, null);
		}
	}

	@Test
	void aPathInANestedProjectBelongsToTheInnerOne(@TempDir Path temp) throws CoreException, IOException {
		// The temp folder may be spelled with a short (8.3) name; Eclipse reports locations with the long one.
		Path elsewhere = temp.toRealPath();
		IProject outer = resources.getRoot().getProject("outer");
		IProjectDescription outerDescription = resources.newProjectDescription("outer");
		outerDescription.setLocationURI(elsewhere.resolve("outer").toUri());
		outer.create(outerDescription, null);
		outer.open(null);
		IProject inner = resources.getRoot().getProject("inner");
		IProjectDescription innerDescription = resources.newProjectDescription("inner");
		innerDescription.setLocationURI(elsewhere.resolve("outer/modules/inner").toUri());
		inner.create(innerDescription, null);
		inner.open(null);
		try {
			assertEquals("inner/src/a.txt", workspace.label(elsewhere.resolve("outer/modules/inner/src/a.txt")));
			assertEquals("outer/modules/b.txt", workspace.label(elsewhere.resolve("outer/modules/b.txt")));
		} finally {
			inner.delete(true, true, null);
			outer.delete(true, true, null);
		}
	}

	// --- the default working directory ---

	@Test
	void withoutProjectsTheDefaultDirectoryIsTheUsersHome() {
		assertEquals(Path.of(System.getProperty("user.home")).toAbsolutePath().normalize(), workspace.defaultCwd());
	}

	@Test
	void withoutAnActiveEditorTheDefaultDirectoryIsTheFirstRoot() throws CoreException {
		IProject alpha = project("alpha");
		project("beta");

		assertEquals(location(alpha), workspace.defaultCwd());
	}

	@Test
	void startingAndStoppingWithoutAWorkbenchDoesNothing() throws CoreException {
		assertFalse(PlatformUI.isWorkbenchRunning(), "these tests are written for a runtime without a workbench");
		IProject alpha = project("alpha");

		workspace.start();
		assertEquals(location(alpha), workspace.defaultCwd());
		workspace.dispose();
	}

	@Test
	void theProjectOfTheActiveEditorIsTheDefaultDirectory() throws CoreException {
		project("alpha");
		IProject beta = project("beta");
		IFile file = beta.getFile("notes.txt");
		file.create(new ByteArrayInputStream(new byte[0]), true, null);

		workspace.activeInputChanged(new Input(file));

		assertEquals(location(beta), workspace.defaultCwd());
	}

	@Test
	void anEditorWithoutAWorkspaceFileOrNoEditorMeansTheFirstRootAgain() throws CoreException {
		IProject alpha = project("alpha");
		IProject beta = project("beta");
		workspace.activeInputChanged(new Input(beta.getFile("notes.txt")));

		workspace.activeInputChanged(new Input(null));
		assertEquals(location(alpha), workspace.defaultCwd());

		workspace.activeInputChanged(new Input(beta.getFile("notes.txt")));
		workspace.activeInputChanged(null);
		assertEquals(location(alpha), workspace.defaultCwd());
	}

	@Test
	void aClosedOrDeletedActiveProjectIsNotADirectoryToStartIn() throws CoreException {
		IProject alpha = project("alpha");
		IProject beta = project("beta");
		workspace.activeInputChanged(new Input(beta.getFile("notes.txt")));

		beta.close(null);
		assertEquals(location(alpha), workspace.defaultCwd());

		beta.open(null);
		assertEquals(location(beta), workspace.defaultCwd());

		beta.delete(true, true, null);
		assertEquals(location(alpha), workspace.defaultCwd());
	}

	@Test
	void theProjectSelectedInAViewIsTheDefaultDirectory() throws CoreException {
		project("alpha");
		IProject beta = project("beta");

		workspace.selectionChanged(new StructuredSelection(beta.getFolder("src")));

		assertEquals(location(beta), workspace.defaultCwd());
	}

	@Test
	void ofTheEditorAndTheSelectionTheOneNotedLaterDecides() throws CoreException {
		IProject alpha = project("alpha");
		IProject beta = project("beta");

		workspace.selectionChanged(new StructuredSelection(beta));
		workspace.activeInputChanged(new Input(alpha.getFile("notes.txt")));
		assertEquals(location(alpha), workspace.defaultCwd());

		workspace.selectionChanged(new StructuredSelection(beta));
		assertEquals(location(beta), workspace.defaultCwd());
	}

	@Test
	void theSameEditorReportedAgainDoesNotOutdateTheSelection() throws CoreException {
		IProject alpha = project("alpha");
		IProject beta = project("beta");
		workspace.activeInputChanged(new Input(alpha.getFile("notes.txt")));
		workspace.selectionChanged(new StructuredSelection(beta));

		// What the part listener does when the chat view, or any other part, is activated.
		workspace.activeInputChanged(new Input(alpha.getFile("notes.txt")));
		assertEquals(location(beta), workspace.defaultCwd(), "the same editor again");

		workspace.activeInputChanged(new Input(alpha.getFile("other.txt")));
		assertEquals(location(alpha), workspace.defaultCwd(), "another file of the same project is a later choice");

		workspace.selectionChanged(new StructuredSelection(beta));
		workspace.activeInputChanged(null);
		workspace.activeInputChanged(null);
		assertEquals(location(beta), workspace.defaultCwd(), "no editor, reported twice");

		workspace.activeInputChanged(new Input(alpha.getFile("notes.txt")));
		assertEquals(location(alpha), workspace.defaultCwd(), "an editor opened after the selection");
	}

	@Test
	void aSelectionThatIsNotAResourceChangesNothing() throws CoreException {
		project("alpha");
		IProject beta = project("beta");
		workspace.selectionChanged(new StructuredSelection(beta));

		workspace.selectionChanged(StructuredSelection.EMPTY);
		workspace.selectionChanged(new StructuredSelection("not a resource"));
		workspace.selectionChanged(new TextSelection(0, 3));
		workspace.selectionChanged(null);

		assertEquals(location(beta), workspace.defaultCwd());
	}

	@Test
	void theSelectedProjectStaysWhenTheEditorIsClosed() throws CoreException {
		IProject alpha = project("alpha");
		IProject beta = project("beta");
		project("gamma");
		workspace.selectionChanged(new StructuredSelection(beta));
		workspace.activeInputChanged(new Input(alpha.getFile("notes.txt")));

		workspace.activeInputChanged(null);

		assertEquals(location(beta), workspace.defaultCwd());
	}

	@Test
	void aSelectedProjectThatIsClosedGivesWayToTheEditorsProject() throws CoreException {
		project("alpha");
		IProject beta = project("beta");
		IProject gamma = project("gamma");
		workspace.activeInputChanged(new Input(gamma.getFile("notes.txt")));
		workspace.selectionChanged(new StructuredSelection(beta));

		beta.close(null);

		assertEquals(location(gamma), workspace.defaultCwd());
	}

	@Test
	void forAModuleInsideAnotherProjectTheDirectoryIsTheOutermostProject(@TempDir Path temp)
			throws CoreException, IOException {
		Path elsewhere = temp.toRealPath();
		// "a-module" sorts first, as a module's project often does next to its parent's.
		IProject module = projectAt("a-module", elsewhere.resolve("parent/group/a-module"));
		IProject group = projectAt("group", elsewhere.resolve("parent/group"));
		IProject parent = projectAt("parent", elsewhere.resolve("parent"));
		IProject other = project("other");
		try {
			assertEquals(elsewhere.resolve("parent"), workspace.defaultCwd(), "no editor, no selection");

			workspace.activeInputChanged(new Input(module.getFile("src/A.java")));
			assertEquals(elsewhere.resolve("parent"), workspace.defaultCwd(), "editor in the module");

			workspace.selectionChanged(new StructuredSelection(group));
			assertEquals(elsewhere.resolve("parent"), workspace.defaultCwd(), "selection in between");

			parent.close(null);
			assertEquals(elsewhere.resolve("parent/group"), workspace.defaultCwd(), "the outermost that is open");

			workspace.selectionChanged(new StructuredSelection(other));
			assertEquals(location(other), workspace.defaultCwd(), "a project of its own");
		} finally {
			module.delete(true, true, null);
			group.delete(true, true, null);
			parent.delete(true, true, null);
		}
	}

	@Test
	void aSiblingFolderWithALongerNameIsNotAnEnclosingProject(@TempDir Path temp) throws CoreException, IOException {
		Path elsewhere = temp.toRealPath();
		IProject app = projectAt("app", elsewhere.resolve("app"));
		IProject appTools = projectAt("app-tools", elsewhere.resolve("app-tools"));
		try {
			workspace.selectionChanged(new StructuredSelection(appTools));
			assertEquals(elsewhere.resolve("app-tools"), workspace.defaultCwd());
		} finally {
			app.delete(true, true, null);
			appTools.delete(true, true, null);
		}
	}

	@Test
	void everythingAnswersOnAThreadThatIsNotTheUiThread() throws Exception {
		IProject alpha = project("alpha");
		Path file = location(alpha).resolve("src/app.ts");
		workspace.activeInputChanged(new Input(alpha.getFile("src/app.ts")));
		AtomicReference<Object> answer = new AtomicReference<>();

		Thread other = new Thread(() -> {
			try {
				answer.set(List.of(workspace.roots(), workspace.defaultCwd(), workspace.contains(file),
						workspace.label(file)));
			} catch (RuntimeException | Error e) {
				answer.set(e);
			}
		});
		other.start();
		other.join();

		assertEquals(List.of(List.of(location(alpha)), location(alpha), true, "alpha/src/app.ts"), answer.get());
	}

	private IProject project(String name) throws CoreException {
		IProject project = resources.getRoot().getProject(name);
		project.create(null);
		project.open(null);
		return project;
	}

	private IProject projectAt(String name, Path folder) throws CoreException {
		IProject project = resources.getRoot().getProject(name);
		IProjectDescription description = resources.newProjectDescription(name);
		description.setLocationURI(folder.toUri());
		project.create(description, null);
		project.open(null);
		return project;
	}

	private static Path location(IProject project) {
		return project.getLocation().toFile().toPath().toAbsolutePath().normalize();
	}

	/** An editor input as the workbench's file editors have it: it adapts to its file. */
	private record Input(IFile file) implements IEditorInput {

		@Override
		public <T> T getAdapter(Class<T> adapter) {
			return adapter == IFile.class || adapter == IResource.class ? adapter.cast(file) : null;
		}

		@Override
		public boolean exists() {
			return file != null && file.exists();
		}

		@Override
		public ImageDescriptor getImageDescriptor() {
			return null;
		}

		@Override
		public String getName() {
			return file == null ? "untitled" : file.getName();
		}

		@Override
		public IPersistableElement getPersistable() {
			return null;
		}

		@Override
		public String getToolTipText() {
			return getName();
		}
	}
}
