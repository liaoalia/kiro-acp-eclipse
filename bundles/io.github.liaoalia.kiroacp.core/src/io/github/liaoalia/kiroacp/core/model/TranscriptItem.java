package io.github.liaoalia.kiroacp.core.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({ @JsonSubTypes.Type(value = TranscriptItem.Message.class, name = "message"),
		@JsonSubTypes.Type(value = TranscriptItem.Tool.class, name = "tool") })
public sealed interface TranscriptItem {

	record Message(MessageView message) implements TranscriptItem {
	}

	/** The card itself is in {@link UiState#toolCalls()}. */
	record Tool(String toolCallId) implements TranscriptItem {
	}
}
