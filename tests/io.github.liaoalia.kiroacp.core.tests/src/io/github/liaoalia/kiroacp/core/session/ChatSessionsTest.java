package io.github.liaoalia.kiroacp.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import io.github.liaoalia.kiroacp.core.model.ConnectionStatus;
import io.github.liaoalia.kiroacp.core.model.FromWebview;
import io.github.liaoalia.kiroacp.core.model.NoticeView;
import io.github.liaoalia.kiroacp.core.model.SessionStatus;
import io.github.liaoalia.kiroacp.core.model.SessionSummary;
import io.github.liaoalia.kiroacp.core.model.ToWebview;
import io.github.liaoalia.kiroacp.core.session.ChatHooks.Option;
import io.github.liaoalia.kiroacp.core.session.Harness.Client;

/**
 * Several sessions in one agent process, the pages that show them, and the
 * life of the process (reference D19, D20; e2e F10, with the crash and
 * sign-in paths of the core chat).
 */
class ChatSessionsTest {

	@TempDir
	Path dir;

	@Test
	void twoViewsHaveTheirOwnSessionsAndHearOnlyOfTheirOwn() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			Client one = h.openView("one");
			Client two = h.openView("two");
			String s1 = one.sessionId();
			String s2 = two.sessionId();
			assertNotEquals(s1, s2);
			assertEquals(1, h.launches.size(), "one agent process for both");
			assertEquals(List.of(s1), ids(one.snapshot()), "view one lists only its own session");
			assertEquals(List.of(s2), ids(two.snapshot()));
			assertEquals(s2, h.ask(h.controller::focusedSessionId), "the view used last has the focus");

			// A turn in one view: the other hears nothing, not even a session list (its list did not change).
			int quiet = two.mark();
			assertEquals("end_turn", one.turn("/scenario chat").stopReason());
			h.scheduler.flush();
			assertEquals(List.of(), two.page.types(quiet));
			assertEquals(0, two.snapshot().path("items").size());
			assertEquals(2, one.snapshot().path("items").size());
			assertEquals(s1, h.ask(h.controller::focusedSessionId), "a message from a view focuses its session");

			// A new session in view one: displayed there, listed there, most recent first.
			int mark = one.mark();
			one.send(new FromWebview.NewSession());
			ToWebview.Session switched = one.page.await(ToWebview.Session.class, mark, s -> true, "the new session");
			h.awaitSettled(one);
			String s3 = switched.sessionId();
			assertEquals(s3, one.sessionId());
			assertEquals(List.of("session", "state"), one.page.types(mark).subList(0, 2), "a state snapshot follows the switch");
			assertEquals(0, one.snapshot().path("items").size());
			assertEquals(List.of(s3, s1), ids(one.snapshot()));
			assertEquals(List.of(s2), ids(two.snapshot()), "view two's list is unchanged");
			assertEquals("one", h.ask(() -> h.controller.sessionGroup(s3)));

			// Back to the first session: its transcript is still there, and the title is its first prompt.
			int back = one.mark();
			one.send(new FromWebview.SelectSession(s1));
			ToWebview.State restored = one.page.await(ToWebview.State.class, back, s -> true, "the state of the selected session");
			assertEquals(s1, restored.state().sessionId());
			assertEquals(2, restored.state().items().size());
			assertEquals("/scenario chat",
					restored.state().sessions().stream().filter(s -> s.id().equals(s1)).findFirst().orElseThrow().title());

			// Another view's session is not this view's to show.
			int refused = one.mark();
			one.send(new FromWebview.SelectSession(s2));
			h.scheduler.flush();
			assertEquals(s1, one.sessionId());
			assertEquals(List.of(), one.page.types(refused));

			// Closing the displayed session: the view falls back to its other one, and so does the focus.
			int closing = one.mark();
			one.send(new FromWebview.CloseSession(s1));
			one.page.await(ToWebview.Session.class, closing, s -> s3.equals(s.sessionId()), "the fallback to the remaining session");
			h.scheduler.flush();
			assertEquals(s3, one.sessionId());
			assertEquals(s3, h.ask(h.controller::focusedSessionId));
			assertEquals(List.of(s3), ids(one.snapshot()));
			assertEquals(List.of(s3), one.page.last(ToWebview.Sessions.class).sessions().stream().map(SessionSummary::id).toList());
			assertEquals(s2, two.sessionId(), "view two still displays its own session");

			// Closing the view ends its sessions; the other view's session lives on.
			one.handle.close();
			h.await(() -> h.logger.has("session " + s3 + " closed"), "view one's session to close with the view");
			h.scheduler.flush();
			assertEquals(List.of(s2), h.ask(h.controller::sessionIds));
			assertEquals("end_turn", two.turn("/scenario chat").stopReason());
		}
	}

	@Test
	void aPageWithoutAGroupListsEverySessionAndStartsOnTheFocusedOne() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			Client view = h.openView("view");
			assertEquals("end_turn", view.turn("/scenario chat").stopReason());

			Client all = h.open("all", AttachOptions.shared());
			assertEquals(view.sessionId(), all.sessionId(), "it shows the focused session");
			assertEquals(2, all.snapshot().path("items").size());
			assertEquals("view", h.ask(() -> h.controller.sessionGroup(view.sessionId())), "and leaves it in its group");

			// Both pages show the session, so both get its messages.
			int markAll = all.mark();
			int markView = view.mark();
			assertEquals("end_turn", all.turn("/scenario chat").stopReason());
			view.awaitTurnEnd(markView);
			assertEquals(all.page.jsonSince(markAll), view.page.jsonSince(markView));
		}
	}

	@Test
	void aViewClosedWhileItsSessionIsBeingCreatedLeavesNoSessionBehind() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			Client one = h.openView("one");
			// Give the session a transcript, so the next view cannot simply take it over.
			assertEquals("end_turn", one.turn("/scenario chat").stopReason());

			Client gone = h.attach("gone", AttachOptions.ownGroup("gone"));
			gone.send(new FromWebview.Ready());
			gone.handle.close();
			h.await(() -> h.logger.lines.stream().filter(line -> line.contains(" opened (group gone)")).count() == 1
					&& h.logger.lines.stream().filter(line -> line.matches("info: session \\S+ closed")).count() == 1,
					"the session opened for the closed view to be closed again");
			h.scheduler.flush();
			assertEquals(List.of(one.sessionId()), h.ask(h.controller::sessionIds));
			assertEquals(one.sessionId(), h.ask(h.controller::focusedSessionId), "a session remains focused");
			assertTrue(h.ask(h.controller::hasPages));
		}
	}

	@Test
	void aCrashEndsTheTurnAsAnErrorAndRestartBringsAFreshSession() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			Client view = h.openView("view");
			String before = view.sessionId();
			int mark = view.mark();
			view.prompt("/scenario crash");

			ToWebview.Connection down = view.page.await(ToWebview.Connection.class, mark,
					c -> c.connection().status() == ConnectionStatus.ERROR, "the connection to fail");
			assertEquals("kiro-cli exited with code 3", down.connection().message());
			assertEquals("Fake Kiro", down.connection().agentName(), "the page still knows which agent it was");
			h.await(() -> h.logger.has("late prompt failure ignored") || view.page.ofType(ToWebview.Notice.class, mark).stream()
					.anyMatch(n -> n.notice().text().startsWith("Prompt failed")), "the failed prompt to settle");
			h.scheduler.flush();

			// The turn ended as an error, once, whichever of the two (the exit, the failed prompt) was noticed first.
			List<ToWebview.Turn> ends = view.page.ofType(ToWebview.Turn.class, mark).stream().filter(turn -> !turn.active()).toList();
			assertEquals(List.of(new ToWebview.Turn(false, "error")), ends);
			assertTrue(view.page.ofType(ToWebview.Notice.class, mark).stream()
					.anyMatch(n -> n.notice().severity() == NoticeView.Severity.ERROR
							&& n.notice().text().equals("kiro-cli exited with code 3")),
					view.page.ofType(ToWebview.Notice.class, mark).toString());
			// The session stays visible, marked as broken, with what was streamed.
			assertEquals(before, view.sessionId());
			JsonNode state = view.snapshot();
			assertFalse(state.get("turnActive").asBoolean());
			assertEquals("about to crash", state.at("/items/1/message/segments/0/text").asText());
			assertEquals("error", state.at("/sessions/0/status").asText());
			assertEquals(SessionStatus.ERROR, view.page.last(ToWebview.Sessions.class).sessions().get(0).status());

			int restart = view.mark();
			view.send(new FromWebview.Restart());
			ToWebview.State fresh = view.page.await(ToWebview.State.class, restart,
					s -> s.state().sessionId() != null && s.state().connection().status() == ConnectionStatus.READY, "a fresh session");
			h.awaitSettled(view);
			// A new process numbers its sessions from the start again, so the id says nothing; the transcript does.
			assertEquals(0, fresh.state().items().size());
			assertEquals(List.of(view.sessionId()), h.ask(h.controller::sessionIds), "the old session is gone");
			assertEquals(2, h.launches.size());
			List<String> types = view.page.types(restart);
			// The old session ends first (a `session` without an id and an empty state), then the new process reports.
			assertEquals(List.of("session", "state"), types.subList(0, 2), types.toString());
			assertTrue(view.page.jsonSince(restart).get(0).get("sessionId").isNull());
			List<ConnectionStatus> statuses = view.page.ofType(ToWebview.Connection.class, restart).stream()
					.map(c -> c.connection().status()).toList();
			assertEquals(List.of(ConnectionStatus.STOPPED, ConnectionStatus.STARTING, ConnectionStatus.STARTING, ConnectionStatus.READY,
					ConnectionStatus.READY), statuses);
			assertEquals("idle", view.snapshot().at("/sessions/0/status").asText());

			assertEquals("end_turn", view.turn("/scenario chat").stopReason());
			assertEquals("The answer is 42.", view.snapshot().at("/items/1/message/segments/0/text").asText());
		}
	}

	@Test
	void aStoppedAgentMarksEverySessionAsBrokenAndThePromptAfterItStartsAnew() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			Client view = h.openView("view");
			String before = view.sessionId();
			int mark = view.mark();
			h.get(h.controller.shutdownAgent());
			ToWebview.Connection stopped = view.page.await(ToWebview.Connection.class, mark,
					c -> c.connection().status() == ConnectionStatus.STOPPED, "stopped");
			assertEquals("agent stopped", stopped.connection().message());
			h.scheduler.flush();
			assertEquals(before, view.sessionId(), "the sessions are still listed after the agent stopped");
			assertEquals(SessionStatus.ERROR, view.page.last(ToWebview.Sessions.class).sessions().get(0).status());
			assertEquals(0, view.page.ofType(ToWebview.Notice.class, mark).size(), "stopping on purpose is no error");

			// "Show log" must not start the CLI again; a prompt does, as in the reference, and goes to the fresh session.
			view.send(new FromWebview.ShowLog());
			h.scheduler.flush();
			assertEquals(1, h.launches.size());
			int prompt = view.mark();
			view.prompt("/scenario chat");
			assertEquals("end_turn", view.awaitTurnEnd(prompt).stopReason());
			assertEquals(2, h.launches.size());
			List<String> types = view.page.types(prompt);
			assertEquals(List.of("session", "state"), types.subList(0, 2), "the old session ends before the new process starts: " + types);
			assertEquals(2, view.snapshot().path("items").size(), "the fresh session has this exchange only");
			assertEquals("The answer is 42.", view.snapshot().at("/items/1/message/segments/0/text").asText());
		}
	}

	@Test
	void modeAndConfigOptionChosenInThePageReachTheAgentAndComeBack() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			Client view = h.openView("view");
			List<String> chosen = new CopyOnWriteArrayList<>();
			h.controller.hooks().addListener(new ChatHooks.SessionListener() {
				@Override
				public void optionChosen(String sessionId, Option option, String id, JsonNode value) {
					chosen.add(option + " " + id + " " + value);
					h.signal.changed();
				}
			});

			int mark = view.mark();
			view.send(new FromWebview.SetMode("plan"));
			view.page.await(ToWebview.Modes.class, mark, m -> m.modes().currentModeId().equals("plan"), "the mode to change");
			h.await(() -> chosen.size() == 1, "the choice to be reported");
			assertEquals("plan", view.snapshot().at("/modes/currentModeId").asText());
			assertEquals(2, view.snapshot().at("/modes/availableModes").size());

			int config = view.mark();
			view.send(new FromWebview.SetConfigOption("effortLevel", JsonNodeFactory.instance.textNode("high")));
			view.page.await(ToWebview.ConfigOptions.class, config,
					c -> c.options().stream().anyMatch(o -> o.id().equals("effortLevel") && o.currentValue().asText().equals("high")),
					"the option to change");
			h.await(() -> chosen.size() == 2, "the choice to be reported");
			assertEquals("high", view.snapshot().at("/configOptions/1/currentValue").asText());
			assertEquals("claude-sonnet-4", view.snapshot().at("/configOptions/0/currentValue").asText());
			// A boolean option travels as a boolean.
			int flag = view.mark();
			view.send(new FromWebview.SetConfigOption("autoCompact", JsonNodeFactory.instance.booleanNode(false)));
			view.page.await(ToWebview.ConfigOptions.class, flag,
					c -> c.options().stream().anyMatch(o -> o.id().equals("autoCompact") && o.currentValue().isBoolean()
							&& !o.currentValue().asBoolean()),
					"the flag to change");
			h.await(() -> chosen.size() == 3, "the choice to be reported");

			// What the agent refuses is said in the page and not remembered.
			int refused = view.mark();
			view.send(new FromWebview.SetMode("no-such-mode"));
			ToWebview.Notice notice = view.page.await(ToWebview.Notice.class, refused, n -> true, "the refusal");
			assertEquals(NoticeView.Severity.WARNING, notice.notice().severity());
			assertTrue(notice.notice().text().contains("no-such-mode"), notice.notice().text());
			// The V3 agent has no session/set_model.
			view.send(new FromWebview.SetModel("nova-pro"));
			h.await(() -> view.page.ofType(ToWebview.Notice.class, refused).size() == 2, "the second refusal");
			h.scheduler.flush();
			assertEquals("plan", view.snapshot().at("/modes/currentModeId").asText());
			assertEquals(List.of("MODE plan \"plan\"", "CONFIG effortLevel \"high\"", "CONFIG autoCompact false"), chosen);
			assertEquals(view.snapshot().path("modes"), view.page.model().path("modes"));
			assertEquals(view.snapshot().path("configOptions"), view.page.model().path("configOptions"));
		}
	}

	@Test
	void aSignedOutAgentShowsTheSignInStateInsteadOfASession() throws Exception {
		try (Harness h = Harness.launch(dir, "--auth-required")) {
			Client view = h.attach("view", AttachOptions.ownGroup("view"));
			view.send(new FromWebview.Ready());
			ToWebview.Connection signIn = view.page.await(ToWebview.Connection.class, 0,
					c -> c.connection().status() == ConnectionStatus.AUTH_REQUIRED && c.connection().message() != null
							&& c.connection().message().startsWith("Sign in"),
					"the sign-in state");
			assertEquals("Sign in to Kiro to start a session.", signIn.connection().message());
			assertEquals(List.of("kiro-login"), signIn.connection().authMethods().stream().map(m -> m.id()).toList());
			assertTrue(signIn.connection().authMethods().get(0).terminal());
			h.scheduler.flush();
			assertNull(view.sessionId());
			assertEquals(0, view.page.ofType(ToWebview.Notice.class).size(), "the banner says it; no error notice on top");

			int mark = view.mark();
			view.prompt("hello?");
			ToWebview.Notice notice = view.page.await(ToWebview.Notice.class, mark, n -> true, "the notice");
			assertEquals("No active session. Sign in or restart the agent, then try again.", notice.notice().text());
			assertEquals(NoticeView.Severity.WARNING, notice.notice().severity());
			h.scheduler.flush();
			assertEquals(0, view.page.ofType(ToWebview.Chunk.class, mark).size(), "nothing is echoed");
			assertEquals(1, h.launches.size());

			// The seam of `/login` and `/restart` (reference D18, D25): a prompt without a session reaches the interceptors.
			List<String> seen = new CopyOnWriteArrayList<>();
			h.controller.hooks().addPromptInterceptor(request -> {
				seen.add(request.text() + " for " + request.sessionId());
				h.signal.changed();
				return CompletableFuture.completedFuture(request.text().equals("/login"));
			});
			view.prompt(" /login ");
			h.await(() -> seen.size() == 1, "the interceptor to be asked");
			h.scheduler.flush();
			assertEquals(List.of("/login for null"), seen);
			assertEquals(1, view.page.ofType(ToWebview.Notice.class, mark).size(), "what an interceptor took needs no notice");
		}
	}

	@Test
	void aNewSessionIsShapedBeforeAPageShowsIt() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			// The seam of the remembered options (reference D24).
			List<String> opened = new CopyOnWriteArrayList<>();
			Client[] page = new Client[1];
			h.controller.hooks().setNewSessions(new ChatHooks.NewSessions() {
				@Override
				public JsonNode meta() {
					return Harness.json("{\"kiro\":{\"modeId\":\"plan\"}}");
				}

				@Override
				public CompletableFuture<Void> opened(io.github.liaoalia.kiroacp.core.acp.api.KiroAgent agent,
						io.github.liaoalia.kiroacp.core.acp.api.SessionState session) {
					opened.add(session.sessionId() + " in mode " + session.modes().path("currentModeId").asText() + ", shown: "
							+ page[0].sessionId());
					return agent.setMode(session.sessionId(), "plan");
				}
			});
			page[0] = h.attach("view", AttachOptions.ownGroup("view"));
			Client view = page[0];
			view.send(new FromWebview.Ready());
			ToWebview.State first = view.page.await(ToWebview.State.class, 0, s -> s.state().sessionId() != null, "the session");
			h.awaitSettled(view);
			assertEquals(List.of(view.sessionId() + " in mode vibe, shown: null"), opened);
			assertEquals("plan", first.state().modes().currentModeId(), "the page first sees the session as it was shaped");
		}
	}

	@Test
	void aPromptRejectedForAnExpiredTokenEndsTheTurnAndOffersTheSignIn() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			Client view = h.openView("view");
			int mark = view.mark();
			assertEquals("error", view.turn("/scenario auth-expired").stopReason());

			ToWebview.Connection signIn = view.page.await(ToWebview.Connection.class, mark,
					c -> c.connection().status() == ConnectionStatus.AUTH_REQUIRED, "the sign-in state");
			assertEquals(List.of("kiro-cli-login"), signIn.connection().authMethods().stream().map(m -> m.id()).toList(),
					"the terminal login is offered although the agent advertised no method");
			List<String> types = view.page.types(mark);
			assertTrue(types.indexOf("connection") < types.lastIndexOf("turn"), "the banner is up before the turn ends: " + types);
			assertEquals(0, view.page.ofType(ToWebview.Notice.class, mark).size(), "the banner already shows the reason");
			assertTrue(h.logger.has("prompt rejected, sign-in required"), h.logger.lines.toString());
		}
	}

	@Test
	void aRequestThatNobodyCanSeeAsksForAPageOnItsSession() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			h.get(h.controller.start());
			String sessionId = h.ask(h.controller::focusedSessionId);
			assertEquals(ChatController.DEFAULT_GROUP, h.ask(() -> h.controller.sessionGroup(sessionId)));

			h.controller.prompt("/scenario permission-reject", null, null);
			h.await(() -> h.actions.calls.contains("revealSession " + sessionId), "the IDE to be asked for a page");

			// The IDE opens a view on that very session; it moves into the view's group.
			Client view = h.attach("view", AttachOptions.ownGroup("view"));
			view.handle.setSession(sessionId);
			view.send(new FromWebview.Ready());
			ToWebview.State state = view.page.await(ToWebview.State.class, 0, s -> !s.state().permissions().isEmpty(), "the card");
			assertEquals(sessionId, state.state().sessionId());
			assertTrue(state.state().turnActive());
			assertEquals("view", h.ask(() -> h.controller.sessionGroup(sessionId)));

			int mark = view.mark();
			view.send(new FromWebview.PermissionResponse(state.state().permissions().get(0).requestId(), "reject"));
			assertEquals("end_turn", view.awaitTurnEnd(mark).stopReason());
			assertEquals(1, h.actions.calls.stream().filter(call -> call.startsWith("revealSession")).count());
		}
	}

	private static List<String> ids(JsonNode state) {
		return state.path("sessions").findValuesAsText("id");
	}
}
