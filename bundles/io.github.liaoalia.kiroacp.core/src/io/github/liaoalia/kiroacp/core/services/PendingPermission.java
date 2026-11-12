package io.github.liaoalia.kiroacp.core.services;

import java.util.List;

import io.github.liaoalia.kiroacp.core.model.PermissionOptionView;
import io.github.liaoalia.kiroacp.core.model.PermissionView;
import io.github.liaoalia.kiroacp.core.model.ToolContentView;
import io.github.liaoalia.kiroacp.core.model.ToolKind;

/**
 * Everything the manager knows about a pending request.
 *
 * @param consent   the Kiro consent context, or {@code null}
 * @param diffs     diff contents carried by {@code toolCall.content} (for the diff feature)
 * @param createdAt milliseconds since the epoch
 */
public record PendingPermission(String requestId, String sessionId, String toolCallId, ToolKind toolKind, String title,
		List<PermissionOptionView> options, boolean hasDiff, PermissionView.Consent consent,
		List<ToolContentView.Diff> diffs, long createdAt) {

	public PendingPermission {
		options = List.copyOf(options);
		diffs = List.copyOf(diffs);
	}
}
