package io.github.liaoalia.kiroacp.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** ACP tool kinds; anything the agent sends beyond these is shown as {@link #OTHER}. */
public enum ToolKind {
	@JsonProperty("read")
	READ,
	@JsonProperty("edit")
	EDIT,
	@JsonProperty("delete")
	DELETE,
	@JsonProperty("move")
	MOVE,
	@JsonProperty("search")
	SEARCH,
	@JsonProperty("execute")
	EXECUTE,
	@JsonProperty("think")
	THINK,
	@JsonProperty("fetch")
	FETCH,
	@JsonProperty("switch_mode")
	SWITCH_MODE,
	@JsonProperty("other")
	OTHER
}
