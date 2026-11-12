package io.github.liaoalia.kiroacp.core.session;

/**
 * What the chat asks of the IDE beyond the services: the few things only a
 * workbench can do. Every method is called on the controller thread and must
 * return at once.
 */
public interface ChatActions {

	/** Reveal the protocol log ("Show log" in the chat). */
	void showLog();

	/**
	 * Open a file beside the chat without taking the focus from it. Failures
	 * are the implementation's to report.
	 *
	 * @param path absolute path as the agent named it
	 * @param line 1-based line to reveal, or {@code null}
	 */
	void openFile(String path, Integer line);

	/**
	 * A session waits for an answer (a permission request) while no chat page
	 * is attached at all: open a page and call {@link PageHandle#setSession}
	 * with this id, rather than a fresh page that would hide the question.
	 */
	default void revealSession(String sessionId) {
	}

	/** The focused session started or ended a turn; what enables "Cancel Current Turn". */
	default void turnActiveChanged(boolean active) {
	}
}
