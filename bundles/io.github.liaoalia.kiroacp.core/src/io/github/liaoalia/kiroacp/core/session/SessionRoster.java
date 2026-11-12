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
