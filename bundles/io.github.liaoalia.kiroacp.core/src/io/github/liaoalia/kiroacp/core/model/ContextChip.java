package io.github.liaoalia.kiroacp.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ContextChip(String id, Kind kind,
		/** Absolute path. */
		String path,
		/** Workspace-relative label, e.g. {@code src/app.ts}. */
		String label,
		/** 1-based inclusive line range for selections. */
		Integer startLine, Integer endLine,
		/** The active-file chip is implicit; {@code @} chips are explicit. */
		boolean implicit) {

	public enum Kind {
		@JsonProperty("file")
		FILE,
		@JsonProperty("selection")
		SELECTION
	}
}
