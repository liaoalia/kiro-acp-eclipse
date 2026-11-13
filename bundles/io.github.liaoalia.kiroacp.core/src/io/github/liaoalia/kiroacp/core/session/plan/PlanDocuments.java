package io.github.liaoalia.kiroacp.core.session.plan;

/**
 * The read-only documents the plans of the sessions are shown in, one per
 * session (the reference's {@code kiro-plan:} document, D11; an editor in
 * Eclipse).
 *
 * <p>An implementation only shows and reports. What the text is, when it
 * changes and what Approve and Reject do is decided by {@link PlanDocument}.
 * Every method is called on the controller thread, which is the UI thread of
 * the IDE, and none may wait for the user.
 */
public interface PlanDocuments {

	/**
	 * What a document shows.
	 *
	 * @param markdown        the whole text
	 * @param approvalPending whether a request to leave planning waits for the user: only then
	 *                        Approve and Reject are offered
	 */
	record Content(String markdown, boolean approvalPending) {
	}

	/** What the user does in a document. To be called on the controller thread. */
	interface Approval {

		void approve();

		void reject();
	}

	/**
	 * Show the document of a session without taking the focus from the chat.
	 * When it is open already it is brought up and made to show {@code content}.
	 *
	 * @throws IllegalStateException when there is nowhere to show it
	 */
	void show(String sessionId, Content content, Approval approval);

	/** The text or the pending approval changed. Nothing happens while no document is open for the session. */
	void update(String sessionId, Content content);

	/** @return whether a document of the session is open; {@code false} once the user closed it */
	boolean isOpen(String sessionId);

	/** Close the session's document. Nothing happens when none is open. */
	void close(String sessionId);
}
