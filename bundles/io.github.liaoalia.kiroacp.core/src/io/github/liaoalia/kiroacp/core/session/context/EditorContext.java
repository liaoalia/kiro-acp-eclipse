package io.github.liaoalia.kiroacp.core.session.context;

import java.nio.file.Path;
import java.util.function.Consumer;

import io.github.liaoalia.kiroacp.core.host.Subscription;

/**
 * The editor the user works in, as the context chips need it (the
 * reference's {@code vscode.window.activeTextEditor}). Only editors of files
 * on the local file system count; anything else (a console, a compare
 * editor, a file of a remote system) is "no editor".
 *
 * <p>{@link #activeFile()} and {@link #selection()} are called on the
 * controller thread.
 */
public interface EditorContext {

	/**
	 * What is selected in the active editor. Lines and columns are 0-based;
	 * the end is exclusive, so a selection that ends at column 0 does not
	 * reach into that line. Start and end are equal for a mere caret.
	 *
	 * @param file absolute
	 */
	record TextSelection(Path file, int startLine, int startColumn, int endLine, int endColumn) {

		public boolean isEmpty() {
			return startLine == endLine && startColumn == endColumn;
		}
	}

	/** The file of the active editor, absolute; {@code null} when there is none. */
	Path activeFile();

	/** The selection of the active editor; {@code null} when there is no editor of a file. */
	TextSelection selection();

	/**
	 * Told whenever another editor became the active one, with its file, on
	 * any thread. An editor that is not of a file is not reported at all: the
	 * file used last stays the reference.
	 *
	 * @return removes the listener
	 */
	Subscription onActiveFileChanged(Consumer<Path> listener);

	/** No workbench: no editor, ever. */
	EditorContext NONE = new EditorContext() {
		@Override
		public Path activeFile() {
			return null;
		}

		@Override
		public TextSelection selection() {
			return null;
		}

		@Override
		public Subscription onActiveFileChanged(Consumer<Path> listener) {
			return () -> {
			};
		}
	};
}
