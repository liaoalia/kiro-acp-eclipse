package io.github.liaoalia.kiroacp.core.session.plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

/**
 * Plan documents that exist only as records, and a user who closes them and
 * presses their buttons.
 */
public final class FakePlanDocuments implements PlanDocuments {

	private final Map<String, Content> open = new LinkedHashMap<>();
	private final Map<String, Approval> approvals = new LinkedHashMap<>();
	private final BooleanSupplier onControllerThread;
	private final Runnable changed;
	/** {@code show}, {@code update} and {@code close} with the session they were for, in order. */
	public final List<String> calls = new CopyOnWriteArrayList<>();
	public final List<String> problems = new CopyOnWriteArrayList<>();
	/** Makes {@link #show} fail, as an IDE without a window does. */
	public volatile String refusal;

	/**
	 * @param onControllerThread whether the caller is on the thread the documents belong to
	 * @param changed            wakes whoever waits for a document to change
	 */
	public FakePlanDocuments(BooleanSupplier onControllerThread, Runnable changed) {
		this.onControllerThread = onControllerThread;
		this.changed = changed;
	}

	private void called(String what, String sessionId) {
		if (!onControllerThread.getAsBoolean()) {
			problems.add(what + " " + sessionId + " off the controller thread");
		}
		calls.add(what + " " + sessionId);
	}

	@Override
	public void show(String sessionId, Content content, Approval approval) {
		called("show", sessionId);
		if (refusal != null) {
			throw new IllegalStateException(refusal);
		}
		synchronized (this) {
			open.put(sessionId, content);
			approvals.put(sessionId, approval);
		}
		changed.run();
	}

	@Override
	public void update(String sessionId, Content content) {
		called("update", sessionId);
		synchronized (this) {
			if (open.containsKey(sessionId)) {
				open.put(sessionId, content);
			}
		}
		changed.run();
	}

	@Override
	public boolean isOpen(String sessionId) {
		synchronized (this) {
			return open.containsKey(sessionId);
		}
	}

	@Override
	public void close(String sessionId) {
		called("close", sessionId);
		synchronized (this) {
			open.remove(sessionId);
			approvals.remove(sessionId);
		}
		changed.run();
	}

	// --- what a test sees and does ---

	/** The text the session's document shows, or {@code null} when none is open. */
	public synchronized String text(String sessionId) {
		Content content = open.get(sessionId);
		return content != null ? content.markdown() : null;
	}

	/** Whether the session's document offers Approve and Reject. */
	public synchronized boolean offersApproval(String sessionId) {
		Content content = open.get(sessionId);
		return content != null && content.approvalPending();
	}

	public synchronized List<String> openSessions() {
		return new ArrayList<>(open.keySet());
	}

	/** The user closes the document. */
	public void userCloses(String sessionId) {
		synchronized (this) {
			open.remove(sessionId);
			approvals.remove(sessionId);
		}
		changed.run();
	}

	/** The buttons of the session's document. Press them on the controller thread. */
	public synchronized Approval buttons(String sessionId) {
		return approvals.get(sessionId);
	}
}
