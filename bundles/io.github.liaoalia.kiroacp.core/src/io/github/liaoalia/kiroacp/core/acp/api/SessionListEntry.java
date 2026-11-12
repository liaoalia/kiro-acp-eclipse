package io.github.liaoalia.kiroacp.core.acp.api;

/**
 * One entry of {@code session/list}.
 *
 * @param title	 {@code null} when the agent has none
 * @param updatedAt ISO timestamp when known, else {@code null}
 */
public record SessionListEntry(String sessionId, String cwd, String title, String updatedAt) {
}
