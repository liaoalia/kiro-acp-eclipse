package io.github.liaoalia.kiroacp.core.services;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The threads the services do their blocking work on. All are daemons and end
 * when idle, so a service nobody closed keeps neither the JVM nor a thread.
 */
final class Workers {

	private static final long IDLE_SECONDS = 30;

	private Workers() {
	}

	/** One thread: tasks run one at a time, in the order they were handed in. */
	static ThreadPoolExecutor single(String name) {
		ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, IDLE_SECONDS, TimeUnit.SECONDS,
				new LinkedBlockingQueue<>(), factory(name));
		executor.allowCoreThreadTimeOut(true);
		return executor;
	}

	/** A thread per task that finds none idle: for work that may wait on the user. */
	static ThreadPoolExecutor pool(String name) {
		return new ThreadPoolExecutor(0, Integer.MAX_VALUE, IDLE_SECONDS, TimeUnit.SECONDS, new SynchronousQueue<>(),
				factory(name));
	}

	static Thread daemon(String name, Runnable task) {
		Thread thread = new Thread(task, name);
		thread.setDaemon(true);
		return thread;
	}

	private static ThreadFactory factory(String name) {
		AtomicInteger count = new AtomicInteger();
		return task -> daemon(name + "-" + count.incrementAndGet(), task);
	}
}
