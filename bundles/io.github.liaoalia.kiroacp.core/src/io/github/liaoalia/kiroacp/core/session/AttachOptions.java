package io.github.liaoalia.kiroacp.core.session;

/**
 * How a chat page takes part in the sessions (reference D19, D20).
 *
 * @param prefer  which session the page gets when it has none and the agent is ready
 * @param groupId the page's session group: it lists and switches between the sessions of that
 *                group only, and {@link PageHandle#close()} ends them. {@code null}: the page
 *                lists every session and owns none
 */
public record AttachOptions(Prefer prefer, String groupId) {

	public enum Prefer {
		/** A session nobody uses yet, else a new one. */
		FRESH,
		/** The focused session when the page may list it, else as {@link #FRESH}. */
		FOCUSED
	}

	public AttachOptions {
		prefer = prefer != null ? prefer : Prefer.FOCUSED;
	}

	/** A page with sessions of its own, starting on a fresh one: a chat view. */
	public static AttachOptions ownGroup(String groupId) {
		return new AttachOptions(Prefer.FRESH, groupId);
	}

	/** A page that sees every session and starts on the focused one. */
	public static AttachOptions shared() {
		return new AttachOptions(Prefer.FOCUSED, null);
	}
}
