package io.github.liaoalia.kiroacp.core.pure.transcript;

import java.util.List;

import io.github.liaoalia.kiroacp.core.model.ConnectionView;
import io.github.liaoalia.kiroacp.core.model.ContextChip;
import io.github.liaoalia.kiroacp.core.model.ModelView;
import io.github.liaoalia.kiroacp.core.model.NoticeView;
import io.github.liaoalia.kiroacp.core.model.PermissionView;
import io.github.liaoalia.kiroacp.core.model.SessionSummary;

/**
 * The fields of a {@code UiState} the host owns, as opposed to the ones the transcript holds.
 *
 * @param sessionId {@code null} when no session is displayed
 * @param models	{@code null} when the session advertises none
 */
public record UiStateExtras(ConnectionView connection, String sessionId, List<SessionSummary> sessions, boolean turnActive,
		List<PermissionView> permissions, ModelView models, List<ContextChip> chips, List<NoticeView> notices) {
}
