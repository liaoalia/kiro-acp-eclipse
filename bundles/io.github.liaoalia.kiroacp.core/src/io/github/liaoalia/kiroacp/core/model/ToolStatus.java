package io.github.liaoalia.kiroacp.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Status of a tool call card. */
public enum ToolStatus {
	@JsonProperty("pending")
	PENDING,
	@JsonProperty("in_progress")
	IN_PROGRESS,
	@JsonProperty("completed")
	COMPLETED,
	@JsonProperty("failed")
	FAILED,
	@JsonProperty("cancelled")
	CANCELLED
}
