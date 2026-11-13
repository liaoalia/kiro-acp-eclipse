package io.github.liaoalia.kiroacp.core.session;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.liaoalia.kiroacp.core.model.PermissionView;
import io.github.liaoalia.kiroacp.core.model.ToWebview;
import io.github.liaoalia.kiroacp.core.services.PendingPermission;

/**
 * Keeps the permission cards of the pages in step with what is pending in
 * the {@code PermissionManager} ({@code syncPermissions} in the reference).
 *
 * <p>The manager changes on the reader thread (a request arrives), on the
 * controller thread (the user answers) and wherever a turn is cancelled.
 * Every change notes, on its own thread, which requests are pending at that
 * moment ({@link #changed()}) and queues one {@link #sync} with them on the
 * controller thread.
 *
 * <p>The guarantee: a request's card, its {@code permissionRequested} hook
 * and {@code revealSession} never come before the controller has handled the
 * tool call update that preceded the request on the wire. The reader thread
 * queues that update before it registers the request, so whatever a change
 * finds pending has its tool call queued ahead of the sync that change
 * queues. A sync therefore announces only requests that it, or a sync before
 * it, was queued with (they are <em>released</em>), never one that was
 * registered while it waited in the queue: that one is announced by the sync
 * of its own registration, which is queued behind its tool call.
 *
 * <p>What a sync was queued with only releases; what it shows is what is
 * pending when it runs. The changes of different threads may be queued in
 * another order than they happened, and an older list must neither bring
 * back the card of a request that was answered meanwhile nor take away one.
 */
final class PermissionCards {

	private final ChatController c;
	/** Pending requests a sync was queued with: their tool calls have been handled. Controller thread. */
	private final Set<String> released = new HashSet<>();

	PermissionCards(ChatController controller) {
		this.c = controller;
	}

	/** The pending set changed. On the thread that changed it. */
	void changed() {
		List<String> pendingNow = c.permissions.pendingIds();
		c.async.later(() -> sync(pendingNow));
	}

	/**
	 * Controller thread.
	 *
	 * @param pendingWhenQueued the ids that were pending when this sync was queued
	 */
	void sync(Collection<String> pendingWhenQueued) {
		released.addAll(pendingWhenQueued);
		Map<String, List<PermissionView>> bySession = new HashMap<>();
		Set<String> pendingNow = new HashSet<>();
		for (PermissionView request : c.permissions.pending()) {
			pendingNow.add(request.requestId());
			if (!released.contains(request.requestId())) {
				// Registered while this sync was queued: its own sync is queued behind its tool call.
				continue;
			}
			PendingPermission entry = c.permissions.getEntry(request.requestId());
			if (entry != null) {
				bySession.computeIfAbsent(entry.sessionId(), key -> new ArrayList<>()).add(request);
			}
		}
		// Ids are never used twice, so what is no longer pending never needs its release again.
		released.retainAll(pendingNow);
		for (ChatSession session : List.copyOf(c.sessions.values())) {
			List<PermissionView> pending = bySession.getOrDefault(session.id(), List.of());
			Set<String> current = new HashSet<>();
			for (PermissionView request : pending) {
				current.add(request.requestId());
			}
			for (String id : List.copyOf(session.knownPermissionIds)) {
				if (!current.contains(id)) {
					session.knownPermissionIds.remove(id);
					c.postTo(session.id(), new ToWebview.PermissionResolved(id));
				}
			}
			for (PermissionView request : pending) {
				if (!session.knownPermissionIds.add(request.requestId())) {
					continue;
				}
				c.touch(session);
				c.postTo(session.id(), new ToWebview.Permission(request));
				// Seam (reference D7): the diff feature opens the proposed edit from here.
				c.hooks.each(listener -> listener.permissionRequested(session.id(), request));
				// Nobody can answer: have a page opened on this very session.
				if (c.pages.isEmpty()) {
					c.host.actions().revealSession(session.id());
				}
			}
		}
		c.listing.schedule();
	}
}
