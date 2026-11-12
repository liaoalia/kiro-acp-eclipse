package io.github.liaoalia.kiroacp.core.model;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Full state snapshot; sent when the page (re)loads and after a new session. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UiState(ConnectionView connection,
		/** The host accepts prompts while a turn is running (steering or queueing). */
		Boolean acceptsPromptsDuringTurn,
		/** The session this page displays; all other fields describe it. */
		@JsonInclude(JsonInclude.Include.ALWAYS) String sessionId,
		/** The sessions this page can switch between, most recent first. */
		List<SessionSummary> sessions, boolean turnActive, List<TranscriptItem> items, Map<String, ToolCallView> toolCalls,
		List<PlanEntryView> plan, List<PermissionView> permissions,
		@JsonInclude(JsonInclude.Include.ALWAYS) ModesView modes,
		List<ConfigOptionView> configOptions,
		@JsonInclude(JsonInclude.Include.ALWAYS) ModelView models,
		List<CommandView> commands, List<ContextChip> chips,
		/** Transient notices (errors, refusal, max_tokens, ...). */
		List<NoticeView> notices,
		/** Usage of the displayed session once the agent reported any. */
		SessionUsage usage,
		/** When the displayed session last changed, epoch ms. */
		Long lastActivityAt,
		/** Host-side display preferences (defaults; the page may override locally). */
		Prefs prefs) {

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Prefs(boolean showSettings, Boolean sessionPanelCollapsed) {
	}
}
