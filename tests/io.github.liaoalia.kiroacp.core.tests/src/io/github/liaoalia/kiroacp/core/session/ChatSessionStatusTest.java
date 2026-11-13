package io.github.liaoalia.kiroacp.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.liaoalia.kiroacp.core.model.FromWebview;
import io.github.liaoalia.kiroacp.core.model.SessionStatus;
import io.github.liaoalia.kiroacp.core.model.SessionSummary;
import io.github.liaoalia.kiroacp.core.model.ToWebview;
import io.github.liaoalia.kiroacp.core.session.Harness.Client;

/**
 * What {@link ChatSessionsTest} leaves of the reference's e2e F10: sessions
 * made without a page (F10.1), the status of one session while another is
 * busy (F10.3), and the view opened for a waiting session, whose end spares
 * the group the session came from (F10.10).
 */
class ChatSessionStatusTest {

	@TempDir
	Path dir;

	@Test
	void sessionsMadeWithoutAPageAreAllKeptAndTheNewestIsFocusedAndListedFirst() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			h.get(h.controller.start());
			String first = h.ask(h.controller::focusedSessionId);
			String a = h.get(h.controller.newSession(null));
			String b = h.get(h.controller.newSession(null));
			assertEquals(3, List.of(first, a, b).stream().distinct().count(), "distinct sessions");

			assertEquals(b, h.ask(h.controller::focusedSessionId), "the newest session is focused");
			List<SessionSummary> sessions = h.ask(() -> h.controller.snapshot(b).sessions());
			assertEquals(List.of(b, a, first), sessions.stream().map(SessionSummary::id).toList(), "most recent first");
			assertEquals(List.of(SessionStatus.IDLE, SessionStatus.IDLE, SessionStatus.IDLE),
					sessions.stream().map(SessionSummary::status).toList());
			assertEquals(List.of("New session", "New session", "New session"), sessions.stream().map(SessionSummary::title).toList());
			for (String id : List.of(first, a, b)) {
				assertEquals(ChatController.DEFAULT_GROUP, h.ask(() -> h.controller.sessionGroup(id)));
			}
		}
	}

	@Test
	void aSessionIsRunningOrWaitsForAnAnswerWhileTheOtherStaysIdle() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			// A page without a group lists every session.
			Client all = h.open("all", AttachOptions.shared());
			String a = all.sessionId();
			String b = h.get(h.controller.newSession(null));
			assertNotEquals(a, b);
			h.get(h.controller.selectSession(a, all.handle));
			h.awaitSettled(all);

			int mark = all.mark();
			CompletableFuture<Void> turn = h.controller.prompt("/scenario cancel", a, all.handle);
			awaitStatuses(all, mark, Map.of(a, SessionStatus.RUNNING, b, SessionStatus.IDLE), "A to be running while B is idle");
			h.get(h.controller.cancel(a));
			h.get(turn);
			awaitStatuses(all, mark, Map.of(a, SessionStatus.IDLE, b, SessionStatus.IDLE), "A to be idle after the cancel");

			int waiting = all.mark();
			CompletableFuture<Void> second = h.controller.prompt("/scenario permission-reject", a, all.handle);
			ToWebview.Permission asked = all.page.await(ToWebview.Permission.class, waiting, p -> true, "the question");
			awaitStatuses(all, waiting, Map.of(a, SessionStatus.NEEDS_INPUT, b, SessionStatus.IDLE), "A to need an answer while B is idle");
			all.send(new FromWebview.PermissionResponse(asked.request().requestId(), "reject"));
			h.get(second);
			awaitStatuses(all, waiting, Map.of(a, SessionStatus.IDLE, b, SessionStatus.IDLE), "A to be idle again");
			assertFalse(all.snapshot().path("turnActive").asBoolean());
		}
	}

	@Test
	void closingTheViewOpenedForAWaitingSessionSparesTheGroupTheSessionCameFrom() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			h.get(h.controller.start());
			String waiting = h.ask(h.controller::focusedSessionId);
			String sibling = h.get(h.controller.newSession(null));
			assertEquals(ChatController.DEFAULT_GROUP, h.ask(() -> h.controller.sessionGroup(waiting)));
			assertEquals(ChatController.DEFAULT_GROUP, h.ask(() -> h.controller.sessionGroup(sibling)));

			// No page at all: the request has nobody to ask, so the IDE is asked for a view on that session.
			CompletableFuture<Void> turn = h.controller.prompt("/scenario permission-reject", waiting, null);
			h.await(() -> h.actions.calls.contains("revealSession " + waiting), "the IDE to be asked for a page");
			Client view = h.attach("tab", AttachOptions.ownGroup("tab"));
			view.handle.setSession(waiting);
			view.send(new FromWebview.Ready());
			ToWebview.State state = view.page.await(ToWebview.State.class, 0, s -> !s.state().permissions().isEmpty(), "the card");

			assertEquals("tab", h.ask(() -> h.controller.sessionGroup(waiting)), "the waiting session moved into the view's group");
			assertEquals(ChatController.DEFAULT_GROUP, h.ask(() -> h.controller.sessionGroup(sibling)), "its sibling did not");
			assertEquals(List.of(waiting), state.state().sessions().stream().map(SessionSummary::id).toList(),
					"the view lists the waiting session only");

			view.send(new FromWebview.PermissionResponse(state.state().permissions().get(0).requestId(), "reject"));
			h.get(turn);
			view.handle.close();
			h.await(() -> h.logger.has("session " + waiting + " closed"), "the view's session to close with the view");
			h.scheduler.flush();

			assertEquals(List.of(sibling), h.ask(h.controller::sessionIds), "the sibling of the old group survives the view");
			assertEquals(ChatController.DEFAULT_GROUP, h.ask(() -> h.controller.sessionGroup(sibling)));
			assertTrue(h.ask(() -> h.controller.focusedSessionId()) != null, "a session remains focused");
		}
	}

	/** Waits for a {@code sessions} message since the mark that says exactly this of the sessions it names. */
	private static void awaitStatuses(Client page, int mark, Map<String, SessionStatus> expected, String label) throws Exception {
		page.page.await(ToWebview.Sessions.class, mark, list -> expected.equals(list.sessions().stream()
				.filter(s -> expected.containsKey(s.id())).collect(Collectors.toMap(SessionSummary::id, SessionSummary::status))), label);
	}
}
