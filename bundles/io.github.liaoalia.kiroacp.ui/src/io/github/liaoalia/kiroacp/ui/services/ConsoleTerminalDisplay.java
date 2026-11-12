package io.github.liaoalia.kiroacp.ui.services;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.liaoalia.kiroacp.core.host.Settings.TerminalMode;
import io.github.liaoalia.kiroacp.core.pure.output.TerminalPresentation;
import io.github.liaoalia.kiroacp.core.services.TerminalDisplay;

/**
 * Agent commands mirrored into consoles of the Console view (reference D9):
 * one console per session in shared mode, one per command in per-command
 * mode. The consoles only show; nothing typed into one reaches a command.
 *
 * <p>The view renders plain text, so escape sequences are stripped and line
 * ends normalised ({@link TerminalText}).
 *
 * <p>A per-command console stays after its command ended, for the user to
 * read, as the reference's terminals do. Unlike those it costs a document in
 * memory and an entry in the view's list, so only the last
 * {@link #FINISHED_COMMAND_CONSOLES} finished ones are kept.
 *
 * <p>Callable from any thread. No lock is held while a console is written to
 * or shown: a writer may be waiting for the UI thread to take its text, and
 * the UI thread must still be able to close the console.
 */
public final class ConsoleTerminalDisplay implements TerminalDisplay {

	/** Per-command consoles kept after their command ended. */
	static final int FINISHED_COMMAND_CONSOLES = 8;

	private final ConsoleHost host;
	private final Object lock = new Object();
	/** The live shared console of each session. */
	private final Map<String, Shown> shared = new HashMap<>();
	/** Per-command consoles whose command ended, oldest first. */
	private final Deque<Shown> finished = new ArrayDeque<>();

	/** Shows the commands in the Console view; nothing while no workbench is running. */
	public ConsoleTerminalDisplay() {
		this(new WorkbenchConsoles());
	}

	ConsoleTerminalDisplay(ConsoleHost host) {
		this.host = Objects.requireNonNull(host, "host");
	}

	@Override
	public Console open(Target target, Runnable closedByUser) {
		if (target == null || target.mode() == null || target.mode() == TerminalMode.HIDDEN) {
			return null;
		}
		Runnable onClosed = closedByUser != null ? closedByUser : () -> {
		};
		boolean isShared = target.mode() == TerminalMode.SHARED;
		if (isShared) {
			synchronized (lock) {
				Shown existing = shared.get(target.sessionId());
				if (existing != null) {
					// One console per session, whoever asks: the latest caller is the one told
					// when the user closes it.
					existing.closedByUser = onClosed;
					return existing;
				}
			}
		}
		Shown shown = new Shown(target, onClosed);
		ConsoleHost.Sink sink = host.create(name(target), shown::removed);
		if (sink == null) {
			return null;
		}
		shown.sink = sink;
		if (isShared) {
			synchronized (lock) {
				if (!shown.closed.get()) {
					shared.put(target.sessionId(), shown);
				}
			}
		}
		return shown;
	}

	private static String name(Target target) {
		String name = target.name();
		if (name != null && !name.isBlank()) {
			return name;
		}
		return target.mode() == TerminalMode.SHARED
				? TerminalPresentation.sharedTerminalName(Objects.toString(target.sessionId(), ""))
				: TerminalPresentation.commandTerminalName(Objects.toString(target.terminalId(), ""));
	}

	private final class Shown implements Console {

		private final Target target;
		private final AtomicBoolean closed = new AtomicBoolean();
		private final TerminalText text = new TerminalText();
		private volatile Runnable closedByUser;
		private volatile ConsoleHost.Sink sink;

		Shown(Target target, Runnable closedByUser) {
			this.target = target;
			this.closedByUser = closedByUser;
		}

		@Override
		public void write(String chunk) {
			if (chunk == null || chunk.isEmpty()) {
				return;
			}
			String plain;
			synchronized (text) {
				plain = text.feed(chunk);
			}
			append(plain);
		}

		@Override
		public void exited(String terminalId, Integer exitCode, String signal) {
			String rest;
			synchronized (text) {
				rest = text.flush();
			}
			append(rest);
			if (target.mode() != TerminalMode.PER_COMMAND) {
				return;
			}
			List<Shown> surplus = new ArrayList<>();
			synchronized (lock) {
				if (closed.get() || finished.contains(this)) {
					return;
				}
				finished.addLast(this);
				while (finished.size() > FINISHED_COMMAND_CONSOLES) {
					surplus.add(finished.removeFirst());
				}
			}
			surplus.forEach(Shown::close);
		}

		@Override
		public void reveal(boolean focus) {
			ConsoleHost.Sink current = sink;
			if (current != null && !closed.get()) {
				current.show(focus);
			}
		}

		@Override
		public void close() {
			if (closed.compareAndSet(false, true)) {
				forget();
				ConsoleHost.Sink current = sink;
				if (current != null) {
					current.remove();
				}
			}
		}

		/** The console is gone from the IDE. Our own {@link #close} ends here too, and is then no news. */
		void removed() {
			if (closed.compareAndSet(false, true)) {
				forget();
				closedByUser.run();
			}
		}

		private void append(String plain) {
			ConsoleHost.Sink current = sink;
			// Late output of a command whose console is gone has nowhere to go.
			if (current != null && !plain.isEmpty() && !closed.get()) {
				current.append(plain);
			}
		}

		private void forget() {
			synchronized (lock) {
				shared.remove(target.sessionId(), this);
				finished.remove(this);
			}
		}
	}
}
