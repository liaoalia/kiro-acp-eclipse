package io.github.liaoalia.kiroacp.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Per-session status shown in the session panel. */
public enum SessionStatus {
	@JsonProperty("idle")
	IDLE,
	@JsonProperty("running")
	RUNNING,
	@JsonProperty("needs_input")
	NEEDS_INPUT,
	@JsonProperty("error")
	ERROR
}
