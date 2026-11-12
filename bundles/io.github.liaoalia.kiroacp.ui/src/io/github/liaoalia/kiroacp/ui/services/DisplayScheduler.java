package io.github.liaoalia.kiroacp.ui.services;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.swt.SWTException;
import org.eclipse.swt.widgets.Display;

import io.github.liaoalia.kiroacp.core.host.Subscription;
import io.github.liaoalia.kiroacp.core.host.UiScheduler;

/**
 * The SWT display thread as the chat controller's thread.
 *
 * <p>Callable from any thread. Once the display is disposed nothing runs any
 * more and nothing throws: the agent's threads outlive the workbench for a
 * moment on shutdown and must not fail on their way out.
 */
public final class DisplayScheduler implements UiScheduler {

	private final Display display;

	public DisplayScheduler(Display display) {
		this.display = Objects.requireNonNull(display, "display");
	}

	@Override
	public void execute(Runnable task) {
		Objects.requireNonNull(task, "task");
		async(task);
	}

	@Override
	public Subscription schedule(long delayMillis, Runnable task) {
		Objects.requireNonNull(task, "task");
		int delay = (int) Math.max(0, Math.min(Integer.MAX_VALUE, delayMillis));
		AtomicBoolean cancelled = new AtomicBoolean();
		Runnable timed = () -> {
			if (!cancelled.get()) {
				task.run();
			}
		};
		// Timers can only be set and removed on the display's own thread.
		onDisplayThread(() -> {
			if (!cancelled.get()) {
				display.timerExec(delay, timed);
			}
		});
		return () -> {
			if (cancelled.compareAndSet(false, true)) {
				// The flag alone would do; removing the timer lets go of the task now.
				onDisplayThread(() -> display.timerExec(-1, timed));
			}
		};
	}

	@Override
	public boolean isControllerThread() {
		try {
			return !display.isDisposed() && display.getThread() == Thread.currentThread();
		} catch (SWTException disposedMeanwhile) {
			return false;
		}
	}

	private void onDisplayThread(Runnable work) {
		if (isControllerThread()) {
			work.run();
		} else {
			async(work);
		}
	}

	private void async(Runnable work) {
		if (display.isDisposed()) {
			return;
		}
		try {
			display.asyncExec(work);
		} catch (SWTException disposedMeanwhile) {
			// Another thread disposed the display between the check and the call.
		}
	}
}
