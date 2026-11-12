package io.github.liaoalia.kiroacp.ui.services;

/**
 * The consoles of the IDE as {@link ConsoleTerminalDisplay} needs them; the
 * Console view in the product ({@link WorkbenchConsoles}), a recording fake in
 * tests. Callable from any thread.
 */
interface ConsoleHost {

	/**
	 * Adds a console without bringing it up.
	 *
	 * @param removed to be run, on any thread, whenever the console is gone from the IDE: taken
	 *                away by the user or through {@link Sink#remove}
	 * @return {@code null} when no console can be shown now
	 */
	Sink create(String name, Runnable removed);

	/** One console. */
	interface Sink {

		/** Appends plain text with {@code \n} line ends. May wait for the UI thread to keep up. */
		void append(String text);

		/** Brings the console up; takes the keyboard focus only when {@code focus}. */
		void show(boolean focus);

		/** Takes the console out of the IDE. */
		void remove();
	}
}
