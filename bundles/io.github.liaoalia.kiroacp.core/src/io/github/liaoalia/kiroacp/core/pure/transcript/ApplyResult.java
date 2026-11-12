package io.github.liaoalia.kiroacp.core.pure.transcript;

import java.util.List;

import io.github.liaoalia.kiroacp.core.model.ToWebview;

/**
 * What one or more updates did to a transcript.
 *
 * @param state   the transcript afterwards; the very instance passed in when nothing changed
 * @param emits   the incremental messages the host should forward to the chat page, in order
 * @param ignored why the (last ignored) update changed nothing, for the log; {@code null} when every
 *				update was applied. The reducer itself never logs and never fails: kinds it does
 *				not render ({@code session_info_update}, {@code usage_update}, anything Kiro adds)
 *				end up here, and the caller decides which of them are worth a line.
 */
public record ApplyResult(TranscriptState state, List<ToWebview> emits, String ignored) {

	public ApplyResult {
		emits = List.copyOf(emits);
	}

	static ApplyResult applied(TranscriptState state, ToWebview emit) {
		return new ApplyResult(state, List.of(emit), null);
	}

	static ApplyResult ignored(TranscriptState state, String reason) {
		return new ApplyResult(state, List.of(), reason);
	}
}
