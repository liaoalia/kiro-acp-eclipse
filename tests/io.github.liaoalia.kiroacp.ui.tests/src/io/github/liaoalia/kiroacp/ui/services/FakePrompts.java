package io.github.liaoalia.kiroacp.ui.services;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;

/** Records what would have been a dialog; the test answers. Nothing is ever shown. */
final class FakePrompts implements Prompts {

	/** A question asked and the answer the test gives it. */
	record Asked(Question question, CompletableFuture<Boolean> answer) {
	}

	/** A notice on show. */
	static final class Told implements Notice {

		final String title;
		final String message;
		final String done;
		final CompletableFuture<Void> acknowledged = new CompletableFuture<>();
		volatile int dismissals;

		Told(String title, String message, String done) {
			this.title = title;
			this.message = message;
			this.done = done;
		}

		@Override
		public CompletionStage<Void> acknowledged() {
			return acknowledged;
		}

		@Override
		public synchronized void dismiss() {
			dismissals++;
		}
	}

	final List<Asked> asked = new CopyOnWriteArrayList<>();
	final List<Told> told = new CopyOnWriteArrayList<>();

	@Override
	public CompletionStage<Boolean> ask(Question question) {
		Asked entry = new Asked(question, new CompletableFuture<>());
		asked.add(entry);
		return entry.answer();
	}

	@Override
	public Notice tell(String title, String message, String done) {
		Told notice = new Told(title, message, done);
		told.add(notice);
		return notice;
	}
}
