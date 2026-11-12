package io.github.liaoalia.kiroacp.ui.services;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.IFileBuffer;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.filebuffers.ITextFileBufferManager;
import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.filesystem.IFileStore;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.widgets.Display;

import io.github.liaoalia.kiroacp.core.host.Logger;
import io.github.liaoalia.kiroacp.core.pure.text.Platform;
import io.github.liaoalia.kiroacp.core.services.DiskTextFiles;
import io.github.liaoalia.kiroacp.core.services.FsService;

/**
 * The agent's reads and writes as Eclipse sees the files (reference D8).
 *
 * <p>A file open in an editor has a text file buffer, and the buffer is what
 * is read and written: the agent sees unsaved text, and its write replaces the
 * document in one edit (one step of the editor's undo) and saves it, so the
 * editor shows the new text and is not left dirty. The buffer saves in the
 * file's own encoding, as the editor would. A file without a buffer is read
 * and written on disk as UTF-8, and the workspace is told about the write so
 * the file is not reported as out of sync.
 *
 * <h2>Threads</h2>
 * {@link #read} and {@link #write} are called on the {@code kiro-fs} worker.
 * The document of a buffer belongs to the UI thread: an editor reads it there
 * and expects changes there. Whatever touches a document therefore runs
 * synchronously on the UI thread of the display given to the constructor
 * ({@code Display.syncExec}); with no display, or one that is gone, it runs
 * on the calling thread. The save runs in the same step, so nothing the user
 * types can come between the edit and the save; like a save from the editor
 * it waits for the workspace if a build holds the file.
 *
 * <p>The worker waits for the UI thread, so the UI thread must never wait for
 * the worker: no {@code join()} or {@code get()} there on a future of
 * {@code FsService}. A modal dialog is no such wait, its event loop runs the
 * hop. Disk work (reading, writing, refreshing the workspace) stays on the
 * worker.
 */
public final class EclipseTextFiles extends DiskTextFiles {

	private final Display display;
	private final Prompts prompts;
	private final Logger logger;
	private final ITextFileBufferManager buffers;
	private final IWorkspaceRoot root;

	/**
	 * @param display whose thread documents are read and changed on; {@code null} when there is no
	 *                UI, as in a headless run
	 * @param logger  may be {@code null}
	 */
	public EclipseTextFiles(Display display, Prompts prompts, Logger logger) {
		this(display, prompts, logger, FileBuffers.getTextFileBufferManager(), ResourcesPlugin.getWorkspace().getRoot());
	}

	EclipseTextFiles(Display display, Prompts prompts, Logger logger, ITextFileBufferManager buffers,
			IWorkspaceRoot root) {
		this.display = display;
		this.prompts = Objects.requireNonNull(prompts, "prompts");
		this.logger = logger != null ? logger : Logger.NONE;
		this.buffers = Objects.requireNonNull(buffers, "buffers");
		this.root = Objects.requireNonNull(root, "root");
	}

	@Override
	public String read(Path file) throws IOException {
		if (bufferOf(file) != null) {
			String text = onUiThread(() -> {
				// Looked up again: the editor may have closed while the hop was waiting.
				ITextFileBuffer buffer = bufferOf(file);
				return buffer == null ? null : buffer.getDocument().get();
			});
			if (text != null) {
				return text;
			}
		}
		return super.read(file);
	}

	@Override
	public void write(Path file, String content) throws IOException {
		if (bufferOf(file) != null && onUiThread(() -> writeThroughBuffer(file, content))) {
			return;
		}
		super.write(file, content);
		refresh(file);
	}

	@Override
	public CompletionStage<Boolean> confirmOutsideWorkspace(Path file) {
		return prompts.ask(new Prompts.Question("Kiro", FsService.outsideWorkspaceQuestion(file), null, "Write",
				"Deny", true));
	}

	/** @return false when the file has no buffer any more, or its buffer cannot bring the content to disk */
	private Boolean writeThroughBuffer(Path file, String content) throws IOException {
		ITextFileBuffer buffer = bufferOf(file);
		if (buffer == null) {
			return Boolean.FALSE;
		}
		IDocument document = buffer.getDocument();
		try {
			if (!content.equals(document.get())) {
				// What a team provider needs before a file is edited (a checkout, a lock).
				buffer.validateState(new NullProgressMonitor(), null);
				document.replace(0, document.getLength(), content);
			}
			if (buffer.isDirty()) {
				buffer.commit(new NullProgressMonitor(), true);
				return Boolean.TRUE;
			}
			// A clean buffer saves nothing. If the file changed on disk behind it, only a
			// write to disk makes the content true there; the buffer then reloads.
			return buffer.isSynchronized();
		} catch (CoreException | BadLocationException e) {
			throw new IOException("Could not save " + file + ": " + e.getMessage(), e);
		}
	}

	/** The buffer of the editor (or of whoever else connected one) the file is open in; null for none. */
	private ITextFileBuffer bufferOf(Path file) {
		Path wanted = file.toAbsolutePath().normalize();
		ITextFileBuffer found = find(buffers.getFileBuffers(), wanted);
		// Files opened from outside the workspace are kept apart, by file store.
		return found != null ? found : find(buffers.getFileStoreFileBuffers(), wanted);
	}

	private static ITextFileBuffer find(IFileBuffer[] candidates, Path wanted) {
		for (IFileBuffer each : candidates) {
			if (each instanceof ITextFileBuffer text && same(wanted, location(each))) {
				return text;
			}
		}
		return null;
	}

	private static Path location(IFileBuffer buffer) {
		try {
			IFileStore store = buffer.getFileStore();
			File local = store == null ? null : store.toLocalFile(EFS.NONE, null);
			return local == null ? null : local.toPath().toAbsolutePath().normalize();
		} catch (CoreException | RuntimeException e) {
			// Not a file of the local file system: nothing the agent can name by path.
			return null;
		}
	}

	private static boolean same(Path wanted, Path location) {
		if (location == null) {
			return false;
		}
		// The agent may spell a Windows path in another case than Eclipse does (c:\ for C:\).
		return wanted.equals(location) || Platform.current() == Platform.WIN32
				&& wanted.toString().equalsIgnoreCase(location.toString());
	}

	/** Tells the workspace about a file written behind its back. */
	private void refresh(Path file) {
		try {
			for (IFile resource : root.findFilesForLocationURI(file.toAbsolutePath().normalize().toUri())) {
				if (!resource.getProject().isAccessible()) {
					continue;
				}
				// Folders made for the file are new to the workspace too, and a resource
				// can only be added below one the workspace knows: outermost first.
				Deque<IResource> unknown = new ArrayDeque<>();
				for (IResource each = resource.getParent(); each != null && !each.exists(); each = each.getParent()) {
					unknown.push(each);
				}
				for (IResource folder : unknown) {
					folder.refreshLocal(IResource.DEPTH_ZERO, null);
				}
				resource.refreshLocal(IResource.DEPTH_ZERO, null);
			}
		} catch (CoreException | RuntimeException e) {
			// The file is written; the workspace finds out at its next refresh.
			logger.warn("Could not refresh the workspace after writing " + file + ": " + e.getMessage());
		}
	}

	private interface DocumentWork<T> {
		T run() throws IOException;
	}

	private <T> T onUiThread(DocumentWork<T> work) throws IOException {
		Display ui = display;
		if (ui == null || ui.isDisposed() || ui.getThread() == Thread.currentThread()) {
			return work.run();
		}
		Object[] outcome = new Object[2];
		boolean[] ran = new boolean[1];
		try {
			ui.syncExec(() -> {
				ran[0] = true;
				try {
					outcome[0] = work.run();
				} catch (IOException | RuntimeException e) {
					outcome[1] = e;
				}
			});
		} catch (SWTException disposedMeanwhile) {
			// Handled below: the work has not run.
		}
		if (!ran[0]) {
			// The workbench is going down and took its editors along.
			return work.run();
		}
		if (outcome[1] instanceof IOException failure) {
			throw failure;
		}
		if (outcome[1] instanceof RuntimeException failure) {
			throw failure;
		}
		@SuppressWarnings("unchecked")
		T result = (T) outcome[0];
		return result;
	}
}
