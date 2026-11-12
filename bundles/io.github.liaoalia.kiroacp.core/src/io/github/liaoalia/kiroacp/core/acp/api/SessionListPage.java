package io.github.liaoalia.kiroacp.core.acp.api;

import java.util.List;

/** @param nextCursor {@code null} on the last page */
public record SessionListPage(List<SessionListEntry> sessions, String nextCursor) {

	public SessionListPage {
		sessions = List.copyOf(sessions);
	}
}
