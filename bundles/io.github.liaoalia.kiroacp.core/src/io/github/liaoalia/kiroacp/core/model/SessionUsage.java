package io.github.liaoalia.kiroacp.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Context-window and token usage of a session; every field is {@code null} until reported. */
public record SessionUsage(
		/** Share of the context window in use, 0-100. */
		@JsonInclude(JsonInclude.Include.ALWAYS) Double contextPercent,
		@JsonInclude(JsonInclude.Include.ALWAYS) Long contextUsed,
		@JsonInclude(JsonInclude.Include.ALWAYS) Long contextSize,
		/** Tokens spent by the session so far. */
		@JsonInclude(JsonInclude.Include.ALWAYS) Long tokensSpent,
		@JsonInclude(JsonInclude.Include.ALWAYS) Cost cost) {

	public static final SessionUsage EMPTY = new SessionUsage(null, null, null, null, null);

	public record Cost(double amount, String currency) {
	}
}
