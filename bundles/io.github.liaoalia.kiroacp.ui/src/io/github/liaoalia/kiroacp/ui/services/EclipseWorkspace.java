package io.github.liaoalia.kiroacp.ui.services;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.ISelectionListener;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import io.github.liaoalia.kiroacp.core.host.Workspace;

/**
 * The open projects of the Eclipse workspace as the core's {@link Workspace}.
 *
 * <p>A root is the location of an open project on disk; projects kept on
 * another file system have none and are left out. Roots are read from the
 * resource tree on every call, so a project opened or closed later is seen.
 *
 * <p>The directory to start in follows what the user is working on: the
 * project of the active editor or of the resource selected last in a view
 * such as the Project Explorer, whichever was noted later. A workspace with
 * a multi-module build has a project per module, each inside the folder of
 * its parent; the directory is then the outermost of them, which is where
 * the build and usually the repository start.
 *
 * <p>Every method is callable from any thread. The active editor and the
 * selection can only be asked on the UI thread, so {@link #start()} follows
 * them there with listeners and the other threads read what was noted last.
 */
public final class EclipseWorkspace implements Workspace {

	private final IWorkspace workspace;
	/** Project of the editor on top in the window used last; null when it has none. */
	private volatile IProject activeProject;
	/** Project of the resource selected last in a view; stays when the selection moves to something else. */
	private volatile IProject selectedProject;
	/** Whether the selection was noted after the editor. */
	private volatile boolean selectionIsNewer;

	// UI thread only.
	private IWorkbench workbench;
	private boolean disposed;

	private final IPartListener2 partListener = new IPartListener2() {
		@Override
		public void partActivated(IWorkbenchPartReference part) {
			pageChanged(part.getPage());
		}

		@Override
		public void partBroughtToTop(IWorkbenchPartReference part) {
			pageChanged(part.getPage());
		}

		@Override
		public void partOpened(IWorkbenchPartReference part) {
			pageChanged(part.getPage());
		}

		@Override
		public void partInputChanged(IWorkbenchPartReference part) {
			pageChanged(part.getPage());
		}

		@Override
		public void partClosed(IWorkbenchPartReference part) {
			IWorkbenchPage page = part.getPage();
			IEditorPart active = page == null ? null : page.getActiveEditor();
			// The page may still name the editor that is closing. If another one
			// takes its place, that one is brought to top and reported then.
			if (active != null && active == part.getPart(false)) {
				activeInputChanged(null);
			} else {
				pageChanged(page);
			}
		}
	};

	private final ISelectionListener selectionListener = (part, selection) -> selectionChanged(selection);

	private final IWindowListener windowListener = new IWindowListener() {
		@Override
		public void windowOpened(IWorkbenchWindow window) {
			follow(window);
		}

		@Override
		public void windowActivated(IWorkbenchWindow window) {
			pageChanged(window.getActivePage());
		}

		@Override
		public void windowClosed(IWorkbenchWindow window) {
			unfollow(window);
		}

		@Override
		public void windowDeactivated(IWorkbenchWindow window) {
			// The editor used last stays the reference while a dialog or another application is in front.
		}
	};

	public EclipseWorkspace() {
		this(ResourcesPlugin.getWorkspace());
	}

	EclipseWorkspace(IWorkspace workspace) {
		this.workspace = Objects.requireNonNull(workspace, "workspace");
	}

	/**
	 * Starts following the active editor. Does nothing without a running
	 * workbench; {@link #defaultCwd()} then answers the first root.
	 */
	public void start() {
		if (!PlatformUI.isWorkbenchRunning()) {
			return;
		}
		IWorkbench running = PlatformUI.getWorkbench();
		onUiThread(running.getDisplay(), () -> {
			if (workbench != null || disposed) {
				return;
			}
			workbench = running;
			running.addWindowListener(windowListener);
			for (IWorkbenchWindow window : running.getWorkbenchWindows()) {
				follow(window);
			}
			IWorkbenchWindow window = running.getActiveWorkbenchWindow();
			if (window != null) {
				// The editor first: what is selected now is the later choice of the two.
				pageChanged(window.getActivePage());
				selectionChanged(window.getSelectionService().getSelection());
			}
		});
	}

	/** Stops following the active editor. */
	public void dispose() {
		if (!PlatformUI.isWorkbenchRunning()) {
			return;
		}
		onUiThread(PlatformUI.getWorkbench().getDisplay(), () -> {
			disposed = true;
			if (workbench == null) {
				return;
			}
			workbench.removeWindowListener(windowListener);
			for (IWorkbenchWindow window : workbench.getWorkbenchWindows()) {
				unfollow(window);
			}
			workbench = null;
		});
	}

	@Override
	public List<Path> roots() {
		List<Path> roots = new ArrayList<>();
		for (Root root : openProjects()) {
			roots.add(root.location);
		}
		return List.copyOf(roots);
	}

	@Override
	public Path defaultCwd() {
		List<Root> roots = openProjects();
		IProject editor = activeProject;
		IProject selected = selectedProject;
		for (IProject project : selectionIsNewer ? new IProject[] { selected, editor } : new IProject[] { editor, selected }) {
			Path location = location(project);
			if (location != null) {
				return outermost(location, roots);
			}
		}
		if (!roots.isEmpty()) {
			return outermost(roots.get(0).location, roots);
		}
		return Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
	}

	@Override
	public boolean contains(Path absolute) {
		return rootOf(absolute) != null;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Inside a root the label starts with the project's name, for example
	 * {@code project/src/app.ts}: an Eclipse workspace usually holds several
	 * projects, and the same relative path exists in more than one of them.
	 * (The reference leaves the folder name out, which suits its usual single
	 * folder.) The separator is always {@code /}.
	 */
	@Override
	public String label(Path absolute) {
		Path path = absolute.toAbsolutePath().normalize();
		Root root = rootOf(path);
		if (root == null) {
			Path name = path.getFileName();
			return name != null ? name.toString() : path.toString();
		}
		StringBuilder label = new StringBuilder(root.name);
		for (Path segment : root.location.relativize(path)) {
			if (!segment.toString().isEmpty()) {
				label.append('/').append(segment);
			}
		}
		return label.toString();
	}

	/** Notes the project of the editor input now on top; null for no editor. */
	void activeInputChanged(IEditorInput input) {
		IResource resource = null;
		if (input != null) {
			resource = input.getAdapter(IFile.class);
			if (resource == null) {
				resource = input.getAdapter(IResource.class);
			}
		}
		activeProject = resource == null ? null : resource.getProject();
		selectionIsNewer = false;
	}

	/**
	 * Notes the project of the first selected element when it is a workspace
	 * resource. Any other selection (text in an editor, nothing at all)
	 * changes nothing: moving on to another view is not a change of project.
	 */
	void selectionChanged(ISelection selection) {
		if (!(selection instanceof IStructuredSelection structured) || structured.isEmpty()) {
			return;
		}
		IResource resource = Adapters.adapt(structured.getFirstElement(), IResource.class);
		IProject project = resource == null ? null : resource.getProject();
		if (project != null) {
			selectedProject = project;
			selectionIsNewer = true;
		}
	}

	private void follow(IWorkbenchWindow window) {
		window.getPartService().addPartListener(partListener);
		window.getSelectionService().addSelectionListener(selectionListener);
	}

	private void unfollow(IWorkbenchWindow window) {
		window.getPartService().removePartListener(partListener);
		window.getSelectionService().removeSelectionListener(selectionListener);
	}

	/** The location of the outermost open project whose folder holds this one. */
	private static Path outermost(Path location, List<Root> roots) {
		Path outermost = location;
		for (Root root : roots) {
			if (location.startsWith(root.location) && root.location.getNameCount() < outermost.getNameCount()) {
				outermost = root.location;
			}
		}
		return outermost;
	}

	private void pageChanged(IWorkbenchPage page) {
		IEditorPart editor = page == null ? null : page.getActiveEditor();
		activeInputChanged(editor == null ? null : editor.getEditorInput());
	}

	/** The innermost root holding the path: a project may lie inside another one's folder. */
	private Root rootOf(Path absolute) {
		Path path = absolute.toAbsolutePath().normalize();
		Root innermost = null;
		for (Root root : openProjects()) {
			if (path.startsWith(root.location)
					&& (innermost == null || root.location.getNameCount() > innermost.location.getNameCount())) {
				innermost = root;
			}
		}
		return innermost;
	}

	private List<Root> openProjects() {
		List<Root> roots = new ArrayList<>();
		for (IProject project : workspace.getRoot().getProjects()) {
			Path location = location(project);
			if (location != null) {
				roots.add(new Root(project.getName(), location));
			}
		}
		return roots;
	}

	/** @return where the project is on disk; null when it is closed, gone or not on the local file system */
	private static Path location(IProject project) {
		if (project == null || !project.isAccessible()) {
			return null;
		}
		IPath location = project.getLocation();
		return location == null ? null : location.toFile().toPath().toAbsolutePath().normalize();
	}

	private static void onUiThread(Display display, Runnable work) {
		if (display == null || display.isDisposed()) {
			return;
		}
		try {
			if (display.getThread() == Thread.currentThread()) {
				work.run();
			} else {
				display.asyncExec(work);
			}
		} catch (SWTException disposedMeanwhile) {
			// The workbench is shutting down; there is nothing left to follow.
		}
	}

	private record Root(String name, Path location) {
	}
}
