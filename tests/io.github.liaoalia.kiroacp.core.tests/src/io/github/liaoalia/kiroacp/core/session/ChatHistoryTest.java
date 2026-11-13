package io.github.liaoalia.kiroacp.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.liaoalia.kiroacp.core.model.FromWebview;
import io.github.liaoalia.kiroacp.core.model.NoticeView;
import io.github.liaoalia.kiroacp.core.model.SessionStatus;
import io.github.liaoalia.kiroacp.core.model.ToWebview;
import io.github.liaoalia.kiroacp.core.session.Harness.Client;
import io.github.liaoalia.kiroacp.core.session.history.PastSessions;
import io.github.liaoalia.kiroacp.core.session.history.PastSessions.Entry;

/**
 * Browsing the agent's past sessions and restoring one ({@code session/list}
 * and {@code session/load}, reference D21; e2e F11), against the fake agent
 * and its two canned past sessions.
 */
class ChatHistoryTest {

	private static final String PAST_1 = "past-1";
	private static final String PAST_1_TITLE = "Investigate flaky CI";
	private static final String PAST_1_QUESTION = "Why is CI flaky?";
	private static final String PAST_1_TOOL = "call-past-1-read";

	/** Answers in the user's stead with what the test chose beforehand. */
	private static final class FakePicker implements PastSessions.Picker {
		final Hosts.Signal signal;
		final List<List<Entry>> offered = new CopyOnWriteArrayList<>();
		volatile String choice;

		FakePicker(Hosts.Signal signal) {
			this.signal = signal;
		}

		@Override
		public CompletionStage<String> pick(List<Entry> entries) {
			offered.add(entries);
			signal.changed();
			return CompletableFuture.completedFuture(choice);
		}
	}

	@TempDir
	Path dir;

	@Test
	void anAgentThatListsAndLoadsSessionsCanBeBrowsed() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			Client view = h.openView("view");
			assertTrue(view.snapshot().at("/connection/canBrowseSessions").asBoolean(), view.snapshot().path("connection").toString());
		}
		try (Harness h = Harness.launch(dir, "--engine", "v2")) {
			Client view = h.openView("view");
			assertFalse(view.snapshot().at("/connection/canBrowseSessions").asBoolean(false), "the v2 agent has no session/load");
		}
	}

	@Test
	void thePastSessionsAreListedAcrossThePagesOfTheAgentWithoutTheLiveOnes() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			PastSessions past = PastSessions.install(h.controller, new FakePicker(h.signal));
			Client view = h.openView("view");
			// The fake agent hands the list out two entries at a time, newest first. With two live
			// sessions the canned ones, which are older, are beyond the first page.
			h.get(h.controller.newSession(view.handle));
			h.awaitSettled(view);
			List<String> live = h.ask(h.controller::sessionIds);
			assertEquals(2, live.size());

			List<Entry> entries = h.get(past.list());
			List<String> ids = entries.stream().map(Entry::sessionId).toList();
			assertEquals(List.of(PAST_1, "past-2"), ids, "both canned sessions, each once, the newer first, and no live one");
			Entry first = entries.get(0);
			assertEquals(PAST_1_TITLE, first.title());
			assertEquals(PAST_1_TITLE, first.label(), "the label is the title");
			assertEquals("Add retry to fetch", entries.get(1).title());
			assertEquals("just now", first.description(), "a time relative to the (pinned, earlier) clock");
			assertEquals(h.dir.toString(), first.detail(), "the folder of the session: the workspace's, which the list was asked for");
			assertEquals(first.detail(), first.cwd());
			assertEquals("2026-11-09T10:00:00.000Z", first.updatedAt());

			// A session closed here stays with the agent (nothing calls session/delete) and is offered as well.
			String closed = live.get(0);
			h.get(h.controller.closeSession(closed));
			List<String> after = h.get(past.list()).stream().map(Entry::sessionId).toList();
			assertTrue(after.contains(closed), after.toString());
			assertEquals(3, after.size(), after.toString());
			Entry untitled = h.get(past.list()).stream().filter(e -> e.sessionId().equals(closed)).findFirst().orElseThrow();
			assertEquals(closed, untitled.label(), "a session without a title is labelled with its id");
			assertNull(untitled.title());
		}
	}

	@Test
	void aPastSessionIsRestoredWithItsTranscriptAndPromptsWaitForTheRestore() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			PastSessions past = PastSessions.install(h.controller, new FakePicker(h.signal));
			Client view = h.openView("view");
			Client other = h.openView("other");
			String before = view.sessionId();
			// The title is known from here on, so the restore registers the session before it first waits for the agent.
			h.get(past.list());

			int mark = view.mark();
			int quiet = other.mark();
			// One piece of work of the controller thread: the session is still restoring when the
			// prompt arrives. It must be refused, not sent: its answer would be taken for history.
			List<CompletableFuture<?>> flows = h.ask(() -> {
				CompletableFuture<String> restore = past.open(PAST_1, view.handle);
				assertEquals(PAST_1, view.sessionId(), "the restoring session is displayed at once");
				assertEquals(PAST_1, h.controller.focusedSessionId());
				CompletableFuture<Void> prompt = h.controller.prompt("/scenario chat", PAST_1, view.handle);
				assertTrue(prompt.isDone(), "a refused prompt is over at once");
				assertFalse(h.controller.turnActive(PAST_1));
				return List.of(restore, prompt);
			});
			ToWebview.Notice wait = view.page.await(ToWebview.Notice.class, mark,
					n -> n.notice().text().equals("Wait for the session to finish restoring."), "the notice that asks to wait");
			assertEquals(NoticeView.Severity.INFO, wait.notice().severity());
			assertEquals(PAST_1, h.get(flows.get(0)), "the restored id");
			h.scheduler.flush();
			assertEquals(List.of(), view.page.ofType(ToWebview.Turn.class, mark), "no turn was started");

			JsonNode state = view.snapshot();
			assertEquals(PAST_1, state.path("sessionId").asText());
			assertFalse(state.path("turnActive").asBoolean());
			List<JsonNode> messages = messages(state);
			assertEquals("user", messages.get(0).path("role").asText(), "the conversation starts with what the user asked");
			assertEquals(PAST_1_QUESTION, text(messages.get(0)));
			assertFalse(messages.stream().anyMatch(m -> text(m).contains("/scenario chat")), "the refused prompt was not echoed");
			// The tool call splits the answer into the text before it and the text after it.
			List<String> agent = messages.stream().filter(m -> m.path("role").asText().equals("agent")).map(ChatHistoryTest::text).toList();
			assertEquals(List.of("Let me look at .github/workflows/ci.yml. ", "The test job has no retry and the network step times out."),
					agent);
			assertEquals("completed", state.at("/toolCalls/" + PAST_1_TOOL + "/status").asText());
			assertEquals("read", state.at("/toolCalls/" + PAST_1_TOOL + "/kind").asText());
			List<String> order = new ArrayList<>();
			state.path("items").forEach(item -> order.add(item.path("type").asText()));
			assertEquals(List.of("message", "message", "tool", "message"), order, "in the order it happened");
			List<String> notices = state.path("notices").findValuesAsText("text");
			assertEquals(List.of("Wait for the session to finish restoring."), notices, "the progress notice was withdrawn");
			assertEquals(1, view.page.ofType(ToWebview.ClearNotice.class, mark).size());

			// The session list: the agent's title, idle, in this view's group only; what was displayed before lives on.
			JsonNode summary = state.path("sessions").get(0);
			assertEquals(PAST_1, summary.path("id").asText(), "a session that came back is the most recent one");
			assertEquals(PAST_1_TITLE, summary.path("title").asText());
			assertEquals("idle", summary.path("status").asText());
			assertEquals(List.of(PAST_1, before), state.path("sessions").findValuesAsText("id"));
			assertEquals("view", h.ask(() -> h.controller.sessionGroup(PAST_1)));
			assertEquals(List.of(other.sessionId()), other.snapshot().path("sessions").findValuesAsText("id"));
			assertEquals(List.of(), other.page.types(quiet), "the other view heard nothing of it");

			// What the page was sent: the switch, the history as it was replayed, then the whole state.
			List<String> types = view.page.types(mark);
			assertEquals(List.of("session", "state"), types.subList(0, 2), types.toString());
			assertEquals(PAST_1, view.page.ofType(ToWebview.Session.class, mark).get(0).sessionId());
			assertEquals(3, view.page.ofType(ToWebview.Chunk.class, mark).size(), "the replayed chunks reach the page one by one");
			ToWebview.State last = view.page.last(ToWebview.State.class);
			assertEquals(PAST_1, last.state().sessionId());
			assertTrue(last.state().toolCalls().containsKey(PAST_1_TOOL), "a state with the restored transcript was posted");
			assertEquals(state.path("items"), view.page.model().path("items"), "what the page built from the messages is the transcript");
			view.page.await(ToWebview.Sessions.class, mark, s -> s.sessions().stream()
					.anyMatch(each -> each.id().equals(PAST_1) && each.title().equals(PAST_1_TITLE) && each.status() == SessionStatus.IDLE),
					"the session list with the restored session");
			assertTrue(h.logger.has("session past-1 restoring (group view)"), h.logger.lines.toString());
			assertTrue(h.logger.has("session past-1 restored (4 items)"), h.logger.lines.toString());

			// Ids of terminals in replayed history are of an earlier run: they name nothing here.
			assertNull(h.controller.terminals().snapshot("term-1", PAST_1));
			assertFalse(h.controller.terminals().showTerminal("term-1", PAST_1));
			assertFalse(h.get(past.list()).stream().anyMatch(e -> e.sessionId().equals(PAST_1)), "a live session is no past session");

			// A restored session is an ordinary session: a prompt streams and the history stays.
			assertEquals("end_turn", view.turn("/scenario chat").stopReason());
			JsonNode later = view.snapshot();
			List<JsonNode> all = messages(later);
			assertEquals(PAST_1_QUESTION, text(all.get(0)));
			assertEquals("completed", later.at("/toolCalls/" + PAST_1_TOOL + "/status").asText());
			assertEquals("/scenario chat", text(all.get(all.size() - 2)), "the new prompt was echoed here");
			assertEquals("The answer is 42.", text(all.get(all.size() - 1)));
			assertEquals(PAST_1_TITLE, later.at("/sessions/0/title").asText(), "the title is still the agent's");

			// Restoring what is live already only shows it.
			view.send(new FromWebview.SelectSession(before));
			h.scheduler.flush();
			assertEquals(before, view.sessionId());
			assertEquals(PAST_1, h.get(past.open(PAST_1, view.handle)));
			assertEquals(PAST_1, view.sessionId());
			assertEquals(List.of(PAST_1, before), h.ask(() -> ids(view)), "and adds no session");
		}
	}

	@Test
	void restoringAnUnknownSessionFailsWithANoticeAndThePageKeepsItsSession() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			PastSessions past = PastSessions.install(h.controller, new FakePicker(h.signal));
			List<String> heard = new CopyOnWriteArrayList<>();
			h.controller.hooks().addListener(new ChatHooks.SessionListener() {
				@Override
				public void sessionOpened(String sessionId) {
					heard.add("opened " + sessionId);
				}

				@Override
				public void sessionClosing(String sessionId) {
					heard.add("closing " + sessionId);
				}
			});
			Client view = h.openView("view");
			String before = view.sessionId();
			int mark = view.mark();

			assertNull(h.get(past.open("nope", view.handle)), "the restore reports its failure");
			h.scheduler.flush();

			assertEquals(before, view.sessionId(), "the view shows what it showed before");
			assertEquals(before, h.ask(h.controller::focusedSessionId), "and the focus is back there");
			assertEquals(List.of(before), h.ask(h.controller::sessionIds), "no session is left over");
			assertEquals(List.of("opened " + before, "opened nope", "closing nope"), heard, "who heard of the session heard of its end");
			List<NoticeView> warnings = view.page.ofType(ToWebview.Notice.class, mark).stream().map(ToWebview.Notice::notice)
					.filter(n -> n.severity() == NoticeView.Severity.WARNING).toList();
			assertEquals(1, warnings.size(), view.page.ofType(ToWebview.Notice.class, mark).toString());
			assertTrue(warnings.get(0).text().startsWith("Could not restore session nope: "), warnings.get(0).text());
			assertTrue(warnings.get(0).text().contains("session not found"), warnings.get(0).text());
			JsonNode state = view.snapshot();
			assertEquals(List.of(warnings.get(0).text()), state.path("notices").findValuesAsText("text"),
					"the warning belongs to the displayed session; the progress notice went with the failed one");
			assertEquals(List.of(before), state.path("sessions").findValuesAsText("id"));
			// The page went to the placeholder and came back.
			assertEquals(List.of("nope", before),
					view.page.ofType(ToWebview.Session.class, mark).stream().map(ToWebview.Session::sessionId).toList());
			assertEquals("end_turn", view.turn("/scenario chat").stopReason());
		}
	}

	@Test
	void theHistoryButtonOffersThePastSessionsAndRestoresTheChosenOne() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			FakePicker picker = new FakePicker(h.signal);
			PastSessions past = PastSessions.install(h.controller, picker);
			Client view = h.openView("view");
			String before = view.sessionId();

			// The user closes the dialog: nothing happens.
			view.send(new FromWebview.BrowseSessions());
			h.await(() -> picker.offered.size() == 1, "the choice to be offered");
			h.scheduler.flush();
			assertEquals(List.of(PAST_1, "past-2"), picker.offered.get(0).stream().map(Entry::sessionId).toList());
			assertEquals(before, view.sessionId());
			assertEquals(List.of(before), h.ask(h.controller::sessionIds));

			// The user chooses the older one.
			picker.choice = "past-2";
			int mark = view.mark();
			view.send(new FromWebview.BrowseSessions());
			h.await(() -> h.logger.has("session past-2 restored"), "the chosen session to be restored");
			h.scheduler.flush();
			assertEquals("past-2", view.sessionId());
			assertEquals("past-2", h.ask(h.controller::focusedSessionId));
			JsonNode state = view.snapshot();
			assertEquals("Add a retry to the fetch helper.", text(messages(state).get(0)));
			assertEquals("Add retry to fetch", state.at("/sessions/0/title").asText());
			assertEquals("completed", state.at("/toolCalls/call-past-2-read/status").asText());
			assertEquals(List.of(), view.page.ofType(ToWebview.Notice.class, mark).stream().map(n -> n.notice().text())
					.filter(text -> !text.startsWith("Restoring session")).toList());

			// Closing it forgets it here only: the agent still has it, and it can be restored again.
			view.send(new FromWebview.CloseSession("past-2"));
			h.await(() -> h.logger.has("session past-2 closed"), "the restored session to close");
			h.scheduler.flush();
			assertEquals(before, view.sessionId());
			assertEquals(List.of(PAST_1, "past-2"), h.get(past.list()).stream().map(Entry::sessionId).toList());
			assertEquals("past-2", h.get(past.open("past-2", null)), "without a page: the one used last");
			assertEquals("past-2", view.sessionId());
			assertEquals("Add a retry to the fetch helper.", text(messages(view.snapshot()).get(0)));
			assertEquals(4, view.snapshot().path("items").size(), "the history once, not twice");
		}
	}

	@Test
	void anAgentThatCannotListSessionsGivesANoticeInsteadOfAChoice() throws Exception {
		try (Harness h = Harness.launch(dir, "--engine", "v2")) {
			FakePicker picker = new FakePicker(h.signal);
			PastSessions.install(h.controller, picker);
			Client view = h.openView("view");
			int mark = view.mark();

			view.send(new FromWebview.BrowseSessions());

			ToWebview.Notice notice = view.page.await(ToWebview.Notice.class, mark, n -> true, "the notice");
			assertEquals(NoticeView.Severity.WARNING, notice.notice().severity());
			assertTrue(notice.notice().text().startsWith("Could not list past sessions: "), notice.notice().text());
			h.scheduler.flush();
			assertEquals(List.of(), picker.offered);
			assertEquals(List.of(notice.notice().text()), view.snapshot().path("notices").findValuesAsText("text"),
					"the notice belongs to the session the page shows");
		}
	}

	@Test
	void aSessionClosedInThisRunComesBackWithItsCommandAsHistoryAndMayRunCommandsAgain() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			PastSessions past = PastSessions.install(h.controller, new FakePicker(h.signal));
			List<String> changed = new CopyOnWriteArrayList<>();
			h.controller.hooks().addListener(new ChatHooks.SessionListener() {
				@Override
				public void toolCallChanged(String sessionId, io.github.liaoalia.kiroacp.core.model.ToolCallView call) {
					changed.add(call.toolCallId());
				}
			});
			Client view = h.openView("view");
			String sessionId = view.sessionId();

			// A turn with a command; the question it asks is answered with no.
			int mark = view.mark();
			view.prompt("/scenario tour");
			ToWebview.Permission asked = view.page.await(ToWebview.Permission.class, mark, p -> true, "the question of the tour");
			JsonNode live = view.snapshot().at("/toolCalls/call-exec/content/0");
			String terminalId = live.path("terminalId").asText();
			assertTrue(terminalId.matches("term-[a-z0-9]+-\\d+"), live.toString());
			assertTrue(live.path("output").asText().contains("hello"), live.toString());
			assertEquals(0, live.path("exitCode").asInt(-1), live.toString());
			view.send(new FromWebview.PermissionResponse(asked.request().requestId(), "reject"));
			view.awaitTurnEnd(mark);
			assertTrue(changed.contains("call-exec"), changed.toString());

			view.send(new FromWebview.CloseSession(sessionId));
			h.await(() -> h.logger.has("session " + sessionId + " closed"), "the session to close");
			h.awaitSettled(view);
			assertNull(h.controller.terminals().snapshot(terminalId), "the terminal went with its session");
			ObjectNode command = Harness.MAPPER.createObjectNode().put("sessionId", sessionId).put("command", "node");
			command.putArray("args").add("-e").add("");
			assertTrue(h.get(h.controller.services.createTerminal(command).handle((created, error) -> error != null)),
					"no command for a closed session");

			// The same id again, restored: the card of the command is history. It names the terminal
			// of then, and shows nothing of a terminal of now.
			changed.clear();
			int restore = view.mark();
			assertEquals(sessionId, h.get(past.open(sessionId, view.handle)));
			h.scheduler.flush();
			JsonNode state = view.snapshot();
			assertEquals(sessionId, state.path("sessionId").asText());
			JsonNode replayed = state.at("/toolCalls/call-exec/content/0");
			assertEquals("terminal", replayed.path("type").asText(), state.at("/toolCalls/call-exec").toString());
			assertEquals(terminalId, replayed.path("terminalId").asText());
			assertEquals("", replayed.path("output").asText("?"), "no output of a live terminal: " + replayed);
			assertFalse(replayed.has("exitCode"), "and no exit code: " + replayed);
			for (ToWebview.ToolCall card : view.page.ofType(ToWebview.ToolCall.class, restore)) {
				for (JsonNode content : io.github.liaoalia.kiroacp.core.model.UiJson.toJson(card).at("/call/content")) {
					assertTrue(content.path("output").asText("").isEmpty() && !content.has("exitCode"),
							"a replayed card was filled from a terminal: " + content);
				}
			}
			assertEquals(List.of(), changed, "replayed tool calls are history: nobody is told to act on them");
			assertEquals("/scenario tour", state.at("/sessions/0/title").asText(), "without a title of the agent: the first replayed prompt");
			int showing = view.mark();
			view.send(new FromWebview.ShowTerminal(terminalId));
			assertEquals("That terminal is no longer available.",
					view.page.await(ToWebview.Notice.class, showing, n -> true, "the notice").notice().text());

			// The id was closed before; restored, its commands are started again.
			JsonNode created = h.get(h.controller.services.createTerminal(command));
			String again = created.path("terminalId").asText();
			assertNotEquals(terminalId, again);
			assertTrue(h.controller.terminals().snapshot(again, sessionId) != null, created.toString());
		}
	}

	private static List<String> ids(Client view) {
		return view.handle.snapshot().sessions().stream().map(s -> s.id()).toList();
	}

	/** The messages of a state, in order. */
	private static List<JsonNode> messages(JsonNode state) {
		List<JsonNode> out = new ArrayList<>();
		for (JsonNode item : state.path("items")) {
			if (item.has("message")) {
				out.add(item.get("message"));
			}
		}
		return out;
	}

	private static String text(JsonNode message) {
		StringBuilder text = new StringBuilder();
		for (JsonNode segment : message.path("segments")) {
			text.append(segment.path("text").asText(""));
		}
		return text.toString();
	}
}
