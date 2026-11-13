package io.github.liaoalia.kiroacp.core.session.diff;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

/**
 * Editors that exist only as records, and a user who types, saves and closes
 * in them. They behave as the compare editors do: unsaved text belongs to the
 * editor and is lost with it, and asking for the text saves it.
 */
public final class FakeDiffEditors implements DiffEditors {

	/** One open editor. Read through the accessors, which see a consistent state from any thread. */
	public final class Editor {
		private Proposal proposal;
		private Review review;
		private String saved;
		private String text;
		private boolean pending;

		public Proposal proposal() {
			synchronized (FakeDiffEditors.this) {
				return proposal;
			}
		}

		public String id() {
			return proposal().id();
		}

		/** What the proposed side shows, saved or not. */
		public String text() {
			synchronized (FakeDiffEditors.this) {
				return text;
			}
		}

		/** Whether Accept and Reject are offered. */
		public boolean pending() {
			synchronized (FakeDiffEditors.this) {
				return pending;
			}
		}

		/** The user types: the proposed side now reads {@code text}, unsaved. */
		public void type(String text) {
			synchronized (FakeDiffEditors.this) {
				this.text = text;
			}
		}

		/** The user saves. Controller thread. */
		public void save() {
			String now;
			synchronized (FakeDiffEditors.this) {
				saved = text;
				now = text;
			}
			review.saved(now);
		}

		/** The user presses Accept. Controller thread. */
		public void accept() {
			review.accept();
		}

		/** The user presses Reject. Controller thread. */
		public void reject() {
			review.reject();
		}

		/** The user closes the editor and does not save. */
		public void closeByUser() {
			synchronized (FakeDiffEditors.this) {
				open.remove(proposal.id());
			}
			record("closed by the user " + proposal.id());
		}
	}

	private final BooleanSupplier onControllerThread;
	private final Runnable changed;
	private final Map<String, Editor> open = new LinkedHashMap<>();
	/** What was asked of the editors, in order: {@code open <id> <title>}, {@code pending <id> <flag>}, {@code close <id>}. */
	public final List<String> events = new CopyOnWriteArrayList<>();
	/** Calls from the wrong thread. */
	public final List<String> problems = new CopyOnWriteArrayList<>();
	/** Makes {@link #open} fail, as an editor that cannot be created does. */
	public volatile boolean failToOpen;

	/**
	 * @param onControllerThread whether the caller is on the thread the editors belong to
	 * @param changed            told after every change, for tests that wait
	 */
	public FakeDiffEditors(BooleanSupplier onControllerThread, Runnable changed) {
		this.onControllerThread = onControllerThread;
		this.changed = changed;
	}

	@Override
	public void open(Proposal proposal, Review review) {
		check("open");
		if (failToOpen) {
			throw new IllegalStateException("no editor today");
		}
		synchronized (this) {
			Editor editor = open.computeIfAbsent(proposal.id(), id -> new Editor());
			editor.proposal = proposal;
			editor.review = review;
			editor.saved = proposal.proposed();
			editor.text = proposal.proposed();
			editor.pending = proposal.pending();
		}
		record("open " + proposal.id() + " " + proposal.title());
	}

	@Override
	public String proposedText(String id) {
		check("proposedText");
		Editor editor;
		String text;
		boolean unsaved;
		synchronized (this) {
			editor = open.get(id);
			if (editor == null) {
				return null;
			}
			text = editor.text;
			unsaved = !text.equals(editor.saved);
			editor.saved = text;
		}
		if (unsaved) {
			editor.review.saved(text);
		}
		return text;
	}

	@Override
	public void setPending(String id, boolean pending) {
		check("setPending");
		synchronized (this) {
			Editor editor = open.get(id);
			if (editor == null) {
				return;
			}
			editor.pending = pending;
		}
		record("pending " + id + " " + pending);
	}

	@Override
	public void close(String id) {
		check("close");
		synchronized (this) {
			if (open.remove(id) == null) {
				return;
			}
		}
		record("close " + id);
	}

	/** The open editors, oldest first. */
	public synchronized List<Editor> editors() {
		return new ArrayList<>(open.values());
	}

	public synchronized int count() {
		return open.size();
	}

	/** The one open editor. */
	public synchronized Editor only() {
		if (open.size() != 1) {
			throw new AssertionError("expected one editor, there are " + open.size() + "; " + events);
		}
		return open.values().iterator().next();
	}

	/** How often an editor was opened or shown again. */
	public long opens() {
		return events.stream().filter(event -> event.startsWith("open ")).count();
	}

	private void check(String call) {
		if (!onControllerThread.getAsBoolean()) {
			problems.add(call + " off the controller thread");
		}
	}

	private void record(String event) {
		events.add(event);
		changed.run();
	}
}
