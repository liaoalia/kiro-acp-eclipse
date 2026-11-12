package io.github.liaoalia.kiroacp.ui.services;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;

import io.github.liaoalia.kiroacp.core.host.Logger;
import io.github.liaoalia.kiroacp.core.host.Settings;

/**
 * Writes what the plugin does to the "Kiro ACP" console and its problems to
 * the Eclipse log.
 *
 * <p>The console gets every line, as the reference's output channel does:
 * {@code 12:34:56.789 [info] text}, and {@code [send]}, {@code [recv]} and
 * {@code [stderr]} lines while the protocol log is switched on. Warnings and
 * errors also go to the Eclipse log, where the Error Log view and the
 * workspace's {@code .log} keep them; information does not, it would bury
 * the problems of everything else there.
 *
 * <p>Callable from any thread. Nothing here throws: a log line is never worth
 * failing the work it reports.
 */
public final class EclipseLogger implements Logger {

	/** What the lines are written to; the console in the product. */
	interface Sink {

		/** Appends complete lines, each ending in a line break. Callable from any thread. */
		void append(String lines);

		/** Brings the text in front of the user. Callable from any thread. */
		void show();
	}

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ROOT);

	private final Settings settings;
	private final ILog log;
	private final Sink sink;

	/** @param settings read on every call, so switching the protocol log takes effect at once */
	public EclipseLogger(Settings settings) {
		this(settings, ILog.of(EclipseLogger.class), new KiroConsole());
	}

	EclipseLogger(Settings settings, ILog log, Sink sink) {
		this.settings = Objects.requireNonNull(settings, "settings");
		this.log = Objects.requireNonNull(log, "log");
		this.sink = Objects.requireNonNull(sink, "sink");
	}

	@Override
	public void info(String message) {
		line("info", message);
	}

	@Override
	public void warn(String message) {
		line("warn", message);
		report(IStatus.WARNING, message, null);
	}

	@Override
	public void error(String message, Throwable cause) {
		// The stack trace goes to the Eclipse log; the console says what and why in one line.
		line("error", cause == null ? message : message + ": " + cause);
		report(IStatus.ERROR, message, cause);
	}

	@Override
	public boolean traces() {
		try {
			return settings.logProtocol();
		} catch (RuntimeException e) {
			return false;
		}
	}

	@Override
	public void trace(Direction direction, String payload) {
		// Asked again: callers may hold on to the answer of traces() for a while.
		if (traces()) {
			line(direction.name().toLowerCase(Locale.ROOT), payload);
		}
	}

	/** Reveals the console, for the "Show Protocol Log" command. */
	public void show() {
		try {
			sink.show();
		} catch (RuntimeException e) {
			report(IStatus.WARNING, "Could not show the Kiro ACP console", e);
		}
	}

	private void line(String tag, String text) {
		String line = LocalTime.now().format(TIME) + " [" + tag + "] " + text + '\n';
		try {
			sink.append(line);
		} catch (RuntimeException e) {
			report(IStatus.WARNING, "Could not write to the Kiro ACP console", e);
		}
	}

	private void report(int severity, String message, Throwable cause) {
		try {
			log.log(new Status(severity, PreferenceSettings.QUALIFIER, String.valueOf(message), cause));
		} catch (RuntimeException e) {
			// The platform log is going down with the platform.
		}
	}
}
