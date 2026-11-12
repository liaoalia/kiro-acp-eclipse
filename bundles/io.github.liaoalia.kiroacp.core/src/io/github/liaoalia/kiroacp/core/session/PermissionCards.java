package io.github.liaoalia.kiroacp.core.session;

import java.util.ArrayList;
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
 * Every change queues one {@link #sync()} on the controller thread, which
 * compares what is pending then with what each session's pages were told. A
 * request is queued behind the tool call update that preceded it on the
 * wire; a sync queued earlier that already finds the request shows its card
 * before the tool card, which the page allows for.
 */
final class PermissionCards {

	private final ChatController c;

	PermissionCards(ChatController controller) {
		this.c = controller;
	}

	void sync() {
		Map<String, List<PermissionView>> bySession = new HashMap<>();
		for (PermissionView request : c.permissions.pending()) {
			PendingPermission entry = c.permissions.getEntry(request.requestId());
			if (entry != null) {
				bySession.computeIfAbsent(entry.sessionId(), key -> new ArrayList<>()).add(request);
			}
		}
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
