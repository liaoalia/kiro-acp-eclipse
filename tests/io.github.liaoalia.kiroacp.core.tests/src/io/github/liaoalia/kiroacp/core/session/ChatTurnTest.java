package io.github.liaoalia.kiroacp.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.liaoalia.kiroacp.core.model.FromWebview;
import io.github.liaoalia.kiroacp.core.model.MessageRole;
import io.github.liaoalia.kiroacp.core.model.MessageSegment;
import io.github.liaoalia.kiroacp.core.model.NoticeView;
import io.github.liaoalia.kiroacp.core.model.PermissionScope;
import io.github.liaoalia.kiroacp.core.model.PlanEntryView;
import io.github.liaoalia.kiroacp.core.model.ToWebview;
import io.github.liaoalia.kiroacp.core.model.ToolCallView;
import io.github.liaoalia.kiroacp.core.model.ToolContentView;
import io.github.liaoalia.kiroacp.core.model.ToolKind;
import io.github.liaoalia.kiroacp.core.model.ToolStatus;
import io.github.liaoalia.kiroacp.core.model.UiJson;
import io.github.liaoalia.kiroacp.core.session.ChatHooks.PromptRequest;
import io.github.liaoalia.kiroacp.core.session.Harness.Client;

/**
 * The chat itself against the reference's fake agent: what a page is sent,
 * message by message (reference e2e F1 and F6 at the level of the page
 * protocol).
 */
class ChatTurnTest {

	@TempDir
	Path dir;

	@Test
	void theExtraArgumentsOfTheSettingFollowAcpAndThePickerSettingsReachThePage() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			// Reference: `args = ["acp", ...cliArgs]`; F8.3: the snapshot carries the two settings of the page.
			h.settings.cliArgs = List.of("--agent-engine=v3", "--agent", "my agent");
			h.settings.showSettings = true;
			h.settings.sessionPanelCollapsed = true;
			Client view = h.openView("view");

			assertEquals(1, h.launches.size());
			assertEquals(List.of("acp", "--agent-engine=v3", "--agent", "my agent"), h.launches.get(0).args());
			assertTrue(h.logger.has("spawning: kiro-cli acp --agent-engine=v3 --agent my agent (cwd "), h.logger.lines.toString());
			assertEquals(Harness.json("{\"showSettings\":true,\"sessionPanelCollapsed\":true}"), view.snapshot().get("prefs"));
			assertEquals(Harness.json("{\"showSettings\":true,\"sessionPanelCollapsed\":true}"),
					view.page.jsonSince(0).get(0).at("/state/prefs"), "also the state a page gets for ready");
		}
	}

	@Test
	void aPageThatSaysReadyGetsItsStateThenTheAgentAndASession() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			Client view = h.attach("view", AttachOptions.ownGroup("view-1"));
			view.send(new FromWebview.Ready());
			h.awaitSettled(view);

			// The settings' command and the reference's default argument reach the agent factory.
			assertEquals(1, h.launches.size());
			assertEquals("kiro-cli", h.launches.get(0).command());
			assertEquals(List.of("acp"), h.launches.get(0).args());
			assertEquals(dir, h.launches.get(0).cwd());

			List<String> types = view.page.types(0);
			assertEquals(List.of("state", "connection", "connection", "connection", "connection", "session", "state"),
					types.subList(0, 7), types.toString());
			// The agent announces its commands; the session state they change follows, as in the reference.
			// The session list comes when its debounce has passed, wherever that falls.
			List<String> rest = types.subList(7, types.size());
			assertEquals(List.of("commands", "modes", "configOptions", "models", "commands"),
					rest.stream().filter(type -> !type.equals("sessions")).toList(), types.toString());
			assertEquals(1, rest.stream().filter("sessions"::equals).count(), types.toString());

			List<JsonNode> sent = view.page.jsonSince(0);
			JsonNode first = sent.get(0).get("state");
			assertEquals("stopped", first.path("connection").path("status").asText());
			assertTrue(first.get("sessionId").isNull(), "no session before the agent runs");
			assertEquals(Harness.json("{\"showSettings\":false,\"sessionPanelCollapsed\":false}"), first.get("prefs"));
			assertFalse(first.get("acceptsPromptsDuringTurn").asBoolean(), "nothing takes prompts during a turn");

			assertEquals(Harness.json("{\"type\":\"connection\",\"connection\":{\"status\":\"starting\",\"message\":\"Starting kiro-cli\u2026\"}}"),
					sent.get(1));
			assertEquals(Harness.json("{\"type\":\"connection\",\"connection\":{\"status\":\"starting\"}}"), sent.get(2));
			JsonNode ready = Harness.json("{\"type\":\"connection\",\"connection\":{\"status\":\"ready\",\"agentName\":\"Fake Kiro\","
					+ "\"agentVersion\":\"3.0.0-fake\",\"canBrowseSessions\":true,\"acceptsImages\":true}}");
			assertEquals(ready, sent.get(3));
			assertEquals(ready, sent.get(4));

			String sessionId = view.sessionId();
			assertEquals(Harness.json("{\"type\":\"session\",\"sessionId\":\"" + sessionId + "\"}"), sent.get(5));
			JsonNode state = sent.get(6).get("state");
			assertEquals(sessionId, state.get("sessionId").asText());
			assertEquals("ready", state.path("connection").path("status").asText());
			assertEquals("vibe", state.path("modes").path("currentModeId").asText());
			assertEquals(List.of("model", "effortLevel", "autoCompact"), state.path("configOptions").findValuesAsText("id"));
			assertTrue(state.get("models").isNull(), "the V3 agent has no model list of its own");
			assertEquals(0, state.path("items").size());
			assertEquals(Harness.NOW, state.path("lastActivityAt").asLong());
			// The first session was opened for nobody in particular; the view that shows it has adopted it.
			assertEquals("view-1", h.ask(() -> h.controller.sessionGroup(sessionId)));
			assertEquals(sessionId, h.ask(h.controller::focusedSessionId));

			ToWebview.Sessions sessions = view.page.last(ToWebview.Sessions.class);
			assertEquals(Harness.json("[{\"id\":\"" + sessionId + "\",\"title\":\"New session\",\"status\":\"idle\",\"updatedAt\":"
					+ Harness.NOW + "}]"), UiJson.toJson(sessions).get("sessions"));
			assertEquals(List.of("plan", "compact"),
					view.page.last(ToWebview.Commands.class).commands().stream().map(c -> c.name()).toList());
		}
	}

	@Test
	void aChatTurnIsPostedMessageByMessage() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			Client view = h.openView("view");
			String sessionId = view.sessionId();
			h.clock.set(Harness.NOW + 1000);
			int calls = h.actions.calls.size();

			int mark = view.mark();
			ToWebview.Turn end = view.turn("  /scenario chat\n");
			assertEquals("end_turn", end.stopReason());

			String running = "{\"type\":\"sessions\",\"sessions\":[{\"id\":\"" + sessionId
					+ "\",\"title\":\"/scenario chat\",\"status\":\"%s\",\"updatedAt\":" + (Harness.NOW + 1000) + "}]}";
			List<JsonNode> expected = List.of(
					// The user's message is echoed here, not by the agent (reference D13), trimmed.
					Harness.json("{\"type\":\"chunk\",\"messageId\":\"msg-1\",\"role\":\"user\",\"segment\":{\"type\":\"text\",\"text\":\"/scenario chat\"}}"),
					Harness.json("{\"type\":\"turn\",\"active\":true}"),
					Harness.json(String.format(running, "running")),
					Harness.json("{\"type\":\"chunk\",\"messageId\":\"msg-2\",\"role\":\"agent\",\"segment\":{\"type\":\"text\",\"text\":\"The answer \"}}"),
					Harness.json("{\"type\":\"chunk\",\"messageId\":\"msg-2\",\"role\":\"agent\",\"segment\":{\"type\":\"text\",\"text\":\"is \"}}"),
					Harness.json("{\"type\":\"chunk\",\"messageId\":\"msg-2\",\"role\":\"agent\",\"segment\":{\"type\":\"text\",\"text\":\"42.\"}}"),
					Harness.json("{\"type\":\"turn\",\"active\":false,\"stopReason\":\"end_turn\"}"),
					Harness.json(String.format(running, "idle")));
			assertEquals(expected, view.page.jsonSince(mark));

			JsonNode state = view.snapshot();
			assertFalse(state.get("turnActive").asBoolean());
			assertEquals("The answer is 42.", state.at("/items/1/message/segments/0/text").asText());
			assertEquals(List.of("turnActive true", "turnActive false"), h.actions.calls.subList(calls, h.actions.calls.size()),
					"the focused session's turn enables and disables Cancel");
		}
	}

	@Test
	void theTourShowsToolCardsPlanAndAPermissionAndReadyGivesTheSameState() throws Exception {
		Files.write(dir.resolve("README.md"), "# Tour\nsecond line\n".getBytes(StandardCharsets.UTF_8));
		try (Harness h = Harness.launch(dir)) {
			// What the features that plug in later are told (diff review, plan document).
			List<String> heard = new CopyOnWriteArrayList<>();
			h.controller.hooks().addListener(new ChatHooks.SessionListener() {
				@Override
				public void sessionOpened(String sessionId) {
					heard.add("opened " + sessionId);
				}

				@Override
				public void focusChanged(String sessionId) {
					heard.add("focus " + sessionId);
				}

				@Override
				public void toolCallChanged(String sessionId, ToolCallView call) {
					heard.add("tool " + call.toolCallId() + " " + call.status());
				}

				@Override
				public void planChanged(String sessionId, List<PlanEntryView> entries) {
					heard.add("plan " + entries.size());
				}

				@Override
				public void permissionRequested(String sessionId, io.github.liaoalia.kiroacp.core.model.PermissionView request) {
					heard.add("permission " + request.toolCallId());
				}
			});
			Client view = h.openView("view");
			int mark = view.mark();
			view.prompt("/scenario tour");

			ToWebview.Permission asked = view.page.await(ToWebview.Permission.class, mark, p -> true, "the permission card");
			assertEquals("call-edit", asked.request().toolCallId());
			assertEquals(ToolKind.EDIT, asked.request().kind());
			assertEquals("Create hello.txt", asked.request().title());
			assertTrue(asked.request().hasDiff());
			assertEquals(List.of("accept", "always-accept", "reject", "always-reject"),
					asked.request().options().stream().map(o -> o.optionId()).toList());
			assertEquals("fs_write", asked.request().consent().capability());
			assertEquals(List.of(PermissionScope.KIND, PermissionScope.CAPABILITY, PermissionScope.RESOURCE),
					asked.request().policies().stream().map(p -> p.scope()).toList());
			// While the question is open the page may be reloaded: the snapshot carries the card.
			JsonNode waiting = view.snapshot();
			assertEquals(asked.request().requestId(), waiting.at("/permissions/0/requestId").asText());
			assertTrue(waiting.get("turnActive").asBoolean());
			assertFalse(Files.exists(dir.resolve("hello.txt")), "nothing is written before the answer");

			view.send(new FromWebview.PermissionResponse(asked.request().requestId(), "accept"));
			assertEquals("end_turn", view.awaitTurnEnd(mark).stopReason());
			assertEquals("hello from kiro\n", new String(Files.readAllBytes(dir.resolve("hello.txt")), StandardCharsets.UTF_8));
			assertEquals(List.of("https://example.invalid/oauth"), h.external.urls);

			// Text and thought.
			List<ToWebview.Chunk> chunks = view.page.ofType(ToWebview.Chunk.class, mark);
			assertEquals(MessageRole.USER, chunks.get(0).role());
			assertEquals(List.of("Hello! ", "I am the fake Kiro agent. ", "Let me look around."),
					chunks.stream().filter(c -> c.role() == MessageRole.AGENT && c.messageId().equals(chunks.get(1).messageId()))
							.map(c -> ((MessageSegment.Text) c.segment()).text()).toList());
			assertEquals(1, chunks.stream().filter(c -> c.role() == MessageRole.THOUGHT).count());

			// The read tool: announced in progress, then completed with its text.
			List<ToolCallView> read = cards(view, mark, "call-read");
			assertEquals(List.of(ToolStatus.IN_PROGRESS, ToolStatus.COMPLETED), read.stream().map(ToolCallView::status).toList());
			assertEquals(ToolKind.READ, read.get(1).kind());
			assertEquals(List.of(new ToolContentView.Text("Read README.md")), read.get(1).content());
			assertEquals(1, read.get(0).locations().size());

			// The plan, twice.
			List<ToWebview.Plan> plans = view.page.ofType(ToWebview.Plan.class, mark);
			assertEquals(2, plans.size());
			assertEquals(List.of(PlanEntryView.Status.COMPLETED, PlanEntryView.Status.IN_PROGRESS, PlanEntryView.Status.PENDING),
					plans.get(1).entries().stream().map(PlanEntryView::status).toList());

			// The command: its card names the terminal, and the host fills in what the terminal printed.
			List<ToolCallView> exec = cards(view, mark, "call-exec");
			assertEquals(3, exec.size());
			assertEquals(List.of(), exec.get(0).content());
			ToolContentView.Terminal running = (ToolContentView.Terminal) exec.get(1).content().get(0);
			assertTrue(running.terminalId().matches("term-[a-z0-9]+-\\d+"), running.toString());
			assertNotNull(running.output(), "the card of a known terminal always carries its output so far");
			ToolContentView.Terminal ended = (ToolContentView.Terminal) exec.get(2).content().get(0);
			assertEquals(ToolStatus.COMPLETED, exec.get(2).status());
			assertTrue(ended.output().contains("hello"), ended.toString());
			assertEquals(0, ended.exitCode().asInt(-1));
			assertEquals(running.terminalId(), ended.terminalId());

			// The edit: its diff, the question, the answer, then the agent goes on.
			List<ToolCallView> edit = cards(view, mark, "call-edit");
			assertEquals(List.of(ToolStatus.PENDING, ToolStatus.IN_PROGRESS, ToolStatus.COMPLETED),
					edit.stream().map(ToolCallView::status).toList());
			ToolContentView.Diff diff = (ToolContentView.Diff) edit.get(0).content().get(0);
			assertNull(diff.oldText(), "a file that does not exist yet");
			assertEquals("hello from kiro\n", diff.newText());
			assertTrue(diff.path().endsWith("hello.txt"), diff.path());
			assertNull(edit.get(0).pendingPermissionId(), "the page ties the card to the request itself, as in the reference");
			List<ToWebview> since = view.page.since(mark);
			int pending = since.indexOf(new ToWebview.ToolCall(edit.get(0)));
			int question = since.indexOf(asked);
			int answered = since.indexOf(new ToWebview.PermissionResolved(asked.request().requestId()));
			int inProgress = since.indexOf(new ToWebview.ToolCall(edit.get(1)));
			assertTrue(0 <= pending && pending < question && question < answered && answered < inProgress,
					pending + " < " + question + " < " + answered + " < " + inProgress);

			assertEquals(List.of("focus null", "opened " + view.sessionId(), "focus " + view.sessionId(), "tool call-read IN_PROGRESS",
					"tool call-read COMPLETED", "plan 3", "plan 3", "tool call-exec IN_PROGRESS", "tool call-exec IN_PROGRESS",
					"tool call-exec COMPLETED", "tool call-edit PENDING", "permission call-edit", "tool call-edit IN_PROGRESS",
					"tool call-edit COMPLETED"), heard);

			// What the page arrived at message by message is what it gets when it reloads.
			h.scheduler.flush();
			JsonNode incremental = comparable(view.page.model());
			assertEquals(7, incremental.path("items").size(), incremental.path("items").toString());
			int reload = view.mark();
			view.send(new FromWebview.Ready());
			ToWebview.State snapshot = view.page.await(ToWebview.State.class, reload, s -> true, "the snapshot");
			assertEquals(incremental, comparable(UiJson.toJson(snapshot).get("state")));
			assertEquals(List.of("state"), view.page.types(reload), "ready is answered with the state and nothing else");
		}
	}

	@Test
	void aStopReasonTheUserShouldKnowAboutBecomesANotice() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			Client view = h.openView("view");
			int mark = view.mark();
			assertEquals("refusal", view.turn("/scenario refusal").stopReason());

			List<ToWebview.Notice> notices = view.page.ofType(ToWebview.Notice.class, mark);
			assertEquals(1, notices.size());
			assertEquals(new NoticeView("n-1", NoticeView.Severity.WARNING, "Kiro declined to continue with this request."),
					notices.get(0).notice());
			List<String> types = view.page.types(mark);
			assertTrue(types.indexOf("notice") > types.lastIndexOf("turn"), "the turn ends first: " + types);
			assertEquals("n-1", view.snapshot().at("/notices/0/id").asText());
		}
	}

	@Test
	void aPromptDuringATurnIsRefusedUnlessAFeatureTakesIt() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			Client view = h.openView("view");
			int mark = view.mark();
			view.prompt("/scenario cancel");
			h.await(() -> view.agentChunks(mark).size() >= 2, "the turn to stream");

			view.prompt("and another thing");
			ToWebview.Notice refused = view.page.await(ToWebview.Notice.class, mark, n -> true, "the refusal");
			assertEquals(NoticeView.Severity.INFO, refused.notice().severity());
			assertEquals("Wait for the current turn to finish or cancel it first.", refused.notice().text());
			assertEquals(1, view.page.ofType(ToWebview.Chunk.class, mark).stream().filter(c -> c.role() == MessageRole.USER).count(),
					"the refused prompt is not echoed");

			// The seam of steering and queueing (reference D22): what is registered gets the prompt, with its blocks.
			List<String> taken = new CopyOnWriteArrayList<>();
			h.controller.hooks().setMidTurnPrompts((request, blocks) -> {
				taken.add(request.sessionId() + ": " + request.text() + " " + blocks);
				h.signal.changed();
				return CompletableFuture.completedFuture(null);
			});
			assertTrue(view.snapshot().get("acceptsPromptsDuringTurn").asBoolean());
			view.prompt("steer this way");
			h.await(() -> !taken.isEmpty(), "the mid-turn prompt to reach the hook");
			assertEquals(List.of(view.sessionId() + ": steer this way [{\"type\":\"text\",\"text\":\"steer this way\"}]"), taken);

			view.send(new FromWebview.Cancel());
			assertEquals("cancelled", view.awaitTurnEnd(mark).stopReason());
			assertEquals(1, view.page.ofType(ToWebview.Notice.class, mark).size());
		}
	}

	@Test
	void whatThePageAsksOfTheIdeGoesToTheActions() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			Client view = h.openView("view");
			int mark = view.mark();
			int calls = h.actions.calls.size();
			view.send(new FromWebview.ShowLog());
			view.send(new FromWebview.OpenFile("C:\\work\\a.txt", 12));
			view.send(new FromWebview.OpenFile("/work/b.txt", null));
			view.send(new FromWebview.ShowTerminal("term-none-1"));

			ToWebview.Notice gone = view.page.await(ToWebview.Notice.class, mark, n -> true, "the notice about the terminal");
			assertEquals(new NoticeView(gone.notice().id(), NoticeView.Severity.INFO, "That terminal is no longer available."),
					gone.notice());
			assertEquals(List.of("showLog", "openFile C:\\work\\a.txt 12", "openFile /work/b.txt null"),
					h.actions.calls.subList(calls, h.actions.calls.size()));
		}
	}

	@Test
	void aMessageTheControllerDoesNotHandleReachesTheHandlerRegisteredForIt() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			Client view = h.openView("view");
			List<String> handled = new CopyOnWriteArrayList<>();
			h.controller.hooks().onMessage(FromWebview.QueryFiles.class, (message, page) -> {
				handled.add(message.query() + " from " + page.sessionId() + " on the controller thread: "
						+ h.scheduler.isControllerThread());
				page.post(new ToWebview.FileSuggestions(message.query(), List.of()));
			});
			// A type the controller handles itself never reaches a handler.
			h.controller.hooks().onMessage(FromWebview.ShowLog.class, (message, page) -> handled.add("showLog"));

			int mark = view.mark();
			view.send(new FromWebview.QueryFiles("rea"));
			view.send(new FromWebview.ShowLog());
			// Nobody handles these: logged, and nothing breaks.
			view.send(new FromWebview.OpenPlan());
			view.send(new FromWebview.BrowseSessions());
			assertEquals("end_turn", view.turn("/scenario chat").stopReason());

			assertEquals(List.of("rea from " + view.sessionId() + " on the controller thread: true"), handled);
			assertEquals(new ToWebview.FileSuggestions("rea", List.of()), view.page.since(mark).get(0));
			assertTrue(h.logger.has("page message without a handler: openPlan"), h.logger.lines.toString());
			assertTrue(h.logger.has("page message without a handler: browseSessions"), h.logger.lines.toString());
		}
	}

	@Test
	void theSeamsOfThePromptAreAskedInOrder() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			Client view = h.openView("view");
			List<String> seen = new CopyOnWriteArrayList<>();
			ChatHooks hooks = h.controller.hooks();
			// A slash command run by the client (reference D18): the prompt never reaches the agent.
			hooks.addPromptInterceptor(request -> {
				seen.add("intercept " + request.text());
				return CompletableFuture.completedFuture(request.text().startsWith("/local"));
			});
			hooks.setPromptComposer(new ChatHooks.PromptComposer() {
				@Override
				public CompletableFuture<List<JsonNode>> blocks(PromptRequest request, io.github.liaoalia.kiroacp.core.acp.api.AgentInfo agent) {
					seen.add("blocks " + request.text() + " images=" + agent.acceptsImages());
					ObjectNode text = Harness.MAPPER.createObjectNode().put("type", "text").put("text", request.text());
					return CompletableFuture.completedFuture(List.of(text));
				}

				@Override
				public List<JsonNode> echo(PromptRequest request) {
					return List.of(Harness.json("{\"type\":\"resource_link\",\"uri\":\"file:///work/a.txt\",\"name\":\"a.txt\"}"));
				}

				@Override
				public void sent(PromptRequest request) {
					seen.add("sent " + request.text());
				}
			});
			hooks.addListener(new ChatHooks.SessionListener() {
				@Override
				public void turnStarted(String sessionId) {
					seen.add("turnStarted");
				}

				@Override
				public void turnEnded(String sessionId, String stopReason) {
					seen.add("turnEnded " + stopReason);
				}

				@Override
				public void afterTurn(String sessionId, String stopReason) {
					seen.add("afterTurn " + stopReason);
				}
			});

			int mark = view.mark();
			view.prompt("/local help");
			assertEquals("end_turn", view.turn("/scenario chat").stopReason());

			assertEquals(List.of("intercept /local help", "intercept /scenario chat", "blocks /scenario chat images=true",
					"sent /scenario chat", "turnStarted", "turnEnded end_turn", "afterTurn end_turn"), seen);
			List<ToWebview.Chunk> user = view.page.ofType(ToWebview.Chunk.class, mark).stream()
					.filter(c -> c.role() == MessageRole.USER).toList();
			assertEquals(List.of(new MessageSegment.Text("/scenario chat"), new MessageSegment.Resource("file:///work/a.txt", "a.txt")),
					user.stream().map(ToWebview.Chunk::segment).toList());
			assertEquals(1, user.stream().map(ToWebview.Chunk::messageId).distinct().count(), "one message");
		}
	}

	private static List<ToolCallView> cards(Client view, int mark, String toolCallId) {
		return view.page.ofType(ToWebview.ToolCall.class, mark).stream().map(ToWebview.ToolCall::call)
				.filter(call -> call.toolCallId().equals(toolCallId)).toList();
	}

	/** A {@code UiState} without what the page keeps for itself. */
	private static JsonNode comparable(JsonNode state) {
		ObjectNode copy = state.deepCopy();
		// The page stamps its own clock on activity; the snapshot carries the host's.
		copy.remove("lastActivityAt");
		return copy;
	}
}
