package io.github.liaoalia.kiroacp.ui.chat;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

import org.eclipse.core.filesystem.EFS;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;

import io.github.liaoalia.kiroacp.core.host.Logger;
import io.github.liaoalia.kiroacp.core.session.ChatActions;
import io.github.liaoalia.kiroacp.ui.services.EclipseLogger;

/** What the chat asks of the workbench. Called on the UI thread. */
final class EclipseChatActions implements ChatActions {

	private final EclipseLogger logger;

	EclipseChatActions(EclipseLogger logger) {
		this.logger = logger;
	}

	@Override
	public void showLog() {
		logger.show();
	}

	@Override
	public void openFile(String path, Integer line) {
		Path file = existingFile(path, logger);
		IWorkbenchPage page = activePage();
		if (file == null || page == null) {
			return;
		}
		try {
			IEditorPart editor = IDE.openEditorOnFileStore(page, EFS.getLocalFileSystem().fromLocalFile(file.toFile()));
			if (line != null && editor instanceof ITextEditor text) {
				reveal(text, line);
			}
		} catch (PartInitException e) {
			logger.error("Could not open " + file, e);
		}
	}

	/**
	 * The path comes from the page, and so in the end from the agent: only an
	 * existing regular file named by an absolute path is opened.
	 */
	static Path existingFile(String path, Logger logger) {
		if (path == null || path.isBlank()) {
			return null;
		}
		try {
			Path file = Path.of(path);
			if (file.isAbsolute() && Files.isRegularFile(file)) {
				return file;
			}
		} catch (InvalidPathException e) {
			// Reported below like any other path that is not a file.
		}
		logger.warn("Not an existing file, not opened: " + path);
		return null;
	}

	/** @param line 1-based, as the agent reports locations */
	private static void reveal(ITextEditor editor, int line) {
		IDocument document = editor.getDocumentProvider() == null ? null
				: editor.getDocumentProvider().getDocument(editor.getEditorInput());
		if (document == null) {
			return;
		}
		try {
			int index = Math.max(0, Math.min(line - 1, document.getNumberOfLines() - 1));
			editor.selectAndReveal(document.getLineOffset(index), 0);
		} catch (BadLocationException e) {
			// The file is open; where exactly matters less.
		}
	}

	private static IWorkbenchPage activePage() {
		if (!PlatformUI.isWorkbenchRunning()) {
			return null;
		}
		IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
		return window == null ? null : window.getActivePage();
	}
}
