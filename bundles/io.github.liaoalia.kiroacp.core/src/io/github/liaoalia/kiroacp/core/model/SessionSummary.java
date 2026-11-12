package io.github.liaoalia.kiroacp.core.model;

public record SessionSummary(String id, String title, SessionStatus status,
		/** Epoch ms of the last activity, for ordering. */
		long updatedAt) {
}
