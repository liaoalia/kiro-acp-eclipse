package io.github.liaoalia.kiroacp.core.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConfigOptionView(String id, String name, String description, String category, Type type,
		/** A string for a select option, a boolean for a boolean one. */
		JsonNode currentValue,
		/** Flattened for select options; groups become a {@code group} label. */
		List<Choice> options) {

	public enum Type {
		@JsonProperty("select")
		SELECT,
		@JsonProperty("boolean")
		BOOLEAN
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Choice(String value, String name, String description, String group) {
	}
}
