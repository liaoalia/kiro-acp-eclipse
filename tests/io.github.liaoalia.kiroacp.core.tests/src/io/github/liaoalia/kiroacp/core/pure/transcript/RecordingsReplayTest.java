package io.github.liaoalia.kiroacp.core.pure.transcript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.liaoalia.kiroacp.core.acp.FakeAgent;
import io.github.liaoalia.kiroacp.core.model.ToWebview;
import io.github.liaoalia.kiroacp.core.model.ToolCallView;
import io.github.liaoalia.kiroacp.core.model.ToolKind;
import io.github.liaoalia.kiroacp.core.model.ToolStatus;
import io.github.liaoalia.kiroacp.core.model.TranscriptItem;
import io.github.liaoalia.kiroacp.core.model.UiJson;

/**
 * Every recording the fake agent plays ({@code tests/fixtures/recordings}),
 * fed to the reducer as it is: placeholders such as <code>{{cwd}}</code> stay
 * plain strings, and both outcomes of a permission are replayed one after the other.
 */
class RecordingsReplayTest {

	/** Kinds the recordings contain that the transcript has no part in. */
	private static final Set<String> NOT_TRANSCRIPT = Set.of("not a transcript update: session_info_update",
			"not a transcript update: usage_update", "not a transcript update: kiro_custom_update");

	private static List<Path> recordings() throws IOException {
		try (Stream<Path> files = Files.list(FakeAgent.fixtures().resolve("recordings"))) {
			return files.filter(file -> file.getFileName().toString().endsWith(".json")).sorted().toList();
		}
	}

	/** The updates of {@code steps} in playing order, with those of nested step lists. */
	private static void collectUpdates(JsonNode steps, List<JsonNode> out) {
		for (JsonNode step : steps) {
			String kind = step.path("kind").asText();
			if (kind.equals("update")) {
				out.add(step.get("update"));
			} else if (kind.equals("repeat")) {
				for (int i = 0; i < step.path("times").asInt(1); i++) {
					collectUpdates(step.path("steps"), out);
				}
			}
			collectUpdates(step.path("allow"), out);
			collectUpdates(step.path("reject"), out);
		}
	}

	private static List<JsonNode> updatesOf(Path recording) throws IOException {
		List<JsonNode> updates = new ArrayList<>();
		collectUpdates(new ObjectMapper().readTree(Files.readString(recording)).path("steps"), updates);
		return updates;
	}

	@TestFactory
	Stream<DynamicTest> everyRecordingReplaysWithoutErrors() throws IOException {
		List<Path> recordings = recordings();
		assertFalse(recordings.isEmpty(), "no recordings in " + FakeAgent.fixtures());
		return recordings.stream().map(recording -> DynamicTest.dynamicTest(recording.getFileName().toString(), () -> {
			TranscriptState state = Transcript.createTranscript();
			for (JsonNode update : updatesOf(recording)) {
				assertNotNull(update, "an update step without an update");
				ApplyResult result = Transcript.applyUpdate(state, update);
				if (result.ignored() != null) {
					assertTrue(NOT_TRANSCRIPT.contains(result.ignored()), result.ignored() + ": " + update);
					assertEquals(List.of(), result.emits());
				} else {
					assertFalse(result.emits().isEmpty(), "nothing to show for " + update);
				}
				for (ToWebview emit : result.emits()) {
					// What goes to the page is a message of the protocol, and reads back as the same.
					assertEquals(Optional.of(emit), UiJson.toWebview(UiJson.toJson(emit)), update.toString());
				}
				state = result.state();
			}
			for (TranscriptItem item : state.items()) {
				if (item instanceof TranscriptItem.Tool tool) {
					assertTrue(state.toolCalls().containsKey(tool.toolCallId()), "an item without its card: " + tool);
				}
			}
			long cards = state.items().stream().filter(TranscriptItem.Tool.class::isInstance).count();
			assertEquals(state.toolCalls().size(), cards, "one item per card");
		}));
	}

	@Test
	void theRecordingsExerciseTheReducer() throws IOException {
		Set<String> kinds = new TreeSet<>();
		int updates = 0;
		for (Path recording : recordings()) {
			for (JsonNode update : updatesOf(recording)) {
				kinds.add(update.path("sessionUpdate").asText());
				updates++;
			}
		}
		// Otherwise the replay above passes on an empty or mislaid fixture directory.
		assertTrue(updates >= 47, "updates found: " + updates);
		assertTrue(kinds.containsAll(Set.of("agent_message_chunk", "agent_thought_chunk", "tool_call", "tool_call_update", "plan",
				"current_mode_update")), kinds.toString());
	}

	@Test
	void theTourEndsAsItsRecordingSays() throws IOException {
		Path tour = FakeAgent.fixtures().resolve("recordings").resolve("tour.json");
		ApplyResult result = Transcript.applyUpdates(Transcript.createTranscript(), updatesOf(tour));
		TranscriptState state = result.state();
		ToolCallView read = state.toolCalls().get("call-read");
		assertNotNull(read);
		assertEquals(ToolKind.READ, read.kind());
		assertEquals("Read README.md", read.title());
		assertEquals("{{cwd}}/README.md", read.locations().get(0).path());
		assertEquals(1, read.locations().get(0).line());
		assertEquals(ToolStatus.COMPLETED, read.status());
		assertEquals(List.of("call-read", "call-exec", "call-edit"), List.copyOf(state.toolCalls().keySet()));
		// The outcome of a permission is the fake agent's own update, not a step of the recording.
		assertEquals(ToolStatus.IN_PROGRESS, state.toolCalls().get("call-edit").status());
		assertFalse(state.plan().isEmpty());
		TranscriptItem.Message first = (TranscriptItem.Message) state.items().get(0);
		assertEquals(List.of(new io.github.liaoalia.kiroacp.core.model.MessageSegment.Text(
				"Hello! I am the fake Kiro agent. Let me look around.")), first.message().segments());
	}
}
