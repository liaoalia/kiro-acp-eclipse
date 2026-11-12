package io.github.liaoalia.kiroacp.core.session;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.liaoalia.kiroacp.core.host.Subscription;
import io.github.liaoalia.kiroacp.core.model.SessionStatus;
import io.github.liaoalia.kiroacp.core.model.SessionSummary;
import io.github.liaoalia.kiroacp.core.model.ToWebview;

/** The session list of each page: who is in it, in which order, and when to send it again. */
final class SessionListing {

	static final long DEBOUNCE_MS = 50;

	private final ChatController c;
	private Subscription timer;

	SessionListing(ChatController controller) {
		this.c = controller;
	}

	SessionStatus statusOf(ChatSession session) {
		if (c.lifecycle.agentDown()) {
			return SessionStatus.ERROR;
		}
		// A permission request only exists mid-turn; waiting for the user wins over "running".
		if (!c.pendingFor(session.id()).isEmpty()) {
			return SessionStatus.NEEDS_INPUT;
		}
		return session.turnActive ? SessionStatus.RUNNING : SessionStatus.IDLE;
	}

	/** Most recently used first; sessions used in the same millisecond, newest first. */
	List<ChatSession> sorted() {
		List<ChatSession> all = new ArrayList<>(c.sessions.values());
		all.sort(Comparator.comparingLong((ChatSession s) -> s.updatedAt).thenComparingInt(s -> s.seq).reversed());
		return all;
	}

	/** Summaries of the sessions of {@code groupId} (every session when {@code null}), most recent first. */
	List<SessionSummary> summaries(String groupId) {
		List<SessionSummary> out = new ArrayList<>();
		for (ChatSession session : sorted()) {
			if (groupId == null || groupId.equals(session.groupId)) {
				out.add(new SessionSummary(session.id(), session.title(), statusOf(session), session.updatedAt));
			}
		}
		return out;
	}

	/**
	 * Post {@code sessions} to every page, debounced and only when that
	 * page's list changed. {@code updatedAt} moves with every streamed chunk,
	 * so the comparison rounds it to the minute: the page must not rebuild
	 * its session panel (and lose clicks) every 50 ms during a turn.
	 */
	void schedule() {
		if (timer != null || c.closed) {
			return;
		}
		timer = c.host.scheduler().schedule(DEBOUNCE_MS, () -> {
			timer = null;
			if (!c.closed) {
				c.async.guarded(this::post);
			}
		});
	}

	void stop() {
		if (timer != null) {
			timer.close();
			timer = null;
		}
	}

	private void post() {
		record Entry(List<SessionSummary> sessions, String key) {
		}
		Map<String, Entry> cache = new HashMap<>();
		for (PageBinding page : List.copyOf(c.pages)) {
			Entry entry = cache.computeIfAbsent(String.valueOf(page.groupId) + (page.groupId == null ? "" : "#"), key -> {
				List<SessionSummary> sessions = summaries(page.groupId);
				StringBuilder text = new StringBuilder();
				for (SessionSummary s : sessions) {
					text.append(s.id()).append('\u0000').append(s.title()).append('\u0000').append(s.status()).append('\u0000')
							.append(Math.floorDiv(s.updatedAt(), 60_000L)).append('\u0001');
				}
				return new Entry(sessions, text.toString());
			});
			if (entry.key().equals(page.lastSessionsKey)) {
				continue;
			}
			page.lastSessionsKey = entry.key();
			c.postPage(page, new ToWebview.Sessions(entry.sessions()));
		}
	}
}
