package io.github.liaoalia.kiroacp.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PermissionOptionView(String optionId, String name, Kind kind) {

	public enum Kind {
		@JsonProperty("allow_once")
		ALLOW_ONCE,
		@JsonProperty("allow_always")
		ALLOW_ALWAYS,
		@JsonProperty("reject_once")
		REJECT_ONCE,
		@JsonProperty("reject_always")
		REJECT_ALWAYS
	}
}
