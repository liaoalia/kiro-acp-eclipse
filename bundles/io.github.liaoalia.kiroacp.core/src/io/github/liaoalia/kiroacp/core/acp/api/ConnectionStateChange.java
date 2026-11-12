package io.github.liaoalia.kiroacp.core.acp.api;

import io.github.liaoalia.kiroacp.core.model.ConnectionStatus;

/**
 * @param agent   set once the agent answered {@code initialize}
 * @param message human-readable reason for {@code ERROR} / {@code STOPPED}, else {@code null}
 */
public record ConnectionStateChange(ConnectionStatus status, AgentInfo agent, String message) {
}
