package io.github.liaoalia.kiroacp.core.session;

import io.github.liaoalia.kiroacp.core.model.FromWebview;
import io.github.liaoalia.kiroacp.core.model.ToWebview;
import io.github.liaoalia.kiroacp.core.model.UiState;

/**
 * A chat page attached to the controller: it displays one session, or none
 * yet, and can be pointed at another.
 *
 * <p>{@link #onMessage}, {@link #setSession}, {@link #focus}, {@link #detach}
 * and {@link #close} may be called from any thread; off the controller thread
 * they take effect later, in the order they were called. {@link #snapshot}
 * and {@link #post} belong to the controller thread.
 */
public interface PageHandle {

	/** The session this page displays, or {@code null} when it has none yet. */
	String sessionId();

	/** The group whose sessions this page lists, or {@code null} for a page that sees every session. */
	String groupId();

	/** What the page posted. A message from a page focuses its session. */
	void onMessage(FromWebview message);

	/**
	 * Display a live session, or nothing: posts {@code session} and a
	 * {@code state} snapshot. A grouped page adopts the session into its group.
	 * An id that is not a live session displays nothing.
	 */
	void setSession(String sessionId);

	/** The user turned to this page: commands without a page act on it and on its session. */
	void focus();

	/** The {@code UiState} this page would get for {@code ready}. */
	UiState snapshot();

	/** Send to this page only, for a feature answering a request of it. */
	void post(ToWebview message);

	/** The page is gone; its sessions stay (reference: a disposed sidebar). */
	void detach();

	/** The page was closed for good: {@link #detach()}, then its group's sessions end (reference D20). */
	void close();
}
