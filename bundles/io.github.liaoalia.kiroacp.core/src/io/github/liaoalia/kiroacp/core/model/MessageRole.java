package io.github.liaoalia.kiroacp.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Who a transcript message is from. */
public enum MessageRole {
	@JsonProperty("user")
	USER,
	@JsonProperty("agent")
	AGENT,
	@JsonProperty("thought")
	THOUGHT
}
