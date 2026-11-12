package io.github.liaoalia.kiroacp.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.liaoalia.kiroacp.core.host.ChatPage;
import io.github.liaoalia.kiroacp.core.host.Logger;
import io.github.liaoalia.kiroacp.core.host.Settings;
import io.github.liaoalia.kiroacp.core.host.Subscription;
import io.github.liaoalia.kiroacp.core.host.UiScheduler;
import io.github.liaoalia.kiroacp.core.host.Workspace;
import io.github.liaoalia.kiroacp.core.model.ToWebview;
import io.github.liaoalia.kiroacp.core.model.UiJson;
import io.github.liaoalia.kiroacp.core.pure.text.TextRange;
import io.github.liaoalia.kiroacp.core.services.ExternalActions;

/**
 * The hosts the controller tests run against. Nothing here sleeps to let
 * something happen: whatever is recorded wakes {@link Signal#await}, which
 * re-checks its condition.
 */
final class Hosts {

	static final long SECONDS = 30;

	private Hosts() {
	}

	/** What every recorder notifies and every wait listens to. */
	static final class Signal {

		synchronized void changed() {
			notifyAll();
		}

		synchronized void await(BooleanSupplier condition, String label, Object context) throws InterruptedException {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SECONDS);
			while (!condition.getAsBoolean()) {
				long left = deadline - System.nanoTime();
				if (left <= 0) {
					fail("Timed out waiting for " + label + "; " + context);
				}
				TimeUnit.NANOSECONDS.timedWait(this, left);
			}
		}
	}

	/**
	 * The controller thread: a single-threaded executor. Short delays (the
	 * debounce of the session list) run as if they had passed, right behind
	 * what is queued; long ones (the Stop watchdog) wait for {@link #fire}.
	 */
	static final class TestScheduler implements UiScheduler, AutoCloseable {

		static final long AUTOMATIC_UP_TO_MS = 100;

		private record Timer(long delay, Runnable task, AtomicBoolean cancelled) {
		}

		private final Signal signal;
		private final List<Timer> held = new ArrayList<>();
		private volatile Thread thread;
		private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
			Thread created = new Thread(task, "test-controller");
			created.setDaemon(true);
			thread = created;
			return created;
		});
		final List<Throwable> failures = new CopyOnWriteArrayList<>();

		TestScheduler(Signal signal) {
			this.signal = signal;
		}

		@Override
		public void execute(Runnable task) {
			try {
				executor.execute(() -> {
					try {
						task.run();
					} catch (Throwable e) {
						failures.add(e);
					}
				});
			} catch (RejectedExecutionException e) {
				// The test is over; a late completion has nowhere to go.
			}
		}

		@Override
		public Subscription schedule(long delayMillis, Runnable task) {
			AtomicBoolean cancelled = new AtomicBoolean();
			Runnable guarded = () -> {
				if (!cancelled.get()) {
					task.run();
				}
			};
			if (delayMillis <= AUTOMATIC_UP_TO_MS) {
				execute(guarded);
			} else {
				synchronized (held) {
					held.add(new Timer(delayMillis, guarded, cancelled));
				}
				signal.changed();
			}
			return () -> cancelled.set(true);
		}

		@Override
		public boolean isControllerThread() {
			return Thread.currentThread() == thread;
		}

		/** @return how many timers of that delay are waiting and not cancelled */
		int pending(long delayMillis) {
			synchronized (held) {
				return (int) held.stream().filter(t -> t.delay() == delayMillis && !t.cancelled().get()).count();
			}
		}

		/** Lets the waiting timers of that delay elapse; they run on the controller thread before this returns. */
		int fire(long delayMillis) throws Exception {
			List<Timer> due = new ArrayList<>();
			synchronized (held) {
				held.removeIf(timer -> {
					if (timer.delay() == delayMillis) {
						due.add(timer);
						return true;
					}
					return false;
				});
			}
			for (Timer timer : due) {
				execute(timer.task());
			}
			flush();
			return due.size();
		}

		/** Runs on the controller thread and returns what it gave. */
		<T> T call(Callable<T> task) throws Exception {
			return executor.submit(task).get(SECONDS, TimeUnit.SECONDS);
		}

		/** Returns once everything queued so far has run. */
		void flush() throws Exception {
			call(() -> null);
		}

		@Override
		public void close() {
			executor.shutdown();
		}
	}

	/** A page that keeps what it was sent, as messages and as the JSON the browser would get. */
	static final class RecordingPage implements ChatPage {

		private final String name;
		private final TestScheduler scheduler;
		private final Signal signal;
		private final List<ToWebview> messages = new CopyOnWriteArrayList<>();
		private final List<JsonNode> json = new CopyOnWriteArrayList<>();
		/** What the page would show, reduced as the page reduces it. */
		private final PageModel model = new PageModel();
		final List<String> problems = new CopyOnWriteArrayList<>();

		RecordingPage(String name, TestScheduler scheduler, Signal signal) {
			this.name = name;
			this.scheduler = scheduler;
			this.signal = signal;
		}

		@Override
		public void post(ToWebview message) {
			if (!scheduler.isControllerThread()) {
				problems.add("posted off the controller thread: " + message);
			}
			JsonNode tree = UiJson.toJson(message);
			// What the page gets must be a message of the protocol, and survive the trip.
			ToWebview back = UiJson.toWebview(tree).orElse(null);
			if (back == null || !UiJson.toJson(back).equals(tree)) {
				problems.add("does not serialise through UiJson: " + tree);
			}
			synchronized (model) {
				model.apply(tree);
			}
			messages.add(message);
			json.add(tree);
			signal.changed();
		}

		int mark() {
			return messages.size();
		}

		List<ToWebview> messages() {
			return List.copyOf(messages);
		}

		List<ToWebview> since(int mark) {
			List<ToWebview> all = messages();
			return all.subList(Math.min(mark, all.size()), all.size());
		}

		List<JsonNode> jsonSince(int mark) {
			List<JsonNode> all = List.copyOf(json);
			return all.subList(Math.min(mark, all.size()), all.size());
		}

		/** The {@code type} of every message since the mark, in order. */
		List<String> types(int mark) {
			return jsonSince(mark).stream().map(tree -> tree.path("type").asText()).toList();
		}

		<T extends ToWebview> List<T> ofType(Class<T> type, int mark) {
			return since(mark).stream().filter(type::isInstance).map(type::cast).toList();
		}

		<T extends ToWebview> List<T> ofType(Class<T> type) {
			return ofType(type, 0);
		}

		<T extends ToWebview> T last(Class<T> type) {
			List<T> all = ofType(type, 0);
			assertTrue(!all.isEmpty(), name + " got no " + type.getSimpleName());
			return all.get(all.size() - 1);
		}

		/** Waits for a message of that type since the mark that satisfies the condition, and returns the first. */
		<T extends ToWebview> T await(Class<T> type, int mark, Predicate<T> condition, String label) throws InterruptedException {
			signal.await(() -> ofType(type, mark).stream().anyMatch(condition), label + " in " + name, this);
			return ofType(type, mark).stream().filter(condition).findFirst().orElseThrow();
		}

		/** The state the page has arrived at, as the JSON of a {@code UiState}. */
		JsonNode model() {
			synchronized (model) {
				return model.state();
			}
		}

		@Override
		public String toString() {
			List<JsonNode> all = List.copyOf(json);
			return name + " got " + all.size() + " messages, the last: " + all.subList(Math.max(0, all.size() - 12), all.size());
		}
	}

	static final class FakeSettings implements Settings {
		volatile TerminalMode mode = TerminalMode.SHARED;
		volatile boolean showSettings;

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
			return 1024 * 1024;
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
			return showSettings;
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
		private final Path root;

		FakeWorkspace(Path root) {
			this.root = root;
		}

		@Override
		public List<Path> roots() {
			return List.of(root);
		}

		@Override
		public Path defaultCwd() {
			return root;
		}

		@Override
		public boolean contains(Path absolute) {
			return TextRange.isWithinRoot(absolute.toString(), root.toString());
		}

		@Override
		public String label(Path absolute) {
			return String.valueOf(absolute.getFileName());
		}
	}

	static final class RecordingLogger implements Logger {
		private final Signal signal;
		final List<String> lines = new CopyOnWriteArrayList<>();

		RecordingLogger(Signal signal) {
			this.signal = signal;
		}

		@Override
		public void info(String message) {
			add("info: " + message);
		}

		@Override
		public void warn(String message) {
			add("warn: " + message);
		}

		@Override
		public void error(String message, Throwable cause) {
			add("error: " + message);
		}

		private void add(String line) {
			lines.add(line);
			signal.changed();
		}

		boolean has(String text) {
			return lines.stream().anyMatch(line -> line.contains(text));
		}
	}

	static final class RecordingActions implements ChatActions {
		private final TestScheduler scheduler;
		private final Signal signal;
		final List<String> calls = new CopyOnWriteArrayList<>();
		final List<String> problems = new CopyOnWriteArrayList<>();

		RecordingActions(TestScheduler scheduler, Signal signal) {
			this.scheduler = scheduler;
			this.signal = signal;
		}

		@Override
		public void showLog() {
			add("showLog");
		}

		@Override
		public void openFile(String path, Integer line) {
			add("openFile " + path + " " + line);
		}

		@Override
		public void revealSession(String sessionId) {
			add("revealSession " + sessionId);
		}

		@Override
		public void turnActiveChanged(boolean active) {
			add("turnActive " + active);
		}

		private void add(String call) {
			if (!scheduler.isControllerThread()) {
				problems.add("called off the controller thread: " + call);
			}
			calls.add(call);
			signal.changed();
		}
	}

	/** Opens every URL without asking. */
	static final class OpenEverything implements ExternalActions {
		final List<String> urls = new CopyOnWriteArrayList<>();

		@Override
		public CompletionStage<OpenResult> confirmAndOpenUrl(String host, String url) {
			urls.add(url);
			return CompletableFuture.completedFuture(OpenResult.OPENED);
		}

		@Override
		public CompletionStage<Integer> runSignIn(SignIn signIn) {
			return CompletableFuture.completedFuture(0);
		}
	}

	static void assertNoProblems(List<String> problems) {
		assertEquals(List.of(), List.copyOf(problems));
	}
}
