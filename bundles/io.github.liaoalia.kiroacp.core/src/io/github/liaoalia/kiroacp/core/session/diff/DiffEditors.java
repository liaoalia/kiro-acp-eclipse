package io.github.liaoalia.kiroacp.core.session.diff;

/**
 * The side-by-side editors proposed changes are reviewed in: the original
 * text on one side, read-only, and the agent's proposal on the other,
 * editable, with Accept and Reject at hand (reference D7; compare editors in
 * Eclipse).
 *
 * <p>An implementation only shows and reports. Which proposal belongs to
 * which request, what Accept and Reject do and what ends up in the file is
 * decided by {@link DiffReview}. Every method is called on the controller
 * thread, which is the UI thread of the IDE, and none may wait for the user.
 */
public interface DiffEditors {

	/**
	 * What one editor shows.
	 *
	 * @param id       names the editor from now on; never used twice for different proposals
	 * @param path     absolute path of the file the change is proposed for
	 * @param title    the editor's title
	 * @param original the text the change starts from; empty for a file that does not exist
	 * @param proposed the text the proposed side starts with
	 * @param pending  whether a permission request waits for this proposal: only then Accept and
	 *                 Reject are offered
	 */
	record Proposal(String id, String path, String title, String original, String proposed, boolean pending) {
	}

	/** What the user does in an editor. To be called on the controller thread. */
	interface Review {

		/**
		 * The user saved the proposed side: {@code text} is the proposal from now
		 * on, also once the editor is closed. Unsaved edits are lost with their
		 * editor, as everywhere.
		 */
		void saved(String text);

		void accept();

		void reject();
	}

	/**
	 * Show the editor of a proposal without taking the focus from the chat.
	 * When one is already open for {@code proposal.id()} it is brought up and
	 * made to show {@code proposal}; it has no unsaved edits then, because the
	 * caller asked for {@link #proposedText} first.
	 */
	void open(Proposal proposal, Review review);

	/**
	 * The proposed side as its editor has it now, unsaved edits included. The
	 * editor may save them on this occasion; {@link Review#saved} is then
	 * called before this returns.
	 *
	 * @return {@code null} when no editor is open for {@code id}
	 */
	String proposedText(String id);

	/** A permission request for the proposal arrived or went: offer Accept and Reject, or stop offering them. */
	void setPending(String id, boolean pending);

	/** Close the editor without asking about unsaved edits. Nothing happens when none is open for {@code id}. */
	void close(String id);
}
