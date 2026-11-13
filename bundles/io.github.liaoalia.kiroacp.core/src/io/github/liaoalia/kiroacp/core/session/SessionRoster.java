package io.github.liaoalia.kiroacp.core.session;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.liaoalia.kiroacp.core.acp.api.KiroAgent;
import io.github.liaoalia.kiroacp.core.acp.api.SessionState;
import io.github.liaoalia.kiroacp.core.model.ConnectionStatus;
import io.github.liaoalia.kiroacp.core.model.NoticeView.Severity;
import io.github.liaoalia.kiroacp.core.session.AttachOptions.Prefer;
import io.github.liaoalia.kiroacp.core.session.ChatHooks.NewSessions;

/**
 * Which sessions exist and which page shows which (reference D19, D20):
 * sessions are created, selected and closed here, and a page without a
 * session gets one. Controller thread only.
 */
final class SessionRoster {

	/** What {@link #loadInto} answers for a session that was closed while it was restored. */
	private static final String CLOSED_MEANWHILE = "";

	private final ChatController c;
	private int sessionSeq;

	SessionRoster(ChatController controller) {
		this.c = controller;
	}

	String groupFor(PageBinding page) {
		if (page != null && page.groupId != null) {
			return page.groupId;
		}
		return c.focusedPage != null && c.focusedPage.groupId != null ? c.focusedPage.groupId : ChatController.DEFAULT_GROUP;
	}

	CompletableFuture<Void> ensurePageSession(PageBinding page) {
		return c.lifecycle.ensureStarted().thenCompose(started -> page != null ? assignSession(page) : Async.done(null));
	}

	/**
	 * Give the page its first session. One assignment per page is in flight
	 * at a time: {@code ready} and a first prompt both get here while
	 * {@code session/new} is pending and must not open two sessions.
	 */
	CompletableFuture<Void> assignSession(PageBinding page) {
		if (page.displayed != null || !c.pages.contains(page)) {
			return Async.done(null);
		}
		if (c.agent == null || c.agent.status() != ConnectionStatus.READY) {
			return Async.done(null);
		}
		if (page.assigning != null) {
			return page.assigning;
		}
		CompletableFuture<Void> run = doAssignSession(page);
		if (run.isDone()) {
			return run;
		}
		// Callbacks of one future run in no promised order, so waiters get the stage that has already cleared the mark.
		CompletableFuture<Void> cleared = run.whenComplete((done, error) -> page.assigning = null);
		page.assigning = cleared;
		return cleared;
	}

	private CompletableFuture<Void> doAssignSession(PageBinding page) {
		String groupId = groupFor(page);
		boolean fresh = page.prefer == Prefer.FRESH;
		ChatSession existing = null;
		if (!fresh) {
			// The focused session when the page may list it, else a session of its own.
			ChatSession focused = c.focusedId != null ? c.sessions.get(c.focusedId) : null;
			if (focused != null && (page.groupId == null || page.groupId.equals(focused.groupId)
					|| ChatController.DEFAULT_GROUP.equals(focused.groupId))) {
				existing = focused;
			}
		}
		if (existing == null) {
			existing = spareSession(groupId);
		}
		if (existing == null) {
			existing = spareSession(ChatController.DEFAULT_GROUP);
		}
		if (existing != null) {
			// Adopting stays synchronous, so two pages opened together cannot both pick the same spare.
			adopt(page, existing.id(), fresh);
			return Async.done(null);
		}
		return createSession(groupId).thenCompose(created -> created == null ? Async.done(null)
				: dropIfOrphaned(created, page).thenAccept(dropped -> {
					if (!dropped) {
						adopt(page, created.id(), fresh);
					}
				}));
	}

	private void adopt(PageBinding page, String sessionId, boolean fresh) {
		if (page.displayed != null || !c.pages.contains(page)) {
			return;
		}
		c.setPageSession(page, sessionId);
		if (fresh) {
			c.focusPage(page);
		} else if (c.focusedId == null) {
			c.focus(sessionId);
		}
	}

	/**
	 * A session created for a page arrived after the page went away (it was
	 * closed while {@code session/new} was in flight): close it unless
	 * another attached page lists its group, so no session lingers in a group
	 * nothing shows or closes (reference D20). {@value ChatController#DEFAULT_GROUP}
	 * sessions stay: any page can adopt them.
	 *
	 * @return whether the session was dropped
	 */
	private CompletableFuture<Boolean> dropIfOrphaned(ChatSession session, PageBinding page) {
		if (c.pages.contains(page) || !c.sessions.containsKey(session.id())) {
			return Async.done(false);
		}
		if (ChatController.DEFAULT_GROUP.equals(session.groupId) || c.pages.stream().anyMatch(p -> session.groupId.equals(p.groupId))) {
			return Async.done(false);
		}
		c.logger.info("session " + session.id() + " orphaned (its page closed while it was opening)");
		return doCloseSession(session.id()).thenApply(done -> true);
	}

	CompletableFuture<Void> assignPendingPages() {
		return assignPendingPages(List.copyOf(c.pages), 0);
	}

	private CompletableFuture<Void> assignPendingPages(List<PageBinding> all, int index) {
		for (int i = index; i < all.size(); i++) {
			PageBinding page = all.get(i);
			if (page.displayed == null) {
				int next = i + 1;
				return assignSession(page).thenCompose(done -> assignPendingPages(all, next));
			}
		}
		return Async.done(null);
	}

	/** An idle, empty session of the group that no page displays: the auto-created first session, typically. */
	private ChatSession spareSession(String groupId) {
		for (ChatSession session : c.listing.sorted()) {
			if (groupId.equals(session.groupId) && !session.turnActive && session.transcript.items().isEmpty()
					&& c.pages.stream().noneMatch(page -> session.id().equals(page.displayed))) {
				return session;
			}
		}
		return null;
	}

	CompletableFuture<ChatSession> createSession(String groupId) {
		KiroAgent target = c.agent;
		if (target == null) {
			return Async.done(null);
		}
		// Seam (reference D24): remembered options travel as hints and are applied before anything shows the session.
		NewSessions seam = c.hooks.newSessions();
		JsonNode meta = seam != null ? seam.meta() : null;
		Path cwd = c.host.workspace().defaultCwd();
		CompletableFuture<SessionState> opened = c.async.ui(target.newSession(cwd, List.of(), meta));
		if (meta != null) {
			// A stale hint must never block session creation.
			opened = opened.handle((state, error) -> {
				if (error == null) {
					return Async.done(state);
				}
				c.logger.warn("session/new with hints " + meta + " failed (" + Async.message(error) + "); retrying without them");
				return c.async.ui(target.newSession(cwd, List.of(), null));
			}).thenCompose(flow -> flow);
		}
		return opened.thenCompose(state -> {
			if (c.agent != target) {
				// The process the session belongs to is gone already.
				return Async.<ChatSession>done(null);
			}
			ChatSession session = new ChatSession(state, ++sessionSeq, groupId, c.host.clock().getAsLong());
			c.sessions.put(session.id(), session);
			// In case the id is one that was closed before (a session loaded again).
			c.terminal.admitSession(session.id());
			c.logger.info("session " + session.id() + " opened (group " + groupId + ")");
			c.listing.schedule();
			c.hooks.each(listener -> listener.sessionOpened(session.id()));
			if (seam == null) {
				return Async.done(session);
			}
			return c.async.ui(seam.opened(target, state)).handle((done, error) -> {
				if (error != null) {
					c.logger.warn("session " + session.id() + ": could not apply its options: " + Async.message(error));
				}
				return session;
			});
		}).exceptionally(error -> {
			if (c.agent != target) {
				return null;
			}
			if (target.status() == ConnectionStatus.AUTH_REQUIRED) {
				c.lifecycle.setConnection(c.lifecycle.connectionFromAgent(ConnectionStatus.AUTH_REQUIRED, "Sign in to Kiro to start a session."));
			} else {
				c.tell(null, Severity.ERROR, "Could not create a session: " + Async.message(error));
			}
			return null;
		});
	}

	/** Forget every session: the agent process they belong to is going away. */
	void endAllSessions() {
		for (ChatSession session : List.copyOf(c.sessions.values())) {
			session.stopCancelTimer();
			c.hooks.each(listener -> listener.sessionClosing(session.id()));
			c.permissions.cancelAll(session.id());
			c.permissions.clearPolicies(session.id());
		}
		c.sessions.clear();
		c.kinds.clear();
		// Whatever the agent started for them, running or not, goes with them, also the
		// commands of requests that named no session.
		try {
			c.terminal.disposeAll();
		} catch (RuntimeException e) {
			c.logger.warn("could not end the terminals of the sessions: " + Async.message(e));
		}
		for (PageBinding page : List.copyOf(c.pages)) {
			if (page.displayed != null) {
				page.displayed = null;
				c.postSessionState(page, null);
			}
		}
		c.clearFocus();
		c.listing.schedule();
	}

	CompletableFuture<String> doNewSession(PageBinding given) {
		PageBinding page = given != null ? given : c.focusedPage;
		if (c.agent == null || c.agent.status() != ConnectionStatus.READY) {
			// Starting the agent opens a first session; a page with none gets one assigned.
			return c.lifecycle.ensureStarted().thenCompose(started -> page == null ? Async.done(c.focusedId)
					: assignSession(page).thenApply(assigned -> page.displayed != null ? page.displayed : c.focusedId));
		}
		return createSession(groupFor(page)).thenCompose(session -> {
			if (session == null) {
				return Async.<String>done(null);
			}
			CompletableFuture<Boolean> orphaned = page != null ? dropIfOrphaned(session, page) : Async.done(false);
			return orphaned.thenApply(dropped -> {
				if (dropped) {
					return null;
				}
				if (page != null) {
					c.setPageSession(page, session.id());
				}
				c.focus(session.id());
				return session.id();
			});
		});
	}

	void doSelectSession(String sessionId, PageBinding given) {
		ChatSession session = sessionId != null ? c.sessions.get(sessionId) : null;
		if (session == null) {
			return;
		}
		PageBinding page = given != null ? given : c.focusedPage;
		if (page != null) {
			// A session of another page's group is not this page's to show.
			if (page.groupId != null && !page.groupId.equals(session.groupId) && !ChatController.DEFAULT_GROUP.equals(session.groupId)) {
				return;
			}
			c.setPageSession(page, sessionId);
		}
		c.focus(sessionId);
	}

	/** See {@link ChatController#openPastSession}. */
	CompletableFuture<String> doOpenPastSession(String sessionId, String agentTitle, PageBinding given) {
		if (sessionId == null) {
			return Async.done(null);
		}
		if (c.sessions.containsKey(sessionId)) {
			doSelectSession(sessionId, given);
			return Async.done(sessionId);
		}
		return c.lifecycle.ensureStarted().handle((started, error) -> null).thenCompose(started -> {
			if (c.sessions.containsKey(sessionId)) {
				// Restored by someone else while the agent started.
				doSelectSession(sessionId, given);
				return Async.done(sessionId);
			}
			PageBinding page = given != null ? given : c.focusedPage;
			String previous = page != null ? page.displayed : null;
			KiroAgent agent = c.agent;
			if (agent == null || agent.status() != ConnectionStatus.READY) {
				c.tell(previous != null ? c.sessions.get(previous) : null, Severity.WARNING,
						"Cannot restore a session: the agent is not running.");
				return Async.<String>done(null);
			}
			return loadInto(agent, sessionId, groupFor(page), page != null ? List.of(page) : List.of(), true, agentTitle, null,
					previous).thenCompose(failure -> {
				if (failure == null) {
					ChatSession loaded = c.sessions.get(sessionId);
					if (loaded == null || page == null) {
						return Async.done(loaded != null ? sessionId : null);
					}
					// The page may have been closed while the history was replayed.
					return dropIfOrphaned(loaded, page).thenApply(dropped -> dropped ? null : sessionId);
				}
				if (failure.isEmpty()) {
					return Async.<String>done(null);
				}
				if (sessionId.equals(c.focusedId)) {
					// The page went back to what it showed before, and so does the focus.
					c.focusedId = null;
					String fallback = previous != null && c.sessions.containsKey(previous) ? previous
							: c.listing.sorted().stream().map(ChatSession::id).findFirst().orElse(null);
					if (fallback != null) {
						c.focus(fallback);
					} else {
						c.clearFocus();
					}
				}
				c.tell(c.focusedId != null ? c.sessions.get(c.focusedId) : null, Severity.WARNING,
						"Could not restore session " + sessionId + ": " + failure);
				return Async.<String>done(null);
			});
		});
	}

	/**
	 * {@code session/load} into a fresh session of {@code groupId}, displayed
	 * by {@code pages} (and focused with {@code focus}) while the agent replays
	 * its history ({@code loadInto} of the reference).
	 *
	 * @param updatedAt when the session was last used, for one that comes back to its place in the
	 *                  list; {@code null}: now
	 * @param fallback  what the pages showing the session go back to when the load fails
	 * @return {@code null} once the session is restored, else why it is not (empty when it was
	 *         closed meanwhile): it is then forgotten again, and the focus is the caller's to mend
	 */
	CompletableFuture<String> loadInto(KiroAgent agent, String sessionId, String groupId, List<PageBinding> pages,
			boolean focus, String agentTitle, Long updatedAt, String fallback) {
		Path cwd = c.host.workspace().defaultCwd();
		SessionState placeholder = new SessionState(sessionId, cwd.toString(), null, null, null, List.of(), null);
		ChatSession session = new ChatSession(placeholder, ++sessionSeq, groupId, c.host.clock().getAsLong());
		session.loading = true;
		session.agentTitle = agentTitle;
		c.sessions.put(sessionId, session);
		// The id may be one that was closed earlier in this run: its commands are welcome again.
		c.terminal.admitSession(sessionId);
		c.logger.info("session " + sessionId + " restoring (group " + groupId + ")");
		c.listing.schedule();
		c.hooks.each(listener -> listener.sessionOpened(sessionId));
		for (PageBinding page : pages) {
			if (c.pages.contains(page)) {
				c.setPageSession(page, sessionId);
			}
		}
		if (focus) {
			c.focus(sessionId);
		}
		String restoring = c.tell(session, Severity.INFO, "Restoring session…").id();
		return c.async.ui(agent.loadSession(sessionId, cwd, List.of())).handle((state, error) -> {
			// Only this very object: the id may have been closed, or closed and restored again, meanwhile.
			boolean current = c.sessions.get(sessionId) == session;
			if (error != null) {
				String message = Async.message(error);
				c.logger.warn("session " + sessionId + " restore failed: " + message);
				if (current) {
					c.hooks.each(listener -> listener.sessionClosing(sessionId));
					c.sessions.remove(sessionId);
					c.permissions.cancelAll(sessionId);
					c.permissions.clearPolicies(sessionId);
					c.kinds.forget(sessionId);
					try {
						c.terminal.disposeSession(sessionId);
					} catch (RuntimeException e) {
						c.logger.warn("session " + sessionId + " restore: could not end its terminals: " + Async.message(e));
					}
					String back = fallback != null && c.sessions.containsKey(fallback) ? fallback : null;
					for (PageBinding shown : List.copyOf(c.pages)) {
						if (sessionId.equals(shown.displayed)) {
							c.setPageSession(shown, back);
						}
					}
				}
				c.listing.schedule();
				return message;
			}
			session.loading = false;
			if (!current) {
				// Closed on purpose while it was restored: nothing to show and nothing to explain.
				return CLOSED_MEANWHILE;
			}
			// The agent already reported this state as an event; merging it again does no harm.
			c.events.applySessionState(session, state);
			// The replay touched it. A session opened from the past is the most recent one; one
			// that comes back after a sign-in keeps its place in the list.
			if (updatedAt == null) {
				c.touch(session);
			} else {
				session.updatedAt = updatedAt;
			}
			c.clearNotice(sessionId, restoring);
			for (PageBinding shown : List.copyOf(c.pages)) {
				if (sessionId.equals(shown.displayed)) {
					c.postSessionState(shown, sessionId);
				}
			}
			c.listing.schedule();
			c.logger.info("session " + sessionId + " restored (" + session.transcript.items().size() + " items)");
			return null;
		});
	}

	CompletableFuture<Void> closeNext(String groupId) {
		for (ChatSession session : c.listing.sorted()) {
			if (groupId.equals(session.groupId) && !session.closing) {
				return doCloseSession(session.id()).thenCompose(done -> closeNext(groupId));
			}
		}
		return Async.done(null);
	}

	CompletableFuture<Void> doCloseSession(String sessionId) {
		ChatSession session = sessionId != null ? c.sessions.get(sessionId) : null;
		if (session == null) {
			return Async.done(null);
		}
		// Nothing may start a turn on a session that is going away; listeners hear of it
		// now, while a page may still display it.
		session.closing = true;
		session.stopCancelTimer();
		c.hooks.each(listener -> listener.sessionClosing(sessionId));
		KiroAgent current = c.agent;
		CompletableFuture<Void> cancelled = Async.done(null);
		if (session.turnActive && current != null) {
			cancelled = c.async.ui(current.cancel(sessionId)).handle((done, error) -> {
				if (error != null) {
					c.logger.warn("cancel before close failed: " + Async.message(error));
				}
				return null;
			});
		}
		return cancelled.thenCompose(told -> {
			c.permissions.cancelAll(sessionId);
			c.permissions.clearPolicies(sessionId);
			c.sessions.remove(sessionId, session);
			c.kinds.forget(sessionId);
			// Its commands end with it, whether a turn was running or not, and none is started
			// for it from here on (the agent may still ask: a cancel is only a request). Once
			// the session is gone nothing else would end them. Never a reason not to close.
			try {
				c.terminal.disposeSession(sessionId);
			} catch (RuntimeException e) {
				c.logger.warn("session " + sessionId + " close: could not end its terminals: " + Async.message(e));
			}
			c.logger.info("session " + sessionId + " closed");

			boolean wasFocused = sessionId.equals(c.focusedId);
			List<PageBinding> displaying = c.pages.stream().filter(page -> sessionId.equals(page.displayed)).toList();
			boolean ready = c.agent != null && c.agent.status() == ConnectionStatus.READY;
			String[] replacement = { null };
			return replaceIn(displaying, 0, ready, replacement).thenCompose(replaced -> {
				if (!wasFocused) {
					return Async.<Void>done(null);
				}
				c.focusedId = null;
				// Focus what the user now sees in place of the closed session, not the globally newest one.
				String fallback = replacement[0] != null ? replacement[0]
						: c.listing.sorted().stream().map(ChatSession::id).findFirst().orElse(null);
				CompletableFuture<String> chosen = fallback == null && ready
						? createSession(groupFor(null)).thenApply(created -> created != null ? created.id() : null)
						: Async.done(fallback);
				return chosen.thenAccept(id -> {
					if (id != null) {
						c.focus(id);
					} else {
						c.clearFocus();
					}
				});
			}).thenRun(c.listing::schedule);
		});
	}

	/** See {@link ChatController#replaceSession}. */
	CompletableFuture<String> doReplaceSession(String sessionId, PageBinding given) {
		ChatSession session = sessionId != null ? c.sessions.get(sessionId) : null;
		if (session == null) {
			return Async.done(null);
		}
		if (session.closing) {
			return session.replacement != null ? session.replacement : Async.done(null);
		}
		PageBinding page = given != null && sessionId.equals(given.displayed) ? given
				: c.focusedPage != null && sessionId.equals(c.focusedPage.displayed) ? c.focusedPage
						: c.pages.stream().filter(shown -> sessionId.equals(shown.displayed)).findFirst().orElse(null);
		// First of all, and before the cancel: the end of the cancelled turn must find nothing
		// that could start another turn on the session the user is leaving (reference D22).
		session.closing = true;
		c.hooks.each(listener -> listener.sessionReplacing(sessionId));
		CompletableFuture<String> replacement = new CompletableFuture<>();
		session.replacement = replacement;
		CompletableFuture<Void> cancelled = session.turnActive ? c.turns.cancel(sessionId) : Async.done(null);
		cancelled.handle((done, error) -> null).thenCompose(stopped -> doNewSession(page)).thenCompose(created -> {
			if (created != null && !created.equals(sessionId)) {
				return doCloseSession(sessionId).thenApply(closed -> created);
			}
			// Nothing took its place. When its page went away meanwhile, closing the page did not
			// close a session that was on its way out already: it must not linger in a group nothing shows.
			return page != null ? dropIfOrphaned(session, page).thenApply(dropped -> (String) null) : Async.<String>done(null);
		}).whenComplete((created, error) -> {
			if (error != null) {
				c.logger.warn("session " + sessionId + " could not be replaced: " + Async.message(error));
			}
			if (created == null) {
				// The session stays, and is usable again.
				session.closing = false;
			}
			session.replacement = null;
			replacement.complete(created);
		});
		return replacement;
	}

	/** Each page that displayed the closed session falls back to the most recent session of its group, a new one when none is left. */
	private CompletableFuture<Void> replaceIn(List<PageBinding> displaying, int index, boolean ready, String[] replacement) {
		if (index >= displaying.size()) {
			return Async.done(null);
		}
		PageBinding page = displaying.get(index);
		String groupId = groupFor(page);
		String existing = c.listing.sorted().stream().filter(s -> page.groupId == null || groupId.equals(s.groupId))
				.map(ChatSession::id).findFirst().orElse(null);
		BiFunction<String, Boolean, CompletableFuture<Void>> show = (id, dropped) -> {
			if (!dropped && c.pages.contains(page)) {
				c.setPageSession(page, id);
				if (id != null && (replacement[0] == null || page == c.focusedPage)) {
					replacement[0] = id;
				}
			}
			return replaceIn(displaying, index + 1, ready, replacement);
		};
		if (existing != null || !ready) {
			return show.apply(existing, false);
		}
		return createSession(groupId).thenCompose(created -> created == null ? show.apply(null, false)
				: dropIfOrphaned(created, page).thenCompose(dropped -> show.apply(created.id(), dropped)));
	}
}
