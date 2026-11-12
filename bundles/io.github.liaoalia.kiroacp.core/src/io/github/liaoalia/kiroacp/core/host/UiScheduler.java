package io.github.liaoalia.kiroacp.core.host;

/**
 * Runs the chat controller's work on the one thread it owns its state on.
 *
 * <p>In Eclipse that is the SWT display thread; tests use a plain
 * single-threaded executor. Agent I/O never runs here.
 */
public interface UiScheduler {

	/** Runs the task on the controller thread, after the caller's current work if it is already there. */
	void execute(Runnable task);

	/**
	 * Runs the task on the controller thread after a delay.
	 *
	 * @return cancels the task if it has not started
	 */
	Subscription schedule(long delayMillis, Runnable task);

	/** @return whether the caller is on the controller thread */
	boolean isControllerThread();
}
