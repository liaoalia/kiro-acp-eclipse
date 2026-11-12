package io.github.liaoalia.kiroacp.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PlanEntryView(String content, Priority priority, Status status) {

	public enum Priority {
		@JsonProperty("high")
		HIGH,
		@JsonProperty("medium")
		MEDIUM,
		@JsonProperty("low")
		LOW
	}

	public enum Status {
		@JsonProperty("pending")
		PENDING,
		@JsonProperty("in_progress")
		IN_PROGRESS,
		@JsonProperty("completed")
		COMPLETED
	}
}
