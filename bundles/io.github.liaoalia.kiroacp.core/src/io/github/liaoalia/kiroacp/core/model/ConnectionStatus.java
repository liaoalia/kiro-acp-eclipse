package io.github.liaoalia.kiroacp.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** State of the agent process as the chat page shows it. */
public enum ConnectionStatus {
	@JsonProperty("stopped")
	STOPPED,
	@JsonProperty("starting")
	STARTING,
	@JsonProperty("ready")
	READY,
	@JsonProperty("auth_required")
	AUTH_REQUIRED,
	@JsonProperty("error")
	ERROR
}
