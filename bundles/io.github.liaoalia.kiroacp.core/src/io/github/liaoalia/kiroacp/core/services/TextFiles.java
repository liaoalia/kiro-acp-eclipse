package io.github.liaoalia.kiroacp.core.services;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletionStage;

/**
 * The text of files as the IDE sees it: what {@link FsService} reads and
 * writes through, so that the agent sees unsaved edits and its writes land in
 * the editors the user has open (reference D8).
 *
 * <p>{@link #read} and {@link #write} are called on the one worker thread of
 * the {@link FsService}, never on the agent connection's reader thread nor on
 * the UI thread, one call at a time, and may block (an implementation over
 * editors may run synchronously on the UI thread). Paths are absolute.
 */
public interface TextFiles {

	/**
	 * The current text of the file: the buffer of an open editor when there is
	 * one, saved or not, else the file on disk decoded as UTF-8.
	 *
	 * @throws IOException when there is no such file or it cannot be read
	 */
	String read(Path file) throws IOException;

	/**
	 * Make {@code content} the whole text of the file, on disk: through the
	 * document of an open editor (replaced as one undoable edit, then saved) when
	 * there is one, else by writing the file as UTF-8, creating it and its parent
	 * directories when missing. When this returns, a process reading the file
	 * sees {@code content}.
	 *
	 * @throws IOException when the file cannot be written or saved
	 */
	void write(Path file, String content) throws IOException;

	/**
	 * Ask the user whether the agent may write a file outside every workspace
	 * root. Called on the same worker thread, which must not be kept waiting for
	 * the answer: return at once and complete the stage when the user chose.
	 *
	 * @return completes with {@code true} to allow; {@code false}, {@code null} or a failure
	 *         refuses the write
	 */
	CompletionStage<Boolean> confirmOutsideWorkspace(Path file);
}
