package io.github.liaoalia.kiroacp.core.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolCallView(String toolCallId, String title, ToolKind kind, ToolStatus status, List<ToolContentView> content,
		List<ToolLocation> locations, JsonNode rawInput, JsonNode rawOutput,
		/** Set while a permission request for this call is waiting for the user. */
		String pendingPermissionId,
		/** {@code _meta.kiro.failureReason} when present. */
		String failureReason) {
}
