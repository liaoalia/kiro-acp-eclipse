package io.github.liaoalia.kiroacp.core.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Host to chat page: {@code ToWebview} in the reference's protocol.ts.
 *
 * <p>A field marked as always included is written as {@code null} when empty,
 * because the page tells "no value" from "not sent".
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({ @JsonSubTypes.Type(value = ToWebview.State.class, name = "state"),
		@JsonSubTypes.Type(value = ToWebview.Connection.class, name = "connection"),
		@JsonSubTypes.Type(value = ToWebview.Session.class, name = "session"),
		@JsonSubTypes.Type(value = ToWebview.Sessions.class, name = "sessions"),
		@JsonSubTypes.Type(value = ToWebview.Turn.class, name = "turn"),
		@JsonSubTypes.Type(value = ToWebview.Chunk.class, name = "chunk"),
		@JsonSubTypes.Type(value = ToWebview.MessageDelivery.class, name = "messageDelivery"),
		@JsonSubTypes.Type(value = ToWebview.ToolCall.class, name = "toolCall"),
		@JsonSubTypes.Type(value = ToWebview.Plan.class, name = "plan"),
		@JsonSubTypes.Type(value = ToWebview.Permission.class, name = "permission"),
		@JsonSubTypes.Type(value = ToWebview.PermissionResolved.class, name = "permissionResolved"),
		@JsonSubTypes.Type(value = ToWebview.Modes.class, name = "modes"),
		@JsonSubTypes.Type(value = ToWebview.ConfigOptions.class, name = "configOptions"),
		@JsonSubTypes.Type(value = ToWebview.Models.class, name = "models"),
		@JsonSubTypes.Type(value = ToWebview.Commands.class, name = "commands"),
		@JsonSubTypes.Type(value = ToWebview.Chips.class, name = "chips"),
		@JsonSubTypes.Type(value = ToWebview.FileSuggestions.class, name = "fileSuggestions"),
		@JsonSubTypes.Type(value = ToWebview.Notice.class, name = "notice"),
		@JsonSubTypes.Type(value = ToWebview.ClearNotice.class, name = "clearNotice"),
		@JsonSubTypes.Type(value = ToWebview.Usage.class, name = "usage"),
		@JsonSubTypes.Type(value = ToWebview.FocusInput.class, name = "focusInput"),
		@JsonSubTypes.Type(value = ToWebview.ImagesAttached.class, name = "imagesAttached") })
public sealed interface ToWebview {

	record State(UiState state) implements ToWebview {
	}

	record Connection(ConnectionView connection) implements ToWebview {
	}

	/** The displayed session changed, or ended when {@code null}; a {@code state} snapshot always follows. */
	record Session(@JsonInclude(JsonInclude.Include.ALWAYS) String sessionId) implements ToWebview {
	}

	/** The session list changed. */
	record Sessions(List<SessionSummary> sessions) implements ToWebview {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record Turn(boolean active, String stopReason) implements ToWebview {
	}

	record Chunk(String messageId, MessageRole role, MessageSegment segment) implements ToWebview {
	}

	/** Update, or clear with {@code null}, the delivery badge of a user message. */
	record MessageDelivery(String messageId, @JsonInclude(JsonInclude.Include.ALWAYS) DeliveryState delivery) implements ToWebview {
	}

	record ToolCall(ToolCallView call) implements ToWebview {
	}

	record Plan(List<PlanEntryView> entries) implements ToWebview {
	}

	record Permission(PermissionView request) implements ToWebview {
	}

	record PermissionResolved(String requestId) implements ToWebview {
	}

	record Modes(@JsonInclude(JsonInclude.Include.ALWAYS) ModesView modes) implements ToWebview {
	}

	record ConfigOptions(List<ConfigOptionView> options) implements ToWebview {
	}

	record Models(@JsonInclude(JsonInclude.Include.ALWAYS) ModelView models) implements ToWebview {
	}

	record Commands(List<CommandView> commands) implements ToWebview {
	}

	record Chips(List<ContextChip> chips) implements ToWebview {
	}

	record FileSuggestions(String query, List<FileSuggestion> items) implements ToWebview {
	}

	record Notice(NoticeView notice) implements ToWebview {
	}

	record ClearNotice(String id) implements ToWebview {
	}

	/** Context and token usage of the displayed session changed. */
	record Usage(@JsonInclude(JsonInclude.Include.ALWAYS) SessionUsage usage) implements ToWebview {
	}

	record FocusInput() implements ToWebview {
	}

	/** Images read by the host for {@code pickImages} / {@code dropFiles}. */
	record ImagesAttached(List<ImageAttachment> images) implements ToWebview {
	}
}
