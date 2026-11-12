package io.github.liaoalia.kiroacp.ui.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.eclipse.swt.widgets.Display;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.liaoalia.kiroacp.core.host.Subscription;

/** The test thread owns the display and pumps its events; no window is created. */
class DisplaySchedulerTest {

	private static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(30);

	private Display display;
	private boolean ownDisplay;
	private DisplayScheduler scheduler;
	private final List<String> ran = Collections.synchronizedList(new ArrayList<>());

	@BeforeEach
	void createDisplay() {
		display = Display.getCurrent();
		if (display == null) {
			display = new Display();
			ownDisplay = true;
		}
		scheduler = new DisplayScheduler(display);
	}

	@AfterEach
	void disposeDisplay() {
		if (ownDisplay && !display.isDisposed()) {
			display.dispose();
		}
	}

	@Test
	void aTaskRunsOnTheDisplayThreadAfterTheCallersWork() {
		AtomicReference<Boolean> onControllerThread = new AtomicReference<>();

		scheduler.execute(() -> {
			onControllerThread.set(scheduler.isControllerThread());
			ran.add("task");
		});
		ran.add("caller");

		await(() -> ran.size() == 2, "the task to run");
		assertEquals(List.of("caller", "task"), ran);
		assertEquals(Boolean.TRUE, onControllerThread.get());
	}

	@Test
	void tasksRunInTheOrderTheyWereGiven() {
		for (int i = 0; i < 5; i++) {
			String name = "task " + i;
			scheduler.execute(() -> ran.add(name));
		}

		await(() -> ran.size() == 5, "the tasks to run");
		assertEquals(List.of("task 0", "task 1", "task 2", "task 3", "task 4"), ran);
	}

	@Test
	void aTaskGivenOnAnotherThreadStillRunsOnTheDisplayThread() throws Exception {
		AtomicReference<Thread> ranOn = new AtomicReference<>();
		AtomicReference<Boolean> otherIsController = new AtomicReference<>();

		assertNull(onAnotherThread(() -> {
			otherIsController.set(scheduler.isControllerThread());
			scheduler.execute(() -> ranOn.set(Thread.currentThread()));
		}));

		await(() -> ranOn.get() != null, "the task to run");
		assertSame(Thread.currentThread(), ranOn.get());
		assertEquals(Boolean.FALSE, otherIsController.get());
		assertTrue(scheduler.isControllerThread());
	}

	@Test
	void aScheduledTaskRunsLaterOnTheDisplayThread() {
		AtomicReference<Thread> ranOn = new AtomicReference<>();

		scheduler.schedule(300, () -> ran.add("late"));
		scheduler.schedule(1, () -> {
			ranOn.set(Thread.currentThread());
			ran.add("early");
		});

		assertEquals(List.of(), ran);
		await(() -> ran.size() == 2, "both tasks to run");
		assertEquals(List.of("early", "late"), ran);
		assertSame(Thread.currentThread(), ranOn.get());
	}

	@Test
	void aCancelledTaskDoesNotRun() {
		Subscription cancelled = scheduler.schedule(20, () -> ran.add("cancelled"));
		// Due after the cancelled one, so when it has run the other one's time has passed.
		scheduler.schedule(150, () -> ran.add("marker"));

		cancelled.close();
		cancelled.close();

		await(() -> ran.contains("marker"), "the marker to run");
		assertEquals(List.of("marker"), ran);
	}

	@Test
	void cancellingAfterTheTaskRanIsHarmless() {
		Subscription done = scheduler.schedule(1, () -> ran.add("done"));
		await(() -> ran.contains("done"), "the task to run");

		done.close();

		scheduler.execute(() -> ran.add("after"));
		await(() -> ran.contains("after"), "the next task to run");
		assertEquals(List.of("done", "after"), ran);
	}

	@Test
	void anotherThreadCanScheduleAndCancel() throws Exception {
		AtomicReference<Subscription> cancelled = new AtomicReference<>();

		assertNull(onAnotherThread(() -> {
			cancelled.set(scheduler.schedule(20, () -> ran.add("cancelled")));
			scheduler.schedule(1, () -> ran.add("kept"));
			scheduler.schedule(150, () -> ran.add("marker"));
			cancelled.get().close();
		}));

		await(() -> ran.contains("marker"), "the marker to run");
		assertEquals(List.of("kept", "marker"), ran);
	}

	@Test
	void aTaskCancelledOnAnotherThreadAfterItsTimerWasSetDoesNotRun() throws Exception {
		Subscription cancelled = scheduler.schedule(20, () -> ran.add("cancelled"));
		scheduler.schedule(150, () -> ran.add("marker"));

		assertNull(onAnotherThread(cancelled::close));

		await(() -> ran.contains("marker"), "the marker to run");
		assertEquals(List.of("marker"), ran);
	}

	@Test
	void aDisposedDisplayRunsNothingAndThrowsNothing() throws Exception {
		// Disposing a display this test did not create would take it from its owner.
		assumeTrue(ownDisplay);
		Subscription pending = scheduler.schedule(10_000, () -> ran.add("pending"));

		display.dispose();

		scheduler.execute(() -> ran.add("executed"));
		scheduler.schedule(1, () -> ran.add("scheduled")).close();
		pending.close();
		assertFalse(scheduler.isControllerThread());
		assertNull(onAnotherThread(() -> {
			scheduler.execute(() -> ran.add("executed elsewhere"));
			scheduler.schedule(1, () -> ran.add("scheduled elsewhere")).close();
			scheduler.isControllerThread();
		}));
		assertEquals(List.of(), ran);
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

	/** Runs the event loop until the condition holds or the timeout passes. */
	private void await(BooleanSupplier condition, String what) {
		long deadline = System.nanoTime() + TIMEOUT_NANOS;
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > deadline) {
				fail("Timed out waiting for " + what + "; ran: " + ran);
			}
			if (!display.readAndDispatch()) {
				// Wake up again even when nothing else is due.
				display.timerExec(50, () -> {
				});
				display.sleep();
			}
		}
	}
}
