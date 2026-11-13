package io.github.liaoalia.kiroacp.core.session.history;

/**
 * Where the remembered session options are kept between runs, as one piece
 * of text: per workspace, as the reference keeps them in its workspace state.
 * Callable from any thread.
 */
public interface OptionMemory {

	/** @return what was stored last, or {@code null} when nothing is */
	String read();

	/** @param text what to keep; {@code null} forgets it */
	void write(String text);
}
