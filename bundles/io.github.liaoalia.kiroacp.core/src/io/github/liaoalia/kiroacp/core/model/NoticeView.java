package io.github.liaoalia.kiroacp.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record NoticeView(String id, Severity severity, String text) {

	public enum Severity {
		@JsonProperty("info")
		INFO,
		@JsonProperty("warning")
		WARNING,
		@JsonProperty("error")
		ERROR
	}
}
