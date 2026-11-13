package io.github.liaoalia.kiroacp.ui.context;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IURIEditorInput;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.ITextEditor;

import io.github.liaoalia.kiroacp.core.host.Subscription;
import io.github.liaoalia.kiroacp.core.session.context.EditorContext;

/**
 * The workbench's active editor as the chat's {@link EditorContext}.
 *
 * <p>The active editor is the one on top in the window used last; it stays
 * the active one while a view (the chat itself) has the focus. An editor
 * counts when its input is a file on the local disk: a workspace file, or a
 * file opened from outside the workspace. The selection is the one of a text
 * editor, in the lines of its document (folding does not shift them).
 *
 * <p>Without a running workbench there is no editor. UI thread, except
 * {@link #onActiveFileChanged}.
 */
public final class WorkbenchEditorContext implements EditorContext {

	private final List<Consumer<Path>> listeners = new CopyOnWriteArrayList<>();

	// UI thread only.
	private IWorkbench workbench;
	private boolean disposed;
	/** The file told to the listeners last: parts are activated all the time, the chat among them, and that is no change of editor. */
	private Path reported;

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
		public void partInputChanged(IWorkbenchPartReference part) {
			pageChanged(part.getPage());
		}
	};

	private final IWindowListener windowListener = new IWindowListener() {
		@Override
		public void windowOpened(IWorkbenchWindow window) {
			window.getPartService().addPartListener(partListener);
		}

		@Override
		public void windowActivated(IWorkbenchWindow window) {
			pageChanged(window.getActivePage());
		}

		@Override
		public void windowClosed(IWorkbenchWindow window) {
			window.getPartService().removePartListener(partListener);
		}

		@Override
		public void windowDeactivated(IWorkbenchWindow window) {
			// The editor used last stays the reference while a dialog or another application is in front.
		}
	};

	/** Starts following the active editor. Does nothing without a running workbench. */
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
				window.getPartService().addPartListener(partListener);
			}
			reported = activeFile();
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
				window.getPartService().removePartListener(partListener);
			}
			workbench = null;
		});
	}

	@Override
	public Path activeFile() {
		IEditorPart editor = activeEditor();
		return editor == null ? null : fileOf(editor.getEditorInput());
	}

	@Override
	public TextSelection selection() {
		IEditorPart editor = activeEditor();
		Path file = editor == null ? null : fileOf(editor.getEditorInput());
		if (file == null) {
			return null;
		}
		// A multi-page editor answers with its text page, when it has one.
		ITextEditor text = Adapters.adapt(editor, ITextEditor.class);
		ISelectionProvider provider = text == null ? null : text.getSelectionProvider();
		ISelection selection = provider == null ? null : provider.getSelection();
		if (!(selection instanceof ITextSelection chosen)) {
			return null;
		}
		IDocumentProvider documents = text.getDocumentProvider();
		return selectionOf(file, chosen, documents == null ? null : documents.getDocument(text.getEditorInput()));
	}

	@Override
	public Subscription onActiveFileChanged(Consumer<Path> listener) {
		listeners.add(listener);
		return () -> listeners.remove(listener);
	}

	/** @return the local file an editor shows; null for anything else (a compare input, a class file in a jar, a remote file) */
	static Path fileOf(IEditorInput input) {
		if (input == null) {
			return null;
		}
		IFile file = input.getAdapter(IFile.class);
		if (file != null) {
			IPath location = file.getLocation();
			return location == null ? null : location.toFile().toPath().toAbsolutePath().normalize();
		}
		if (input instanceof IURIEditorInput external) {
			URI uri = external.getURI();
			if (uri != null && "file".equalsIgnoreCase(uri.getScheme())) {
				try {
					return Path.of(uri).toAbsolutePath().normalize();
				} catch (RuntimeException e) {
					// A file: URI this platform cannot read as a path names no file here.
					return null;
				}
			}
		}
		return null;
	}

	/**
	 * A selection of a text editor in 0-based lines and columns of its
	 * document, the end exclusive.
	 *
	 * @param document may be null: the lines are then the selection's own, without columns
	 * @return null when the selection is none (an editor that is still opening)
	 */
	static TextSelection selectionOf(Path file, ITextSelection selection, IDocument document) {
		int offset = selection.getOffset();
		int length = Math.max(0, selection.getLength());
		if (document != null && offset >= 0) {
			try {
				int startLine = document.getLineOfOffset(offset);
				int endLine = document.getLineOfOffset(offset + length);
				return new TextSelection(file, startLine, offset - document.getLineOffset(startLine), endLine,
						offset + length - document.getLineOffset(endLine));
			} catch (BadLocationException e) {
				// The document changed under the selection: the selection's own lines are the best there is.
			}
		}
		int startLine = selection.getStartLine();
		if (startLine < 0) {
			return null;
		}
		if (length == 0) {
			return new TextSelection(file, startLine, 0, startLine, 0);
		}
		// The selection's last line is the one of its last character: the whole of it counts.
		return new TextSelection(file, startLine, 0, Math.max(startLine, selection.getEndLine()), 1);
	}

	private void pageChanged(IWorkbenchPage page) {
		IEditorPart editor = page == null ? null : page.getActiveEditor();
		Path file = editor == null ? null : fileOf(editor.getEditorInput());
		// No editor, or one that shows no file: the file used last stays the reference.
		if (file == null || file.equals(reported)) {
			return;
		}
		reported = file;
		for (Consumer<Path> listener : listeners) {
			listener.accept(file);
		}
	}

	private static IEditorPart activeEditor() {
		if (!PlatformUI.isWorkbenchRunning()) {
			return null;
		}
		IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
		IWorkbenchPage page = window == null ? null : window.getActivePage();
		return page == null ? null : page.getActiveEditor();
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
}
