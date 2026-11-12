package io.github.liaoalia.kiroacp.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

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
	record Terminal(String terminalId, String output, JsonNode exitCode) implements ToolContentView {

		/** The command is still running: no {@code exitCode} field at all. */
		public static Terminal running(String terminalId, String output) {
			return new Terminal(terminalId, output, null);
		}

		/** @param exitCode {@code null} for a command that ended without one (killed): sent as an explicit null */
		public static Terminal ended(String terminalId, String output, Integer exitCode) {
			return new Terminal(terminalId, output,
					exitCode == null ? JsonNodeFactory.instance.nullNode() : JsonNodeFactory.instance.numberNode(exitCode));
		}
	}
}
