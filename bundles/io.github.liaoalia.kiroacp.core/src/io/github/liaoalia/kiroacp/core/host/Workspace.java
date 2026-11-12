package io.github.liaoalia.kiroacp.core.host;

import java.nio.file.Path;
import java.util.List;

/** The folders the user works in, as the core needs them. Callable from any thread. */
public interface Workspace {

	/** Absolute locations of the open projects, possibly empty. */
	List<Path> roots();

	/**
	 * The working directory for the agent process and new sessions: the
	 * project of the active editor, else the first root, else the user's home.
	 */
	Path defaultCwd();

	/** @return whether the absolute path is inside one of the {@link #roots()} */
	boolean contains(Path absolute);

	/** @return a short label for the path: relative to its root when inside one, else the file name */
	String label(Path absolute);
}
