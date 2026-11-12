package io.github.liaoalia.kiroacp.ui.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.ILogListener;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.ui.PlatformUI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.osgi.framework.Bundle;

import io.github.liaoalia.kiroacp.core.host.Logger.Direction;

/**
 * The logger against a recording console and a recording Eclipse log.
 *
 * <p>The real console is not covered: it only takes text while a workbench
 * runs, and these tests have none.
 */
class EclipseLoggerTest {

	private static final String TIME = "\\d\\d:\\d\\d:\\d\\d\\.\\d\\d\\d ";

	private final IEclipsePreferences stored = InstanceScope.INSTANCE.getNode(PreferenceSettings.QUALIFIER);
	private final List<String> console = Collections.synchronizedList(new ArrayList<>());
	private final List<String> shown = new ArrayList<>();
	private final List<IStatus> eclipseLog = Collections.synchronizedList(new ArrayList<>());
	private final EclipseLogger logger = new EclipseLogger(new PreferenceSettings(), new RecordingLog(),
			new EclipseLogger.Sink() {
				@Override
				public void append(String lines) {
					console.add(lines);
				}

				@Override
				public void show() {
					shown.add("shown");
				}
			});

	@BeforeEach
	@AfterEach
	void forgetStoredValues() throws Exception {
		stored.clear();
	}

	@Test
	void informationGoesToTheConsoleOnly() {
		logger.info("agent started");

		assertEquals(1, console.size());
		assertTrue(console.get(0).matches(TIME + "\\[info\\] agent started\n"), console.get(0));
		assertEquals(List.of(), eclipseLog);
	}

	@Test
	void aWarningGoesToTheConsoleAndTheEclipseLog() {
		logger.warn("agent stop failed");

		assertTrue(console.get(0).matches(TIME + "\\[warn\\] agent stop failed\n"), console.get(0));
		assertEquals(1, eclipseLog.size());
		assertEquals(IStatus.WARNING, eclipseLog.get(0).getSeverity());
		assertEquals("agent stop failed", eclipseLog.get(0).getMessage());
		assertEquals(PreferenceSettings.QUALIFIER, eclipseLog.get(0).getPlugin());
	}

	@Test
	void anErrorKeepsItsCauseInTheEclipseLogAndNamesItInTheConsole() {
		IllegalStateException cause = new IllegalStateException("pipe closed");

		logger.error("session restore failed", cause);
		logger.error("no cause");

		assertTrue(console.get(0).matches(
				TIME + "\\[error\\] session restore failed: java.lang.IllegalStateException: pipe closed\n"),
				console.get(0));
		assertTrue(console.get(1).matches(TIME + "\\[error\\] no cause\n"), console.get(1));
		assertEquals(IStatus.ERROR, eclipseLog.get(0).getSeverity());
		assertEquals("session restore failed", eclipseLog.get(0).getMessage());
		assertSame(cause, eclipseLog.get(0).getException());
		assertNull(eclipseLog.get(1).getException());
	}

	@Test
	void tracingFollowsTheSettingWhileTheLoggerLives() {
		assertFalse(logger.traces());
		logger.trace(Direction.SEND, "{\"dropped\":true}");
		assertEquals(List.of(), console);

		stored.putBoolean(PreferenceSettings.LOG_PROTOCOL, true);
		assertTrue(logger.traces());
		logger.trace(Direction.SEND, "{\"id\":1}");
		logger.trace(Direction.RECV, "{\"result\":{}}");
		logger.trace(Direction.STDERR, "warning: something");

		assertEquals(3, console.size());
		assertTrue(console.get(0).matches(TIME + "\\[send\\] \\{\"id\":1\\}\n"), console.get(0));
		assertTrue(console.get(1).matches(TIME + "\\[recv\\] \\{\"result\":\\{\\}\\}\n"), console.get(1));
		assertTrue(console.get(2).matches(TIME + "\\[stderr\\] warning: something\n"), console.get(2));
		// Protocol traffic is never a problem report.
		assertEquals(List.of(), eclipseLog);

		stored.putBoolean(PreferenceSettings.LOG_PROTOCOL, false);
		assertFalse(logger.traces());
		logger.trace(Direction.RECV, "{\"dropped\":true}");
		assertEquals(3, console.size());
	}

	@Test
	void showRevealsTheConsole() {
		logger.show();

		assertEquals(List.of("shown"), shown);
	}

	@Test
	void aConsoleThatFailsDoesNotFailTheCaller() {
		EclipseLogger failing = new EclipseLogger(new PreferenceSettings(), new RecordingLog(),
				new EclipseLogger.Sink() {
					@Override
					public void append(String lines) {
						throw new IllegalStateException("console gone");
					}

					@Override
					public void show() {
						throw new IllegalStateException("console gone");
					}
				});

		failing.info("still running");
		failing.show();

		assertEquals(2, eclipseLog.size());
		assertEquals(IStatus.WARNING, eclipseLog.get(0).getSeverity());
	}

	@Test
	void everyLevelCanBeUsedFromAnotherThread() throws Exception {
		stored.putBoolean(PreferenceSettings.LOG_PROTOCOL, true);

		assertNull(onAnotherThread(() -> {
			logger.info("one");
			logger.warn("two");
			logger.error("three", new RuntimeException("why"));
			logger.trace(Direction.RECV, "four");
			logger.show();
		}));

		assertEquals(4, console.size());
		assertEquals(2, eclipseLog.size());
	}

	@Test
	void theRealConsoleAndLogTakeEverythingWithoutAWorkbenchFromAnyThread() throws Exception {
		assertFalse(PlatformUI.isWorkbenchRunning(), "these tests are written for a runtime without a workbench");
		stored.putBoolean(PreferenceSettings.LOG_PROTOCOL, true);
		EclipseLogger real = new EclipseLogger(new PreferenceSettings());

		assertNull(onAnotherThread(() -> {
			real.info("EclipseLoggerTest: an information line");
			real.trace(Direction.SEND, "{}");
			real.show();
			// More than a console queues: a writer must not wait for a UI thread that never comes.
			for (int i = 0; i < 5_000; i++) {
				real.info("EclipseLoggerTest: line " + i);
			}
		}));
		assertTrue(real.traces());
	}

	private static Throwable onAnotherThread(Runnable work) throws InterruptedException {
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread other = new Thread(() -> {
			try {
				work.run();
			} catch (RuntimeException | Error e) {
				failure.set(e);
			}
		});
		other.start();
		other.join();
		return failure.get();
	}

	private final class RecordingLog implements ILog {

		@Override
		public void log(IStatus status) {
			eclipseLog.add(status);
		}

		@Override
		public Bundle getBundle() {
			return null;
		}

		@Override
		public void addLogListener(ILogListener listener) {
		}

		@Override
		public void removeLogListener(ILogListener listener) {
		}
	}
}
