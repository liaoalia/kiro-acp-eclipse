package io.github.liaoalia.kiroacp.core.session;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import io.github.liaoalia.kiroacp.core.acp.api.KiroAgent;
import io.github.liaoalia.kiroacp.core.model.ConnectionStatus;
import io.github.liaoalia.kiroacp.core.model.NoticeView.Severity;

/**
 * What a sign-in restart brings back (reference D25 and its 0.3.11
 * addendum): the sessions belong to the agent process, the sign-in replaces
 * that process, and the user must lose neither the conversation a page shows
 * nor the others in its list. Controller thread only.
 */
final class SessionRestore {

	/**
	 * A session to load back.
	 *
	 * @param title   what the list called it, for the warning when it cannot be loaded
	 * @param pages   the pages that displayed it
	 * @param focused whether it was the focused session
	 */
	record Entry(String sessionId, String groupId, String agentTitle, String title, long updatedAt, List<PageBinding> pages,
			boolean focused) {

		int rank() {
			return focused ? 0 : !pages.isEmpty() ? 1 : 2;
		}
	}

	private record Failed(Entry entry, String message) {
	}

	private final ChatController c;
	/** Groups whose page was closed since the last plan was made: nothing is restored into them (reference D20). */
	private final Set<String> closedGroups = new HashSet<>();

	SessionRestore(ChatController controller) {
		this.c = controller;
	}

	void groupClosed(String groupId) {
		if (groupId != null) {
			closedGroups.add(groupId);
		}
	}

	/**
	 * Every live session with a conversation (an empty one is not worth a
	 * {@code session/load}), the focused one first, then the displayed ones,
	 * then the rest, most recent first. Sessions still restoring or closing
	 * are left out: their own pending request ends with the old process and
	 * cleans up after itself.
	 */
	List<Entry> plan() {
		closedGroups.clear();
		List<Entry> entries = new ArrayList<>();
		for (ChatSession session : c.listing.sorted()) {
			if (session.loading || session.closing || session.transcript.items().isEmpty()) {
				continue;
			}
			String id = session.id();
			entries.add(new Entry(id, session.groupId, session.agentTitle, session.title(), session.updatedAt,
					c.pages.stream().filter(page -> id.equals(page.displayed)).toList(), id.equals(c.focusedId)));
		}
		// Stable: within a rank the order of the list stays.
		entries.sort(Comparator.comparingInt(Entry::rank));
		return entries;
	}

	/**
	 * Load the plan back into the process that was just started, then give
	 * every page still without a session a fresh one and tell, in the page of
	 * its group, which sessions the agent could not load.
	 */
	CompletableFuture<Void> run(List<Entry> plan) {
		KiroAgent agent = c.agent;
		if (agent == null || c.agentInfo == null) {
			// The start failed and said so.
			return Async.done(null);
		}
		List<Failed> failed = new ArrayList<>();
		CompletableFuture<Void> loaded;
		if (c.agentInfo.canLoadSessions()) {
			loaded = loadNext(agent, plan, 0, failed);
		} else {
			c.logger.warn("the agent cannot load sessions: " + plan.size() + " session(s) not restored");
			loaded = Async.done(null);
		}
		return loaded.thenCompose(done -> {
			if (c.agent != agent || c.closed) {
				return Async.<Void>done(null);
			}
			return c.lifecycle.openFirstSessions().thenRun(() -> {
				if (c.agent != agent) {
					return;
				}
				String shown = c.focusedPage != null ? c.focusedPage.displayed : null;
				if (shown != null) {
					c.focus(shown);
				} else if (c.focusedId == null) {
					c.listing.sorted().stream().findFirst().ifPresent(newest -> c.focus(newest.id()));
				}
				for (Failed each : failed) {
					// Told in the page the session belonged to, which shows a fresh session by
					// now; a group no page lists has the log line only.
					Entry entry = each.entry();
					c.pages.stream().filter(page -> entry.groupId().equals(page.groupId) && page.displayed != null)
							.map(page -> c.sessions.get(page.displayed)).filter(session -> session != null).findFirst()
							.ifPresent(target -> c.tell(target, Severity.WARNING, "Could not restore session \"" + entry.title()
									+ "\" (" + entry.sessionId() + "): " + each.message()));
				}
			});
		});
	}

	/**
	 * One session at a time, the pages' own first. A page shows its session
	 * again unless it shows another one by now. A session whose page was
	 * closed meanwhile, or that something else restored already, is skipped;
	 * once the agent is no longer ready (it exited, or wants a sign-in again)
	 * the rest are left for the list of past sessions.
	 */
	private CompletableFuture<Void> loadNext(KiroAgent agent, List<Entry> plan, int index, List<Failed> failed) {
		for (int i = index; i < plan.size(); i++) {
			if (c.agent != agent || agent.status() != ConnectionStatus.READY) {
				c.logger.warn((plan.size() - i) + " session(s) not restored: the agent is "
						+ agent.status().name().toLowerCase(Locale.ROOT));
				break;
			}
			Entry entry = plan.get(i);
			if (closedGroups.contains(entry.groupId()) || c.sessions.containsKey(entry.sessionId())) {
				continue;
			}
			List<PageBinding> pages = entry.pages().stream().filter(page -> c.pages.contains(page) && page.displayed == null)
					.toList();
			int next = i + 1;
			return c.roster.loadInto(agent, entry.sessionId(), entry.groupId(), pages, entry.focused(), entry.agentTitle(),
					entry.updatedAt(), null).thenCompose(failure -> {
						if (failure != null && !failure.isEmpty()) {
							failed.add(new Failed(entry, failure));
							if (entry.sessionId().equals(c.focusedId)) {
								c.clearFocus();
							}
						}
						return loadNext(agent, plan, next, failed);
					});
		}
		return Async.done(null);
	}
}
