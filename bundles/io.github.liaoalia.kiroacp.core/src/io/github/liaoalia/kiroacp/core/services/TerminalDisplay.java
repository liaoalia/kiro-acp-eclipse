package io.github.liaoalia.kiroacp.core.services;

import io.github.liaoalia.kiroacp.core.host.Settings.TerminalMode;

/**
 * Where the user watches agent commands run: what the reference shows in a VS
 * Code pseudoterminal (D9). {@link TerminalService} keeps the output for the
 * agent and the chat card itself; this only mirrors it.
 *
 * <p>Nothing here is called on the agent connection's reader thread.
 * {@link #open}, {@link Console#write}, {@link Console#exited} and
 * {@code reveal(false)} come from the service's own threads, never the UI
 * thread; {@code reveal(true)} and {@link Console#close} come from whoever
 * called {@code showTerminal} or {@code disposeAll}, usually the UI thread. No
 * lock of the service is held during a call, so an implementation may run
 * synchronously on the UI thread. The displays are not interactive: nothing
 * typed into one reaches a command.
 */
public interface TerminalDisplay {

	/**
	 * What a console is opened for.
	 *
	 * @param mode       {@link TerminalMode#SHARED}: the one console of {@code sessionId}, used by
	 *                   all its commands; {@link TerminalMode#PER_COMMAND}: the console of one
	 *                   command. Never {@link TerminalMode#HIDDEN}
	 * @param terminalId the command's terminal id; {@code null} for a shared console
	 * @param name       the title: {@code Kiro · <session>} or {@code Kiro: <command>}
	 */
	record Target(TerminalMode mode, String sessionId, String terminalId, String name) {
	}

	/**
	 * Create a console without showing it; the service calls
	 * {@link Console#reveal} when it should come up.
	 *
	 * @param closedByUser to be run, on any thread, when the user closes the console: its running
	 *                     commands are then stopped. A console the user cannot close never runs it
	 * @return {@code null} when nothing can be shown: the command runs as in hidden mode
	 */
	Console open(Target target, Runnable closedByUser);

	/**
	 * One console. {@link #write} and {@link #exited} of the same console never
	 * overlap each other, but come from different threads, and a late call may
	 * still arrive after the user closed the console or after {@link #close};
	 * such calls are to be ignored.
	 */
	interface Console {

		/**
		 * Append text as a terminal would receive it: lines end in {@code \r\n} and
		 * the text may carry ANSI escape sequences, both the command's own and those
		 * of the header ({@code $ command   [cwd]}) and footer ({@code [exit code N]})
		 * the service writes around each command. A console that does not interpret
		 * them strips them with {@code ShellText.stripAnsi}.
		 */
		void write(String text);

		/**
		 * A command of this console ended; its footer has been written. For a
		 * console that marks its state (a title, an icon); nothing needs to be printed.
		 *
		 * @param exitCode {@code null} when unknown or ended by a signal
		 * @param signal   {@code null} unless ended by a signal
		 */
		default void exited(String terminalId, Integer exitCode, String signal) {
		}

		/**
		 * Bring the console to the front.
		 *
		 * @param focus {@code false} when a command starts (the user keeps typing where they are),
		 *              {@code true} when the user asked to see the terminal
		 */
		void reveal(boolean focus);

		/** Remove the console. Called for shared consoles when the service is disposed. */
		void close();
	}

	/** Shows nothing: every command runs as in hidden mode. */
	TerminalDisplay NONE = (target, closedByUser) -> null;
}
