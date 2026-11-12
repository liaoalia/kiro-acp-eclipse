package io.github.liaoalia.kiroacp.core.services;

import io.github.liaoalia.kiroacp.core.model.ToolKind;
import io.github.liaoalia.kiroacp.core.pure.permission.PolicyDecision;

/**
 * A pending permission request stopped being pending.
 *
 * @param optionId the agent's option that was selected; {@code null} when cancelled
 */
public record PermissionResolution(String requestId, String sessionId, String toolCallId, ToolKind toolKind,
		Outcome outcome, String optionId) {

	public enum Outcome {
		ALLOW, REJECT, CANCELLED;

		static Outcome of(PolicyDecision decision) {
			return decision == PolicyDecision.ALLOW ? ALLOW : REJECT;
		}
	}
}
