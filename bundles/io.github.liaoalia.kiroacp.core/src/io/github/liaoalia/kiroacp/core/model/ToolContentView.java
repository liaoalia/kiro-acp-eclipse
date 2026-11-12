package io.github.liaoalia.kiroacp.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({ @JsonSubTypes.Type(value = ToolContentView.Text.class, name = "text"),
		@JsonSubTypes.Type(value = ToolContentView.Diff.class, name = "diff"),
		@JsonSubTypes.Type(value = ToolContentView.Terminal.class, name = "terminal") })
public sealed interface ToolContentView {

	record Text(String text) implements ToolContentView {
	}

	/** {@code oldText} is {@code null} for a file that does not exist yet. */
	record Diff(String path, @JsonInclude(JsonInclude.Include.ALWAYS) String oldText, String newText) implements ToolContentView {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record Terminal(String terminalId, String output, Integer exitCode) implements ToolContentView {
	}
}
