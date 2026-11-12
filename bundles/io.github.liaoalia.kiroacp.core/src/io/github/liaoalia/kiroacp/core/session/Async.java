package io.github.liaoalia.kiroacp.core.session;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

import io.github.liaoalia.kiroacp.core.host.Logger;
import io.github.liaoalia.kiroacp.core.host.UiScheduler;

/**
 * Brings what happens elsewhere onto the controller thread.
 *
 * <p>The controller's flows are chains of futures. Every future they wait
 * for is one made by {@link #ui}, so it completes on the controller thread,
 * and every continuation is attached on that thread: so all of them run
 * there, and what the agent wrote in some order is handled in that order.
 */
final class Async {

	private final UiScheduler scheduler;
	private final Logger logger;

	Async(UiScheduler scheduler, Logger logger) {
		this.scheduler = scheduler;
		this.logger = logger;
	}

	static <T> CompletableFuture<T> done(T value) {
		return CompletableFuture.completedFuture(value);
	}

	/**
	 * The same outcome, delivered on the controller thread. Always through
	 * the scheduler's queue, also when the source is complete: whatever was
	 * queued before it (earlier updates of the agent) is handled first.
	 */
	<T> CompletableFuture<T> ui(CompletionStage<T> source) {
		CompletableFuture<T> out = new CompletableFuture<>();
		if (source == null) {
			out.complete(null);
			return out;
		}
		source.whenComplete((value, error) -> scheduler.execute(() -> {
			if (error != null) {
				out.completeExceptionally(unwrap(error));
			} else {
				out.complete(value);
			}
		}));
		return out;
	}

	/** Runs the task on the controller thread: now when already there, else queued. */
	void run(Runnable task) {
		if (scheduler.isControllerThread()) {
			guarded(task);
		} else {
			scheduler.execute(() -> guarded(task));
		}
	}

	/** Queues the task on the controller thread, behind whatever is queued already. */
	void later(Runnable task) {
		scheduler.execute(() -> guarded(task));
	}

	/** Starts a flow on the controller thread; the result completes there too. */
	<T> CompletableFuture<T> call(Supplier<CompletableFuture<T>> flow) {
		if (scheduler.isControllerThread()) {
			return start(flow);
		}
		CompletableFuture<T> out = new CompletableFuture<>();
		scheduler.execute(() -> start(flow).whenComplete((value, error) -> {
			if (error != null) {
				out.completeExceptionally(unwrap(error));
			} else {
				out.complete(value);
			}
		}));
		return out;
	}

	/** A flow nobody waits for: its failure is at least logged. */
	void detached(CompletionStage<?> flow, String what) {
		flow.whenComplete((done, error) -> {
			if (error != null) {
				Throwable cause = unwrap(error);
				logger.error(what + " failed: " + message(cause), cause);
			}
		});
	}

	void guarded(Runnable task) {
		try {
			task.run();
		} catch (RuntimeException e) {
			logger.error("chat controller: " + message(e), e);
		}
	}

	private static <T> CompletableFuture<T> start(Supplier<CompletableFuture<T>> flow) {
		try {
			return flow.get();
		} catch (RuntimeException e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	static Throwable unwrap(Throwable error) {
		Throwable current = error;
		while ((current instanceof CompletionException || current instanceof ExecutionException) && current.getCause() != null) {
			current = current.getCause();
		}
		return current;
	}

	/** What to tell the user about a failure. */
	static String message(Throwable error) {
		Throwable cause = unwrap(error);
		String message = cause.getMessage();
		return message != null && !message.isEmpty() ? message : cause.toString();
	}
}
