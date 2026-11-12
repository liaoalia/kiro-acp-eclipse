package io.github.liaoalia.kiroacp.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuthMethodView(String id, String name, String description,
		/** {@code true} when choosing it opens a terminal the user must complete. */
		boolean terminal) {
}
