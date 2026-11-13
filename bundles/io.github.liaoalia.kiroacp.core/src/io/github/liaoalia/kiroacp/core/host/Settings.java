package io.github.liaoalia.kiroacp.core.host;

import java.util.List;

/** The user's preferences. Values are read each time, so a change takes effect on next use. */
public interface Settings {

	/** How agent terminals are shown ({@code kiroAcp.terminal.mode} of the reference). */
	enum TerminalMode {
		/** One console per session, reused by its commands. */
		SHARED,
		/** A console per command. */
		PER_COMMAND,
		/** No console; output is only kept for the agent and the tool card. */
		HIDDEN
	}

	/** When a proposed edit opens in a compare editor ({@code kiroAcp.autoOpenDiffs}). */
	enum AutoOpenDiffs {
		ALWAYS, WHEN_PERMISSION_REQUIRED, NEVER
	}

	/** Executable path or bare command name; {@code kiro-cli} by default. */
	String cliPath();

	/** Extra arguments appended after {@code acp} ({@code kiroAcp.cliArgs}); empty by default. */
	List<String> cliArgs();

	TerminalMode terminalMode();

	/** Bytes of terminal output kept per terminal; 1048576 by default. */
	int terminalOutputByteLimit();

	boolean logProtocol();

	AutoOpenDiffs autoOpenDiffs();

	/** Attach the active editor's file to prompts as an implicit chip. */
	boolean attachActiveFile();

	boolean showSettingsByDefault();

	boolean sessionPanelCollapsed();

	/** Carry mode, model and config options over to new sessions. */
	boolean rememberSessionOptions();

	/** @return removes the listener; it is called on an unspecified thread after any value changed */
	Subscription onChange(Runnable listener);
}
