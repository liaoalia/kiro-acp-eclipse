package io.github.liaoalia.kiroacp.core.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PermissionView(String requestId, String toolCallId, String title,
		/** Resolved tool kind (from the request, else from the known tool call). */
		ToolKind kind, List<PermissionOptionView> options,
		/** True when the tool call carries diff content. */
		boolean hasDiff,
		/** Kiro V3 consent context when present. */
		Consent consent,
		/** Client-side "always" policies the host can offer for this request, with ready-made labels. */
		List<Policy> policies) {

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Consent(String capability, String resource, Boolean persistableConsent) {
	}

	public record Policy(PermissionScope scope, String label, String rejectLabel) {
	}
}
