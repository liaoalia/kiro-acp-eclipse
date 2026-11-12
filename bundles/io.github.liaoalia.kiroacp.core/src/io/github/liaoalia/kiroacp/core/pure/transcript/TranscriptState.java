package io.github.liaoalia.kiroacp.core.pure.transcript;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.liaoalia.kiroacp.core.model.CommandView;
import io.github.liaoalia.kiroacp.core.model.ConfigOptionView;
import io.github.liaoalia.kiroacp.core.model.ModesView;
import io.github.liaoalia.kiroacp.core.model.PlanEntryView;
import io.github.liaoalia.kiroacp.core.model.ToolCallView;
import io.github.liaoalia.kiroacp.core.model.TranscriptItem;

/**
 * The transcript of one session: {@code TranscriptState} in the reference's
 * transcript.ts. Immutable; {@link Transcript} returns a new state for every
 * change, so a snapshot handed to another thread stays valid.
 *
 * @param toolCalls	  by {@code toolCallId}, in the order the calls first appeared
 * @param modes		  {@code null} when the session advertises none
 * @param openMessage	the message that still accepts chunks, or {@code null} when the next chunk starts a new one
 * @param nextMessageSeq monotonic counter used to mint message view ids
 */
public record TranscriptState(List<TranscriptItem> items, Map<String, ToolCallView> toolCalls, List<PlanEntryView> plan,
		ModesView modes, List<ConfigOptionView> configOptions, List<CommandView> commands, OpenMessage openMessage,
		int nextMessageSeq) {

	public TranscriptState {
		items = List.copyOf(items);
		// Map.copyOf would lose the order, which the page does not need but tests and logs read better with.
		toolCalls = Collections.unmodifiableMap(new LinkedHashMap<>(toolCalls));
		plan = List.copyOf(plan);
		configOptions = List.copyOf(configOptions);
		commands = List.copyOf(commands);
	}

	/**
	 * The same transcript with no message accepting chunks: the next chunk
	 * starts a message of its own, whatever its role and {@code messageId}.
	 */
	public TranscriptState withMessageClosed() {
		return openMessage == null ? this : withOpenMessage(null);
	}

	TranscriptState withItems(List<TranscriptItem> newItems) {
		return new TranscriptState(newItems, toolCalls, plan, modes, configOptions, commands, openMessage, nextMessageSeq);
	}

	TranscriptState withOpenMessage(OpenMessage newOpenMessage) {
		return new TranscriptState(items, toolCalls, plan, modes, configOptions, commands, newOpenMessage, nextMessageSeq);
	}

	TranscriptState withPlan(List<PlanEntryView> newPlan) {
		return new TranscriptState(items, toolCalls, newPlan, modes, configOptions, commands, openMessage, nextMessageSeq);
	}

	TranscriptState withModes(ModesView newModes) {
		return new TranscriptState(items, toolCalls, plan, newModes, configOptions, commands, openMessage, nextMessageSeq);
	}

	TranscriptState withConfigOptions(List<ConfigOptionView> newOptions) {
		return new TranscriptState(items, toolCalls, plan, modes, newOptions, commands, openMessage, nextMessageSeq);
	}

	TranscriptState withCommands(List<CommandView> newCommands) {
		return new TranscriptState(items, toolCalls, plan, modes, configOptions, newCommands, openMessage, nextMessageSeq);
	}
}
