package io.github.liaoalia.kiroacp.core.services;

import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.liaoalia.kiroacp.core.host.Logger;
import io.github.liaoalia.kiroacp.core.host.Settings;
import io.github.liaoalia.kiroacp.core.host.Subscription;
import io.github.liaoalia.kiroacp.core.host.Workspace;
import io.github.liaoalia.kiroacp.core.pure.text.TextRange;

/** The hosts the service tests run against, and how they wait. */
final class Fakes {

	static final ObjectMapper MAPPER = new ObjectMapper();
	static final long SECONDS = 30;

	private Fakes() {
	}

	static JsonNode json(String text) {
		try {
			return MAPPER.readTree(text);
		} catch (JsonProcessingException e) {
			throw new IllegalArgumentException(text, e);
		}
	}

	static <T> T get(CompletableFuture<T> future) throws Exception {
		return future.get(SECONDS, TimeUnit.SECONDS);
	}

	/** @return what the future failed with */
	static Throwable failure(CompletableFuture<?> future) throws Exception {
		try {
			Object value = future.get(SECONDS, TimeUnit.SECONDS);
			return fail("expected a failure, got " + value);
		} catch (ExecutionException e) {
			return e.getCause();
		}
	}

	/**
	 * Waits for something no recorder announces (a process ending, a future
	 * completing elsewhere) by looking again every few milliseconds.
	 */
	static void await(BooleanSupplier condition, String label) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SECONDS);
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > deadline) {
				fail("Timed out waiting for " + label);
			}
			Thread.sleep(5);
		}
	}

	static final class FakeSettings implements Settings {
		volatile TerminalMode mode = TerminalMode.SHARED;
		volatile int outputByteLimit = 1024 * 1024;

		@Override
		public String cliPath() {
			return "kiro-cli";
		}

		@Override
		public List<String> cliArgs() {
			return List.of();
		}

		@Override
		public TerminalMode terminalMode() {
			return mode;
		}

		@Override
		public int terminalOutputByteLimit() {
			return outputByteLimit;
		}

		@Override
		public boolean logProtocol() {
			return false;
		}

		@Override
		public AutoOpenDiffs autoOpenDiffs() {
			return AutoOpenDiffs.NEVER;
		}

		@Override
		public boolean attachActiveFile() {
			return false;
		}

		@Override
		public boolean showSettingsByDefault() {
			return false;
		}

		@Override
		public boolean sessionPanelCollapsed() {
			return false;
		}

		@Override
		public boolean rememberSessionOptions() {
			return false;
		}

		@Override
		public Subscription onChange(Runnable listener) {
			return () -> {
			};
		}
	}

	static final class FakeWorkspace implements Workspace {
		private final List<Path> roots;

		FakeWorkspace(Path... roots) {
			this.roots = List.of(roots);
		}

		@Override
		public List<Path> roots() {
			return roots;
		}

		@Override
		public Path defaultCwd() {
			return roots.isEmpty() ? Path.of(System.getProperty("user.home")) : roots.get(0);
		}

		@Override
		public boolean contains(Path absolute) {
			return roots.stream().anyMatch(root -> TextRange.isWithinRoot(absolute.toString(), root.toString()));
		}

		@Override
		public String label(Path absolute) {
			return String.valueOf(absolute.getFileName());
		}
	}

	static final class RecordingLogger implements Logger {
		final List<String> lines = new CopyOnWriteArrayList<>();

		@Override
		public void info(String message) {
			lines.add("info: " + message);
		}

		@Override
		public void warn(String message) {
			lines.add("warn: " + message);
		}

		@Override
		public void error(String message, Throwable cause) {
			lines.add("error: " + message);
		}

		boolean has(String text) {
			return lines.stream().anyMatch(line -> line.contains(text));
		}
	}

	/** A display that keeps what it was given; everything recorded wakes {@link #await}. */
	static final class RecordingDisplay implements TerminalDisplay {
		private final List<RecordingConsole> consoles = new ArrayList<>();
		/** Answer {@code open} with "nothing can be shown". */
		volatile boolean unavailable;

		@Override
		public Console open(Target target, Runnable closedByUser) {
			if (unavailable) {
				return null;
			}
			RecordingConsole console = new RecordingConsole(target, closedByUser);
			synchronized (this) {
				consoles.add(console);
				notifyAll();
			}
			return console;
		}

		synchronized List<RecordingConsole> consoles() {
			return List.copyOf(consoles);
		}

		synchronized RecordingConsole only() {
			if (consoles.size() != 1) {
				fail("expected one console, got " + consoles.size());
			}
			return consoles.get(0);
		}

		/** Waits until the condition holds; it is re-checked whenever something was recorded. */
		synchronized void await(BooleanSupplier condition, String label) throws InterruptedException {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SECONDS);
			while (!condition.getAsBoolean()) {
				long left = deadline - System.nanoTime();
				if (left <= 0) {
					fail("Timed out waiting for " + label + "; consoles: " + consoles);
				}
				TimeUnit.NANOSECONDS.timedWait(this, left);
			}
		}

		final class RecordingConsole implements Console {
			final Target target;
			final Runnable closedByUser;
			private final StringBuilder text = new StringBuilder();
			private final List<String> events = new ArrayList<>();

			RecordingConsole(Target target, Runnable closedByUser) {
				this.target = target;
				this.closedByUser = closedByUser;
			}

			@Override
			public void write(String chunk) {
				synchronized (RecordingDisplay.this) {
					text.append(chunk);
					RecordingDisplay.this.notifyAll();
				}
			}

			@Override
			public void exited(String terminalId, Integer exitCode, String signal) {
				record("exited " + terminalId + " " + exitCode + " " + signal);
			}

			@Override
			public void reveal(boolean focus) {
				record("reveal " + focus);
			}

			@Override
			public void close() {
				record("close");
			}

			private void record(String event) {
				synchronized (RecordingDisplay.this) {
					events.add(event);
					RecordingDisplay.this.notifyAll();
				}
			}

			String text() {
				synchronized (RecordingDisplay.this) {
					return text.toString();
				}
			}

			List<String> events() {
				synchronized (RecordingDisplay.this) {
					return List.copyOf(events);
				}
			}

			void awaitText(String needle) throws InterruptedException {
				RecordingDisplay.this.await(() -> text.indexOf(needle) >= 0, "\"" + needle + "\" in the console");
			}

			@Override
			public String toString() {
				return target + " " + events + " " + text;
			}
		}
	}
}
