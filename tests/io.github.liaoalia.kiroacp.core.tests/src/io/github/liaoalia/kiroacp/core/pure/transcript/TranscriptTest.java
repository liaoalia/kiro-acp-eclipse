package io.github.liaoalia.kiroacp.core.pure.transcript;

import static io.github.liaoalia.kiroacp.core.pure.transcript.Transcript.applyUpdate;
import static io.github.liaoalia.kiroacp.core.pure.transcript.Transcript.applyUpdates;
import static io.github.liaoalia.kiroacp.core.pure.transcript.Transcript.createTranscript;
import static io.github.liaoalia.kiroacp.core.pure.transcript.Transcript.fileNameOfUri;
import static io.github.liaoalia.kiroacp.core.pure.transcript.Transcript.toMessageSegment;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import io.github.liaoalia.kiroacp.core.acp.api.ModelState;
import io.github.liaoalia.kiroacp.core.acp.api.SessionState;
import io.github.liaoalia.kiroacp.core.model.CommandView;
import io.github.liaoalia.kiroacp.core.model.ConfigOptionView;
import io.github.liaoalia.kiroacp.core.model.ConnectionStatus;
import io.github.liaoalia.kiroacp.core.model.ConnectionView;
import io.github.liaoalia.kiroacp.core.model.DeliveryState;
import io.github.liaoalia.kiroacp.core.model.MessageRole;
import io.github.liaoalia.kiroacp.core.model.MessageSegment;
import io.github.liaoalia.kiroacp.core.model.MessageView;
import io.github.liaoalia.kiroacp.core.model.ModeView;
import io.github.liaoalia.kiroacp.core.model.ModelView;
import io.github.liaoalia.kiroacp.core.model.ModesView;
import io.github.liaoalia.kiroacp.core.model.PlanEntryView;
import io.github.liaoalia.kiroacp.core.model.SessionStatus;
import io.github.liaoalia.kiroacp.core.model.SessionSummary;
import io.github.liaoalia.kiroacp.core.model.ToWebview;
import io.github.liaoalia.kiroacp.core.model.ToolCallView;
import io.github.liaoalia.kiroacp.core.model.ToolContentView;
import io.github.liaoalia.kiroacp.core.model.ToolKind;
import io.github.liaoalia.kiroacp.core.model.ToolLocation;
import io.github.liaoalia.kiroacp.core.model.ToolStatus;
import io.github.liaoalia.kiroacp.core.model.TranscriptItem;
import io.github.liaoalia.kiroacp.core.model.UiJson;
import io.github.liaoalia.kiroacp.core.model.UiState;

/**
 * The cases of the reference's {@code test/transcript.test.ts}, in its order,
 * followed by cases for what only the Java port has (untyped input, the
 * reason an update was ignored).
 */
class TranscriptTest {

	/** Single quotes keep the updates readable in Java source. */
	private static final ObjectMapper JSON = new ObjectMapper().configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true);

	static JsonNode json(String text) {
		try {
			return JSON.readTree(text);
		} catch (JsonProcessingException e) {
			throw new IllegalArgumentException(text, e);
		}
	}

	private static JsonNode chunk(String kind, String text) {
		return chunk(kind, text, null);
	}

	private static JsonNode chunk(String kind, String text, String messageId) {
		ObjectNode update = JSON.createObjectNode().put("sessionUpdate", kind);
		update.putObject("content").put("type", "text").put("text", text);
		if (messageId != null) {
			update.put("messageId", messageId);
		}
		return update;
	}

	private static MessageView message(TranscriptState state, int index) {
		return ((TranscriptItem.Message) state.items().get(index)).message();
	}

	// ---------------------------------------------------------------------
	// test/transcript.test.ts
	// ---------------------------------------------------------------------

	@Test
	void consecutiveChunksWithTheSameMessageIdMergeIntoOneMessage() {
		ApplyResult result = applyUpdates(createTranscript(), List.of(chunk("agent_message_chunk", "Hello ", "m1"),
				chunk("agent_message_chunk", "world", "m1"), chunk("agent_message_chunk", "!")));
		TranscriptState state = result.state();
		assertEquals(1, state.items().size());
		MessageView message = message(state, 0);
		assertEquals(MessageRole.AGENT, message.role());
		assertEquals(List.of(new MessageSegment.Text("Hello world!")), message.segments());
		assertEquals(3, result.emits().size());
		for (ToWebview emit : result.emits()) {
			assertEquals(message.id(), ((ToWebview.Chunk) emit).messageId());
		}
		assertNull(result.ignored());
	}

	@Test
	void aNewMessageIdARoleChangeOrAnInterveningToolCallStartANewMessage() {
		TranscriptState state = applyUpdates(createTranscript(), List.of(chunk("agent_message_chunk", "one", "m1"),
				chunk("agent_message_chunk", "two", "m2"), chunk("agent_thought_chunk", "thinking"),
				chunk("agent_message_chunk", "three"),
				json("{'sessionUpdate':'tool_call','toolCallId':'t1','title':'Read','kind':'read','status':'in_progress'}"),
				chunk("agent_message_chunk", "four"), chunk("user_message_chunk", "me"))).state();
		List<String> roles = new ArrayList<>();
		for (TranscriptItem item : state.items()) {
			roles.add(item instanceof TranscriptItem.Message m ? m.message().role().name() : "tool");
		}
		assertEquals(List.of("AGENT", "AGENT", "THOUGHT", "AGENT", "tool", "AGENT", "USER"), roles);
		assertEquals(List.of(new MessageSegment.Text("one")), message(state, 0).segments());
	}

	@Test
	void nonTextContentBlocksBecomeResourceAndImageSegments() {
		ApplyResult result = applyUpdates(createTranscript(), List.of(
				json("{'sessionUpdate':'user_message_chunk','content':{'type':'resource_link','uri':'file:///a.ts','name':'a.ts'}}"),
				json("{'sessionUpdate':'user_message_chunk','content':{'type':'image','data':'','mimeType':'image/png'}}")));
		assertEquals(List.of(new MessageSegment.Resource("file:///a.ts", "a.ts"), new MessageSegment.Image("image/png", null, null)),
				message(result.state(), 0).segments());
		// What the page gets for the image: no name and no src rather than nulls.
		assertEquals(json("{'type':'chunk','messageId':'msg-1','role':'user','segment':{'type':'image','mimeType':'image/png'}}"),
				UiJson.toJson(result.emits().get(1)));
	}

	private static MessageSegment image(String fields) {
		ObjectNode block = (ObjectNode) json("{'type':'image','data':'','mimeType':'image/png'}");
		block.setAll((ObjectNode) json(fields));
		return toMessageSegment(block);
	}

	@Test
	void imageBlocksSrcOnlyForSmallSafeDataNameFromTheUri() {
		assertEquals(new MessageSegment.Image("image/png", null, "data:image/png;base64,iVBORw0K"), image("{'data':'iVBORw0K'}"));
		assertEquals(new MessageSegment.Image("image/PNG", "My Shot.png", "data:image/png;base64,iVBORw0K"),
				image("{'data':'iVBORw0K','mimeType':'image/PNG','uri':'file:///c%3A/Users/me/My%20Shot.png'}"));
		// Too large to keep in the transcript: a named chip without src.
		ObjectNode big = JSON.createObjectNode().put("type", "image").put("mimeType", "image/png")
				.put("data", "A".repeat(Transcript.MAX_INLINE_PREVIEW_CHARS + 4)).put("uri", "file:///w/big.png");
		assertEquals(new MessageSegment.Image("image/png", "big.png", null), toMessageSegment(big));
		// The limit itself still shows.
		ObjectNode atLimit = big.deepCopy().put("data", "A".repeat(Transcript.MAX_INLINE_PREVIEW_CHARS));
		assertNotNull(((MessageSegment.Image) toMessageSegment(atLimit)).src());
		// Types that are not accepted images, and junk payloads, never get a src.
		assertEquals(new MessageSegment.Image("image/svg+xml", null, null), image("{'data':'PHN2Zy8+','mimeType':'image/svg+xml'}"));
		assertEquals(new MessageSegment.Image("image/png", null, null), image("{'data':'not base64!','mimeType':'image/png'}"));
		assertEquals(new MessageSegment.Image("text/html", null, null), image("{'data':'AAAA','mimeType':'text/html'}"));
	}

	@Test
	void base64PaddingAsThePageGuardAcceptsIt() {
		assertNotNull(((MessageSegment.Image) image("{'data':'AAA='}")).src());
		assertNotNull(((MessageSegment.Image) image("{'data':'AA=='}")).src());
		assertNull(((MessageSegment.Image) image("{'data':'A==='}")).src());
		assertNull(((MessageSegment.Image) image("{'data':'=='}")).src());
		assertNull(((MessageSegment.Image) image("{'data':'AA=A'}")).src());
		assertNull(((MessageSegment.Image) image("{'data':'AAAA\\n'}")).src());
	}

	@Test
	void fileNameOfUriTakesTheLastSegmentOfUrlsAndPlainPaths() {
		assertEquals(Optional.of("shot.png"), fileNameOfUri("file:///home/me/shot.png"));
		assertEquals(Optional.of("caf\u00e9 #1.png"), fileNameOfUri("file:///c%3A/Users/me/caf%C3%A9%20%231.png"));
		assertEquals(Optional.of("a.gif"), fileNameOfUri("https://example.com/img/a.gif?size=2#top"));
		assertEquals(Optional.of("shot.jpg"), fileNameOfUri("C:\\Users\\me\\shot.jpg"));
		assertEquals(Optional.of("50%.png"), fileNameOfUri("/tmp/50%.png"));
		assertEquals(Optional.of("bad%E0%A4%A.png"), fileNameOfUri("file:///bad%E0%A4%A.png"));
		assertEquals(Optional.empty(), fileNameOfUri("file:///dir/"));
		assertEquals(Optional.empty(), fileNameOfUri(""));
	}

	@Test
	void fileNameOfUriDecodesLikeDecodeUriComponent() {
		// A plus is a plus, not a space as in form encoding.
		assertEquals(Optional.of("a+b.png"), fileNameOfUri("file:///w/a+b.png"));
		// Escapes that are not UTF-8, or cut short, leave the segment as it is.
		assertEquals(Optional.of("%FF.png"), fileNameOfUri("file:///w/%FF.png"));
		assertEquals(Optional.of("%C3x.png"), fileNameOfUri("file:///w/%C3x.png"));
		assertEquals(Optional.of("a%2"), fileNameOfUri("file:///w/a%2"));
		assertEquals(Optional.of("a%"), fileNameOfUri("file:///w/a%"));
		assertEquals(Optional.of("\ud83d\ude00.png"), fileNameOfUri("file:///w/%F0%9F%98%80.png"));
		// A query only ends a URL; in a plain path the characters are part of the name.
		assertEquals(Optional.of("a?b#c.png"), fileNameOfUri("/tmp/a?b#c.png"));
	}

	@Test
	void toolCallMapsContentAndLocations() {
		ApplyResult result = applyUpdate(createTranscript(), json("""
				{ 'sessionUpdate': 'tool_call', 'toolCallId': 't1', 'title': 'Edit file', 'kind': 'edit',
				  'locations': [{ 'path': '/w/a.ts', 'line': 3 }, { 'path': '/w/b.ts' }],
				  'content': [
				    { 'type': 'content', 'content': { 'type': 'text', 'text': 'hello' } },
				    { 'type': 'content', 'content': { 'type': 'image', 'data': '', 'mimeType': 'image/png' } },
				    { 'type': 'diff', 'path': '/w/a.ts', 'oldText': null, 'newText': 'new' },
				    { 'type': 'diff', 'path': '/w/b.ts', 'newText': 'b' },
				    { 'type': 'terminal', 'terminalId': 'term-1' }
				  ],
				  'rawInput': { 'path': '/w/a.ts' } }"""));
		TranscriptState state = result.state();
		ToolCallView call = state.toolCalls().get("t1");
		assertNotNull(call);
		assertEquals(ToolStatus.PENDING, call.status());
		assertEquals(ToolKind.EDIT, call.kind());
		assertEquals(List.of(new ToolLocation("/w/a.ts", 3), new ToolLocation("/w/b.ts", null)), call.locations());
		assertEquals(List.of(new ToolContentView.Text("hello"), new ToolContentView.Text("[image]"),
				new ToolContentView.Diff("/w/a.ts", null, "new"), new ToolContentView.Diff("/w/b.ts", null, "b"),
				new ToolContentView.Terminal("term-1", "", null)), call.content());
		assertEquals(json("{'path':'/w/a.ts'}"), call.rawInput());
		assertNull(call.rawOutput());
		assertEquals(List.of(new TranscriptItem.Tool("t1")), state.items());
		assertEquals(List.of(new ToWebview.ToolCall(call)), result.emits());
	}

	@Test
	void toolCallUpdateMergesOnlyProvidedFieldsAndCapturesFailureReason() {
		TranscriptState base = applyUpdate(createTranscript(), json("""
				{ 'sessionUpdate': 'tool_call', 'toolCallId': 't1', 'title': 'Run', 'kind': 'execute', 'status': 'in_progress',
				  'content': [{ 'type': 'content', 'content': { 'type': 'text', 'text': 'a' } }],
				  'locations': [{ 'path': '/w/x' }], 'rawInput': { 'command': 'ls' } }""")).state();

		TranscriptState partial = applyUpdate(base, json("""
				{ 'sessionUpdate': 'tool_call_update', 'toolCallId': 't1', 'status': 'completed', 'title': null,
				  'content': null, 'rawOutput': { 'exitCode': 0 } }""")).state();
		ToolCallView after = partial.toolCalls().get("t1");
		assertEquals(ToolStatus.COMPLETED, after.status());
		assertEquals("Run", after.title());
		assertEquals(ToolKind.EXECUTE, after.kind());
		assertEquals(List.of(new ToolContentView.Text("a")), after.content());
		assertEquals(List.of(new ToolLocation("/w/x", null)), after.locations());
		assertEquals(json("{'command':'ls'}"), after.rawInput());
		assertEquals(json("{'exitCode':0}"), after.rawOutput());
		assertEquals(1, partial.items().size(), "no duplicate item");

		TranscriptState replaced = applyUpdate(partial, json("""
				{ 'sessionUpdate': 'tool_call_update', 'toolCallId': 't1', 'status': 'failed',
				  'content': [{ 'type': 'terminal', 'terminalId': 'term-9' }],
				  '_meta': { 'kiro': { 'failureReason': 'denied' } } }""")).state();
		ToolCallView failed = replaced.toolCalls().get("t1");
		assertEquals(List.of(new ToolContentView.Terminal("term-9", "", null)), failed.content());
		assertEquals("denied", failed.failureReason());
		assertEquals(ToolStatus.FAILED, failed.status());
	}

	@Test
	void toolCallForAnExistingIdReplacesTheCardInPlaceUpdateForUnknownIdCreatesOne() {
		TranscriptState first = applyUpdate(createTranscript(), json("{'sessionUpdate':'tool_call','toolCallId':'t1','title':'A'}"))
				.state();
		TranscriptState second = applyUpdate(first,
				json("{'sessionUpdate':'tool_call','toolCallId':'t1','title':'B','status':'completed'}")).state();
		assertEquals(1, second.items().size());
		assertEquals("B", second.toolCalls().get("t1").title());

		TranscriptState orphan = applyUpdate(second,
				json("{'sessionUpdate':'tool_call_update','toolCallId':'t2','status':'completed'}")).state();
		assertEquals(2, orphan.items().size());
		assertEquals(new ToolCallView("t2", "t2", ToolKind.OTHER, ToolStatus.COMPLETED, List.of(), List.of(), null, null, null, null),
				orphan.toolCalls().get("t2"));
	}

	@Test
	void planUpdatesReplaceTheWholePlan() {
		ApplyResult a = applyUpdate(createTranscript(), json("""
				{ 'sessionUpdate': 'plan', 'entries': [
				  { 'content': 'one', 'priority': 'high', 'status': 'pending' },
				  { 'content': 'two', 'priority': 'low', 'status': 'pending' } ] }"""));
		assertEquals(2, a.state().plan().size());
		ApplyResult b = applyUpdate(a.state(),
				json("{'sessionUpdate':'plan','entries':[{'content':'one','priority':'high','status':'completed'}]}"));
		assertEquals(List.of(new PlanEntryView("one", PlanEntryView.Priority.HIGH, PlanEntryView.Status.COMPLETED)), b.state().plan());
		assertEquals(List.of(new ToWebview.Plan(b.state().plan())), b.emits());
	}

	private static final String CONFIG_OPTIONS = """
			[
			  { 'id': 'model', 'name': 'Model', 'category': 'model', 'type': 'select', 'currentValue': 'a',
			    'options': [
			      { 'group': 'g1', 'name': 'Group One', 'options': [{ 'value': 'a', 'name': 'A', 'description': 'first' }] },
			      { 'group': 'g2', 'name': 'Group Two', 'options': [{ 'value': 'b', 'name': 'B' }] } ] },
			  { 'id': 'effortLevel', 'name': 'Effort', 'type': 'select', 'currentValue': 'low',
			    'options': [{ 'value': 'low', 'name': 'Low' }] },
			  { 'id': 'auto', 'name': 'Auto', 'type': 'boolean', 'currentValue': true }
			]""";

	@Test
	void configOptionsFlattenGroupedSelectOptionsAndKeepBooleans() {
		JsonNode options = json(CONFIG_OPTIONS);
		List<ConfigOptionView> views = Transcript.toConfigOptionViews(options);
		List<ConfigOptionView> expected = List.of(
				new ConfigOptionView("model", "Model", null, "model", ConfigOptionView.Type.SELECT, TextNode.valueOf("a"),
						List.of(new ConfigOptionView.Choice("a", "A", "first", "Group One"),
								new ConfigOptionView.Choice("b", "B", null, "Group Two"))),
				new ConfigOptionView("effortLevel", "Effort", null, null, ConfigOptionView.Type.SELECT, TextNode.valueOf("low"),
						List.of(new ConfigOptionView.Choice("low", "Low", null, null))),
				new ConfigOptionView("auto", "Auto", null, null, ConfigOptionView.Type.BOOLEAN, BooleanNode.TRUE, null));
		assertEquals(expected, views);

		ObjectNode update = JSON.createObjectNode().put("sessionUpdate", "config_option_update");
		update.set("configOptions", options);
		ApplyResult result = applyUpdate(createTranscript(), update);
		assertEquals(3, result.state().configOptions().size());
		assertEquals(List.of(new ToWebview.ConfigOptions(expected)), result.emits());
	}

	@Test
	void modeAndCommandUpdates() {
		TranscriptState seeded = Transcript.withSessionSnapshot(createTranscript(), json("""
				{ 'modes': { 'currentModeId': 'plan',
				  'availableModes': [{ 'id': 'plan', 'name': 'Plan' }, { 'id': 'vibe', 'name': 'Vibe' }] } }"""));
		ApplyResult modeResult = applyUpdate(seeded, json("{'sessionUpdate':'current_mode_update','currentModeId':'vibe'}"));
		assertEquals("vibe", modeResult.state().modes().currentModeId());
		assertEquals(List.of(new ModeView("plan", "Plan", null), new ModeView("vibe", "Vibe", null)),
				modeResult.state().modes().availableModes());
		assertEquals(List.of(new ToWebview.Modes(modeResult.state().modes())), modeResult.emits());

		ApplyResult noModes = applyUpdate(createTranscript(), json("{'sessionUpdate':'current_mode_update','currentModeId':'x'}"));
		assertEquals(new ModesView("x", List.of()), noModes.state().modes());

		ApplyResult commands = applyUpdate(createTranscript(), json("""
				{ 'sessionUpdate': 'available_commands_update', 'availableCommands': [
				  { 'name': 'plan', 'description': 'Plan it', 'input': { 'hint': 'goal' } },
				  { 'name': 'compact', 'description': 'Compact' } ] }"""));
		List<CommandView> expected = List.of(new CommandView("plan", "Plan it", "goal"), new CommandView("compact", "Compact", null));
		assertEquals(expected, commands.state().commands());
		assertEquals(List.of(new ToWebview.Commands(expected)), commands.emits());
	}

	@Test
	void unknownOrIrrelevantUpdatesAreIgnoredWithoutErrorAndDoNotMutateTheInput() {
		TranscriptState initial = createTranscript();
		ApplyResult info = applyUpdate(initial, json("{'sessionUpdate':'session_info_update','title':'x'}"));
		assertSame(initial, info.state());
		assertEquals(List.of(), info.emits());
		assertEquals("not a transcript update: session_info_update", info.ignored());
		ApplyResult unknown = applyUpdate(initial, json("{'sessionUpdate':'kiro_custom'}"));
		assertSame(initial, unknown.state());
		assertEquals(List.of(), unknown.emits());
		assertEquals("not a transcript update: kiro_custom", unknown.ignored());
		applyUpdates(initial, List.of(chunk("agent_message_chunk", "x", "m1"),
				json("{'sessionUpdate':'tool_call','toolCallId':'t','title':'t'}")));
		assertEquals(createTranscript(), initial);
	}

	@Test
	void snapshotToUiStateComposesTranscriptAndHostOwnedFields() {
		TranscriptState state = applyUpdates(createTranscript(), List.of(chunk("agent_message_chunk", "hi", "m1"))).state();
		UiState ui = Transcript.snapshotToUiState(state,
				new UiStateExtras(ConnectionView.of(ConnectionStatus.READY), "s1",
						List.of(new SessionSummary("s1", "New session", SessionStatus.IDLE, 0)), false, List.of(), null, List.of(),
						List.of()));
		assertEquals(1, ui.items().size());
		assertEquals("s1", ui.sessionId());
		assertEquals(ConnectionStatus.READY, ui.connection().status());
		assertEquals(List.of(), ui.configOptions());
		// The page reads a state the reducer made.
		JsonNode sent = UiJson.toJson(new ToWebview.State(ui));
		assertEquals(json("[{'type':'message','message':{'id':'msg-1','role':'agent','segments':[{'type':'text','text':'hi'}]}}]"),
				sent.path("state").path("items"));
		assertEquals(Optional.of(new ToWebview.State(ui)), UiJson.toWebview(sent));
	}

	// ---------------------------------------------------------------------
	// Beyond the reference's cases
	// ---------------------------------------------------------------------

	@Test
	void theStateCannotBeChangedBehindTheReducer() {
		TranscriptState state = applyUpdates(createTranscript(), List.of(chunk("agent_message_chunk", "x"),
				json("{'sessionUpdate':'tool_call','toolCallId':'t','title':'t'}"))).state();
		assertThrowsUnsupported(() -> state.items().clear());
		assertThrowsUnsupported(() -> state.toolCalls().clear());
		assertThrowsUnsupported(() -> message(state, 0).segments().clear());
		assertThrowsUnsupported(() -> state.toolCalls().get("t").content().add(new ToolContentView.Text("x")));
	}

	private static void assertThrowsUnsupported(Runnable change) {
		org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class, change::run);
	}

	@Test
	void aChunkWithoutMessageIdJoinsTheOpenMessageAndTheFirstIdNamesIt() {
		TranscriptState state = applyUpdates(createTranscript(), List.of(chunk("agent_message_chunk", "a"),
				chunk("agent_message_chunk", "b", "m1"), chunk("agent_message_chunk", "c", "m1"))).state();
		assertEquals(1, state.items().size());
		assertEquals(new OpenMessage("msg-1", MessageRole.AGENT, "m1"), state.openMessage());
		TranscriptState next = applyUpdate(state, chunk("agent_message_chunk", "d", "m2")).state();
		assertEquals(2, next.items().size());
		assertEquals("msg-2", message(next, 1).id());
		assertEquals(3, next.nextMessageSeq());
	}

	@Test
	void aClosedMessageDoesNotTakeTheNextChunk() {
		TranscriptState state = applyUpdate(createTranscript(), chunk("agent_message_chunk", "local reply", "local-1")).state();
		TranscriptState closed = state.withMessageClosed();
		assertNull(closed.openMessage());
		assertSame(closed, closed.withMessageClosed());
		TranscriptState next = applyUpdate(closed, chunk("agent_message_chunk", "answer")).state();
		assertEquals(2, next.items().size());
	}

	@Test
	void aToolCallUpdateKeepsTheOpenMessageOpenButANewCardClosesIt() {
		TranscriptState state = applyUpdates(createTranscript(), List.of(
				json("{'sessionUpdate':'tool_call','toolCallId':'t1','title':'A'}"), chunk("agent_message_chunk", "one"),
				json("{'sessionUpdate':'tool_call_update','toolCallId':'t1','status':'completed'}"),
				chunk("agent_message_chunk", " more"))).state();
		assertEquals(2, state.items().size());
		assertEquals(List.of(new MessageSegment.Text("one more")), message(state, 1).segments());

		TranscriptState orphan = applyUpdates(state, List.of(
				json("{'sessionUpdate':'tool_call_update','toolCallId':'t9','status':'completed'}"),
				chunk("agent_message_chunk", "after"))).state();
		assertEquals(4, orphan.items().size());
	}

	@Test
	void textJoinsOnlyTheTextBeforeIt() {
		TranscriptState state = applyUpdates(createTranscript(), List.of(chunk("user_message_chunk", "see ", "u1"),
				json("{'sessionUpdate':'user_message_chunk','messageId':'u1','content':{'type':'resource_link','uri':'file:///a','title':'T','name':'n'}}"),
				chunk("user_message_chunk", "and", "u1"), chunk("user_message_chunk", " this", "u1"))).state();
		assertEquals(List.of(new MessageSegment.Text("see "), new MessageSegment.Resource("file:///a", "T"),
				new MessageSegment.Text("and this")), message(state, 0).segments());
	}

	@Test
	void contentBlocksOfEveryType() {
		assertEquals(new MessageSegment.Text("[audio]"), toMessageSegment(json("{'type':'audio','data':'AAAA','mimeType':'audio/wav'}")));
		assertEquals(new MessageSegment.Resource("file:///r", "file:///r"),
				toMessageSegment(json("{'type':'resource','resource':{'uri':'file:///r','text':'body'}}")));
		assertEquals(new MessageSegment.Resource("file:///l", "file:///l"), toMessageSegment(json("{'type':'resource_link','uri':'file:///l'}")));
		assertEquals(new MessageSegment.Text(""), toMessageSegment(json("{'type':'hologram'}")));

		assertEquals("[audio]", Transcript.contentBlockToText(json("{'type':'audio'}")));
		assertEquals("[resource file:///l]", Transcript.contentBlockToText(json("{'type':'resource_link','uri':'file:///l'}")));
		assertEquals("body", Transcript.contentBlockToText(json("{'type':'resource','resource':{'uri':'file:///r','text':'body'}}")));
		assertEquals("[resource file:///r]",
				Transcript.contentBlockToText(json("{'type':'resource','resource':{'uri':'file:///r','blob':'AAAA'}}")));
		assertEquals("", Transcript.contentBlockToText(json("{'type':'hologram'}")));
	}

	@Test
	void unknownToolKindsAndStatuses() {
		ToolCallView call = Transcript.toToolCallView(json("{'toolCallId':'t','kind':'teleport','status':'exploded'}"));
		assertEquals(ToolKind.OTHER, call.kind());
		assertEquals(ToolStatus.PENDING, call.status());
		assertEquals("t", call.title());
		for (ToolKind kind : ToolKind.values()) {
			assertEquals(kind, Transcript.toToolKind(UiJson.toJson(new ToWebview.ToolCall(
					new ToolCallView("t", "t", kind, ToolStatus.PENDING, List.of(), List.of(), null, null, null, null))).path("call")
					.path("kind")));
		}
		for (ToolStatus status : ToolStatus.values()) {
			assertEquals(status, Transcript.toToolStatus(UiJson.toJson(new ToWebview.ToolCall(
					new ToolCallView("t", "t", ToolKind.OTHER, status, List.of(), List.of(), null, null, null, null))).path("call")
					.path("status")));
		}
		// The wire names are case sensitive.
		assertEquals(ToolKind.OTHER, Transcript.toToolKind(TextNode.valueOf("READ")));
		assertEquals(ToolKind.OTHER, Transcript.toToolKind(null));

		ToolCallView running = new ToolCallView("t", "T", ToolKind.READ, ToolStatus.IN_PROGRESS, List.of(), List.of(), null, null, "perm-1",
				"old");
		ToolCallView merged = Transcript.mergeToolCallUpdate(running, json("{'kind':'teleport','status':'exploded','title':'New'}"));
		assertEquals(ToolKind.OTHER, merged.kind());
		assertEquals(ToolStatus.IN_PROGRESS, merged.status(), "an unknown status keeps the one the card has");
		assertEquals("New", merged.title());
		assertEquals("perm-1", merged.pendingPermissionId());
		assertEquals("old", merged.failureReason());
	}

	@Test
	void malformedUpdatesAreIgnoredWithAReason() {
		TranscriptState initial = createTranscript();
		Map<String, String> cases = Map.of("null", "update without a sessionUpdate kind", "[]", "update without a sessionUpdate kind",
				"'text'", "update without a sessionUpdate kind", "{'sessionUpdate':7}", "update without a sessionUpdate kind",
				"{'sessionUpdate':'tool_call'}", "tool_call without a toolCallId", "{'sessionUpdate':'tool_call_update','toolCallId':1}",
				"tool_call_update without a toolCallId", "{'sessionUpdate':'current_mode_update','currentModeId':null}",
				"current_mode_update without a currentModeId", "{'sessionUpdate':'agent_message_chunk'}",
				"message chunk without a typed content block", "{'sessionUpdate':'agent_message_chunk','content':{'text':'x'}}",
				"message chunk without a typed content block", "{'sessionUpdate':'agent_message_chunk','content':'x'}",
				"message chunk without a typed content block");
		cases.forEach((update, reason) -> {
			ApplyResult result = applyUpdate(initial, json(update));
			assertSame(initial, result.state(), update);
			assertEquals(List.of(), result.emits(), update);
			assertEquals(reason, result.ignored(), update);
		});
		assertEquals("update without a sessionUpdate kind", applyUpdate(initial, null).ignored());
		// One ignored update does not stop the ones after it.
		ApplyResult mixed = applyUpdates(initial, List.of(json("{'sessionUpdate':'usage_update'}"), chunk("agent_message_chunk", "x")));
		assertEquals(1, mixed.state().items().size());
		assertEquals("not a transcript update: usage_update", mixed.ignored());
	}

	@Test
	void wronglyTypedFieldsDoNotBreakTheReducer() {
		TranscriptState state = applyUpdates(createTranscript(), List.of(json("""
				{ 'sessionUpdate': 'tool_call', 'toolCallId': 't1', 'title': 5, 'kind': 3, 'status': {},
				  'content': [null, 'x', 4, { 'type': 'content' }, { 'type': 'content', 'content': 'x' },
				    { 'type': 'content', 'content': { 'type': 'resource' } }, { 'type': 'diff' }, { 'type': 'terminal' },
				    { 'type': 'unknown' }, {}],
				  'locations': [null, {}, { 'path': 1 }, { 'path': '/a', 'line': 'x' }, { 'path': '/b', 'line': 2.5 }],
				  'rawInput': null, '_meta': { 'kiro': 'x' } }"""),
				json("{'sessionUpdate':'tool_call_update','toolCallId':'t1','content':{},'locations':'x','title':9,'_meta':[]}"),
				json("{'sessionUpdate':'plan','entries':{'content':'x'}}"),
				json("{'sessionUpdate':'plan','entries':[null,{},{'content':1},{'content':'ok','priority':'urgent','status':7}]}"),
				json("{'sessionUpdate':'config_option_update','configOptions':{'id':'x'}}"),
				json("{'sessionUpdate':'available_commands_update','availableCommands':[null,{},{'name':'n','description':4,'input':'x'}]}"),
				json("{'sessionUpdate':'user_message_chunk','content':{'type':'text','text':5}}"),
				json("{'sessionUpdate':'user_message_chunk','content':{'type':'resource','resource':'x'}}"),
				json("{'sessionUpdate':'user_message_chunk','content':{'type':'image','data':5,'mimeType':null,'uri':7}}")))
				.state();
		ToolCallView call = state.toolCalls().get("t1");
		assertEquals("t1", call.title());
		assertEquals(ToolKind.OTHER, call.kind());
		assertEquals(ToolStatus.PENDING, call.status());
		// A non-array content or locations in an update is "given", so it replaces, by nothing.
		assertEquals(List.of(), call.content());
		assertEquals(List.of(), call.locations());
		assertNull(call.rawInput());
		assertNull(call.failureReason());
		assertEquals(List.of(new PlanEntryView("ok", PlanEntryView.Priority.MEDIUM, PlanEntryView.Status.PENDING)), state.plan());
		assertEquals(List.of(), state.configOptions());
		assertEquals(List.of(new CommandView("n", "", null)), state.commands());
		assertEquals(List.of(new MessageSegment.Text(""), new MessageSegment.Resource("", ""), new MessageSegment.Image("", null, null)),
				message(state, 1).segments());

		assertEquals(
				List.of(new ToolContentView.Text(""), new ToolContentView.Text(""), new ToolContentView.Text("[resource ]"),
						new ToolContentView.Diff("", null, ""), new ToolContentView.Terminal("", "", null)),
				Transcript.toToolContent(json("""
						[null, 'x', 4, { 'type': 'content' }, { 'type': 'content', 'content': 'x' },
						 { 'type': 'content', 'content': { 'type': 'resource' } }, { 'type': 'diff' }, { 'type': 'terminal' },
						 { 'type': 'unknown' }, {}]""")));
		assertEquals(List.of(new ToolLocation("/a", null), new ToolLocation("/b", null)),
				Transcript.toLocations(json("[null, {}, { 'path': 1 }, { 'path': '/a', 'line': 'x' }, { 'path': '/b', 'line': 2.5 }]")));
	}

	@Test
	void anEmptyFailureReasonIsNone() {
		assertEquals(Optional.of("denied"), Transcript.readFailureReason(json("{'kiro':{'failureReason':'denied'}}")));
		assertEquals(Optional.empty(), Transcript.readFailureReason(json("{'kiro':{'failureReason':3}}")));
		assertEquals(Optional.empty(), Transcript.readFailureReason(null));
		assertNull(Transcript.toToolCallView(json("{'toolCallId':'t','_meta':{'kiro':{'failureReason':''}}}")).failureReason());
		assertEquals("denied",
				Transcript.toToolCallView(json("{'toolCallId':'t','_meta':{'kiro':{'failureReason':'denied'}}}")).failureReason());
	}

	@Test
	void configOptionsOfOtherShapes() {
		List<ConfigOptionView> views = Transcript.toConfigOptionViews(json("""
				[ null, { 'name': 'no id' }, { 'id': 'slider', 'type': 'range' },
				  { 'id': 'b', 'type': 'boolean', 'currentValue': 'true', 'description': 'd' },
				  { 'id': 's', 'type': 'select', 'currentValue': 3,
				    'options': [ 7, { 'value': 1 }, { 'group': 'g', 'options': [ { 'value': 'x' }, { 'name': 'no value' }, null ] } ] },
				  { 'id': 'n', 'type': 'select', 'currentValue': 'v' } ]"""));
		assertEquals(List.of(
				new ConfigOptionView("b", "b", "d", null, ConfigOptionView.Type.BOOLEAN, BooleanNode.FALSE, null),
				new ConfigOptionView("s", "s", null, null, ConfigOptionView.Type.SELECT, TextNode.valueOf(""),
						List.of(new ConfigOptionView.Choice("x", "x", null, "g"))),
				new ConfigOptionView("n", "n", null, null, ConfigOptionView.Type.SELECT, TextNode.valueOf("v"), List.of())), views);
		assertEquals(List.of(), Transcript.toConfigOptionViews((List<JsonNode>) null));
		assertEquals(List.of(), Transcript.toConfigOptionViews((JsonNode) null));
	}

	@Test
	void modesOfOtherShapes() {
		assertNull(Transcript.toModesView(null));
		assertNull(Transcript.toModesView(json("null")));
		assertNull(Transcript.toModesView(json("{'availableModes':[]}")));
		assertEquals(new ModesView("a", List.of()), Transcript.toModesView(json("{'currentModeId':'a','availableModes':'x'}")));
		assertEquals(new ModesView("a", List.of(new ModeView("a", "a", "d"))),
				Transcript.toModesView(json("{'currentModeId':'a','availableModes':[null,{'name':'x'},{'id':'a','description':'d'}]}")));
	}

	@Test
	void messageDeliveryIsSetAndCleared() {
		TranscriptState state = applyUpdates(createTranscript(),
				List.of(chunk("user_message_chunk", "first", "user-1"), chunk("agent_message_chunk", "answer"))).state();
		TranscriptState steering = Transcript.setMessageDelivery(state, "msg-1", DeliveryState.STEERING);
		assertEquals(DeliveryState.STEERING, message(steering, 0).delivery());
		assertNull(message(steering, 1).delivery());
		assertNull(message(state, 0).delivery(), "the input is untouched");
		assertSame(steering, Transcript.setMessageDelivery(steering, "msg-1", DeliveryState.STEERING));
		assertSame(steering, Transcript.setMessageDelivery(steering, "nope", DeliveryState.QUEUED));
		assertSame(state, Transcript.setMessageDelivery(state, "msg-1", null));

		// A badge survives further chunks of its message and clears to a never-badged message.
		TranscriptState more = applyUpdate(Transcript.setMessageDelivery(state, "msg-2", DeliveryState.QUEUED),
				chunk("agent_message_chunk", " more")).state();
		assertEquals(DeliveryState.QUEUED, message(more, 1).delivery());
		assertEquals(state, Transcript.setMessageDelivery(steering, "msg-1", null));
	}

	@Test
	void sessionSnapshots() {
		TranscriptState seeded = Transcript.withSessionSnapshot(createTranscript(), json("""
				{ 'modes': { 'currentModeId': 'plan', 'availableModes': [] },
				  'configOptions': [{ 'id': 'auto', 'type': 'boolean', 'currentValue': true }],
				  'availableCommands': [{ 'name': 'plan' }] }"""));
		assertEquals("plan", seeded.modes().currentModeId());
		assertEquals(1, seeded.configOptions().size());
		assertEquals(List.of(new CommandView("plan", "", null)), seeded.commands());

		// Absent keeps, null clears.
		assertEquals(seeded, Transcript.withSessionSnapshot(seeded, json("{}")));
		assertSame(seeded, Transcript.withSessionSnapshot(seeded, (JsonNode) null));
		TranscriptState cleared = Transcript.withSessionSnapshot(seeded, json("{'modes':null,'configOptions':null}"));
		assertNull(cleared.modes());
		assertEquals(List.of(), cleared.configOptions());
		assertEquals(seeded.commands(), cleared.commands());

		TranscriptState withChat = applyUpdate(seeded, chunk("agent_message_chunk", "hi")).state();
		SessionState session = new SessionState("s1", "/w", json("{'currentModeId':'vibe','availableModes':[{'id':'vibe'}]}"), null,
				new ModelState("m", List.of(new ModelState.Model("m", "M", null))), List.of(json("{'name':'compact','description':'C'}")),
				null);
		TranscriptState fromSession = Transcript.withSessionSnapshot(withChat, session);
		assertEquals(new ModesView("vibe", List.of(new ModeView("vibe", "vibe", null))), fromSession.modes());
		assertEquals(List.of(), fromSession.configOptions());
		assertEquals(List.of(new CommandView("compact", "C", null)), fromSession.commands());
		assertEquals(withChat.items(), fromSession.items());
		assertEquals(withChat.openMessage(), fromSession.openMessage());
		assertNull(Transcript.withSessionSnapshot(withChat, session.withModes(null)).modes());

		assertEquals(new ModelView("m", List.of(new ModelView.Model("m", "M", null))), Transcript.toModelView(session.models()));
		assertNull(Transcript.toModelView(null));
	}

	@Test
	void everyEmitIsAMessageThePageUnderstands() {
		ApplyResult result = applyUpdates(createTranscript(), List.of(chunk("agent_thought_chunk", "hm"),
				json("""
						{ 'sessionUpdate': 'tool_call', 'toolCallId': 't1', 'title': 'Edit', 'kind': 'edit', 'status': 'in_progress',
						  'locations': [{ 'path': '/w/a', 'line': 3 }], 'rawInput': { 'p': [1, 2] },
						  'content': [{ 'type': 'diff', 'path': '/w/a', 'newText': 'n' }, { 'type': 'terminal', 'terminalId': 'x' }] }"""),
				json("{'sessionUpdate':'plan','entries':[{'content':'one','priority':'high','status':'in_progress'}]}"),
				json("{'sessionUpdate':'current_mode_update','currentModeId':'vibe'}"),
				json("{'sessionUpdate':'config_option_update','configOptions':" + CONFIG_OPTIONS + "}"),
				json("{'sessionUpdate':'available_commands_update','availableCommands':[{'name':'plan','input':{'hint':'goal'}}]}")));
		assertEquals(6, result.emits().size());
		for (ToWebview emit : result.emits()) {
			assertEquals(Optional.of(emit), UiJson.toWebview(UiJson.toJson(emit)), emit.toString());
		}
		assertEquals(json("""
				{ 'type': 'toolCall', 'call': { 'toolCallId': 't1', 'title': 'Edit', 'kind': 'edit', 'status': 'in_progress',
				  'content': [{ 'type': 'diff', 'path': '/w/a', 'oldText': null, 'newText': 'n' },
				    { 'type': 'terminal', 'terminalId': 'x', 'output': '' }],
				  'locations': [{ 'path': '/w/a', 'line': 3 }], 'rawInput': { 'p': [1, 2] } } }"""), UiJson.toJson(result.emits().get(1)));
		assertTrue(result.state().toolCalls().containsKey("t1"));
	}
}
