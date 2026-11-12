package io.github.liaoalia.kiroacp.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.liaoalia.kiroacp.core.model.FromWebview;
import io.github.liaoalia.kiroacp.core.model.NoticeView;
import io.github.liaoalia.kiroacp.core.model.PermissionScope;
import io.github.liaoalia.kiroacp.core.model.PermissionView;
import io.github.liaoalia.kiroacp.core.model.SessionStatus;
import io.github.liaoalia.kiroacp.core.model.ToWebview;
import io.github.liaoalia.kiroacp.core.model.ToolKind;
import io.github.liaoalia.kiroacp.core.session.Harness.Client;

/**
 * Permission cards and the answers of the page (reference e2e F7, with
 * F10.3 and F10.8): the request reaches the page of its session, the answer
 * reaches the agent, and what is remembered answers the next one.
 */
class ChatPermissionTest {

	@TempDir
	Path dir;

	@Test
	void rejectingOnTheCardFailsTheToolCallAndWritesNothing() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			Client view = h.openView("view");
			int mark = view.mark();
			view.prompt("/scenario permission-reject");
			PermissionView request = awaitPermission(view, mark);
			assertEquals(ToolKind.EDIT, request.kind());
			assertEquals(List.of(PermissionScope.KIND), request.policies().stream().map(PermissionView.Policy::scope).toList(),
					"without consent meta only the kind policy is offered");
			// The session waits for the user, which the session list says (after its debounce).
			h.scheduler.flush();
			assertEquals(SessionStatus.NEEDS_INPUT, view.page.last(ToWebview.Sessions.class).sessions().get(0).status());

			// An answer the agent did not offer is refused; the question stays.
			view.send(new FromWebview.PermissionResponse(request.requestId(), "no-such-option"));
			ToWebview.Notice refused = view.page.await(ToWebview.Notice.class, mark, n -> true, "the refusal");
			assertEquals(NoticeView.Severity.WARNING, refused.notice().severity());
			assertTrue(refused.notice().text().contains("no-such-option"), refused.notice().text());
			assertEquals(1, h.controller.permissions().size());

			view.send(new FromWebview.PermissionResponse(request.requestId(), "reject"));
			assertEquals("end_turn", view.awaitTurnEnd(mark).stopReason());
			assertEquals(List.of(new ToWebview.PermissionResolved(request.requestId())),
					view.page.ofType(ToWebview.PermissionResolved.class, mark));
			JsonNode state = view.snapshot();
			assertEquals("failed", state.at("/toolCalls/call-edit/status").asText());
			assertEquals("denied", state.at("/toolCalls/call-edit/failureReason").asText());
			assertEquals(0, state.path("permissions").size());
			assertFalse(Files.exists(dir.resolve("config.json")), "config.json was never written");
			assertEquals(SessionStatus.IDLE, view.page.last(ToWebview.Sessions.class).sessions().get(0).status());
			assertEquals(view.page.model().path("toolCalls"), state.path("toolCalls"), "the card the page built is the one it reloads");
		}
	}

	@Test
	void aRememberedRejectAnswersTheNextRequestOfThatKindUntilANewSession() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			Client view = h.openView("view");
			String first = view.sessionId();

			// Turn 1: the request appears; "reject all edits this session".
			int mark1 = view.mark();
			view.prompt("/scenario permission-reject");
			PermissionView request = awaitPermission(view, mark1);
			view.send(new FromWebview.PermissionAlways(request.requestId(), FromWebview.Decision.REJECT, null));
			assertEquals("end_turn", view.awaitTurnEnd(mark1).stopReason());
			assertEquals("denied", view.snapshot().at("/toolCalls/call-edit/failureReason").asText());
			assertEquals(1, view.page.ofType(ToWebview.Permission.class, mark1).size());

			// Turn 2: same kind, same session: answered without a card.
			int mark2 = view.mark();
			assertEquals("end_turn", view.turn("/scenario permission-reject").stopReason());
			assertEquals(0, view.page.ofType(ToWebview.Permission.class, mark2).size(), "no card in turn 2");
			assertEquals("denied", view.snapshot().at("/toolCalls/call-edit/failureReason").asText());
			assertFalse(Files.exists(dir.resolve("config.json")));

			// A new session knows nothing of that rule.
			int mark3 = view.mark();
			view.send(new FromWebview.NewSession());
			view.page.await(ToWebview.Session.class, mark3, s -> s.sessionId() != null, "the new session");
			h.awaitSettled(view);
			assertNotEquals(first, view.sessionId());
			int mark4 = view.mark();
			view.prompt("/scenario permission-reject");
			PermissionView again = awaitPermission(view, mark4);
			view.send(new FromWebview.PermissionResponse(again.requestId(), "accept"));
			assertEquals("end_turn", view.awaitTurnEnd(mark4).stopReason());
			assertEquals("completed", view.snapshot().at("/toolCalls/call-edit/status").asText());
			assertTrue(Files.exists(dir.resolve("config.json")), "allowed this time");
		}
	}

	@Test
	void aRequestWithoutAKindTakesItFromTheToolCallAndCanBeRememberedPerResource() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			Client view = h.openView("view");

			// Turn 1: the request carries no kind, as Kiro sends it; the tool call before it said "edit".
			int mark1 = view.mark();
			view.prompt("/scenario permission-nokind");
			PermissionView request = awaitPermission(view, mark1);
			assertEquals("call-nokind", request.toolCallId());
			assertEquals(ToolKind.EDIT, request.kind(), "kind resolved from the tool_call update, not 'other'");
			assertEquals("fs_write", request.consent().capability());
			List<PermissionView.Policy> policies = request.policies();
			assertEquals(List.of(PermissionScope.KIND, PermissionScope.CAPABILITY, PermissionScope.RESOURCE),
					policies.stream().map(PermissionView.Policy::scope).toList());
			assertEquals("Allow all edits this session", policies.get(0).label());
			assertEquals("Allow all fs_write this session", policies.get(1).label());
			assertEquals("Reject all fs_write this session", policies.get(1).rejectLabel());
			assertEquals("Allow all changes to nokind.txt this session", policies.get(2).label());

			view.send(new FromWebview.PermissionAlways(request.requestId(), FromWebview.Decision.ALLOW, PermissionScope.RESOURCE));
			assertEquals("end_turn", view.awaitTurnEnd(mark1).stopReason());
			assertEquals("completed", view.snapshot().at("/toolCalls/call-nokind/status").asText());
			assertTrue(Files.exists(dir.resolve("nokind.txt")));

			// Turn 2: the same path: allowed without a card.
			Files.delete(dir.resolve("nokind.txt"));
			int mark2 = view.mark();
			assertEquals("end_turn", view.turn("/scenario permission-nokind").stopReason());
			assertEquals(0, view.page.ofType(ToWebview.Permission.class, mark2).size());
			assertTrue(Files.exists(dir.resolve("nokind.txt")), "written again without asking");

			// Turn 3: another path of the same kind still asks.
			int mark3 = view.mark();
			view.prompt("/scenario permission-reject");
			PermissionView other = awaitPermission(view, mark3);
			assertEquals("call-edit", other.toolCallId());
			view.send(new FromWebview.PermissionResponse(other.requestId(), "reject"));
			assertEquals("end_turn", view.awaitTurnEnd(mark3).stopReason());
			assertFalse(Files.exists(dir.resolve("config.json")));

			// A scope the request has nothing for is refused with a notice; the request stays.
			int mark4 = view.mark();
			view.prompt("/scenario permission-reject");
			PermissionView plain = awaitPermission(view, mark4);
			view.send(new FromWebview.PermissionAlways(plain.requestId(), FromWebview.Decision.ALLOW, PermissionScope.RESOURCE));
			ToWebview.Notice refused = view.page.await(ToWebview.Notice.class, mark4, n -> true, "the refusal");
			assertEquals(NoticeView.Severity.WARNING, refused.notice().severity());
			assertEquals(1, h.controller.permissions().size());
			view.send(new FromWebview.Cancel());
			assertEquals("cancelled", view.awaitTurnEnd(mark4).stopReason());
			assertEquals(List.of(new ToWebview.PermissionResolved(plain.requestId())),
					view.page.ofType(ToWebview.PermissionResolved.class, mark4), "Stop answers what is pending");
			assertEquals("cancelled", view.snapshot().at("/toolCalls/call-edit/failureReason").asText());
		}
	}

	@Test
	void aKindLessRequestResolvesItsKindInItsOwnSessionAndItsCardGoesToItsOwnPage() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			Client a = h.openView("a");
			Client b = h.openView("b");
			assertNotEquals(a.sessionId(), b.sessionId());

			// Session A: `call-nokind` is an edit; remember "allow all edits" there.
			int markA = a.mark();
			a.prompt("/scenario permission-nokind");
			PermissionView edit = awaitPermission(a, markA);
			assertEquals(ToolKind.EDIT, edit.kind());
			a.send(new FromWebview.PermissionAlways(edit.requestId(), FromWebview.Decision.ALLOW, PermissionScope.KIND));
			assertEquals("end_turn", a.awaitTurnEnd(markA).stopReason());

			// Session B: the same tool call id is a command, and the request again carries no kind.
			int markB = b.mark();
			int quietA = a.mark();
			b.prompt("/scenario permission-nokind-exec");
			PermissionView exec = awaitPermission(b, markB);
			assertEquals("call-nokind", exec.toolCallId());
			assertEquals(ToolKind.EXECUTE, exec.kind(), "the kind comes from B's own tool call, not from A's edit");
			assertEquals("shell", exec.consent().capability());
			assertEquals("Allow all commands this session", exec.policies().get(0).label());
			assertEquals("edit", a.snapshot().at("/toolCalls/call-nokind/kind").asText());
			assertEquals("execute", b.snapshot().at("/toolCalls/call-nokind/kind").asText());

			b.send(new FromWebview.PermissionResponse(exec.requestId(), "reject"));
			assertEquals("end_turn", b.awaitTurnEnd(markB).stopReason());
			assertEquals("denied", b.snapshot().at("/toolCalls/call-nokind/failureReason").asText());
			assertEquals(List.of(), a.page.types(quietA), "nothing of B's turn reached the page of A");
		}
	}

	@Test
	void closingASessionThatWaitsForAnAnswerCancelsTheRequestAndTheViewGetsAnotherSession() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			Client view = h.openView("view");
			String waiting = view.sessionId();
			int mark = view.mark();
			view.prompt("/scenario permission-reject");
			awaitPermission(view, mark);

			view.send(new FromWebview.CloseSession(waiting));
			ToWebview.Session replaced = view.page.await(ToWebview.Session.class, mark, s -> true, "the replacement");
			assertNotEquals(waiting, replaced.sessionId());
			h.awaitSettled(view);
			assertEquals(0, h.controller.permissions().size(), "no permission request is pending any more");
			assertEquals(List.of(view.sessionId()), h.ask(h.controller::sessionIds));
			assertEquals(view.sessionId(), h.ask(h.controller::focusedSessionId));
			JsonNode state = view.snapshot();
			assertEquals(0, state.path("items").size());
			assertEquals(0, state.path("permissions").size());
			assertFalse(state.get("turnActive").asBoolean());
			// The closed session's turn settles without the page hearing of it.
			h.await(() -> h.logger.has("session " + waiting + " closed"), "the close to be logged");
			assertEquals("end_turn", view.turn("/scenario chat").stopReason());
		}
	}

	private static PermissionView awaitPermission(Client view, int mark) throws InterruptedException {
		return view.page.await(ToWebview.Permission.class, mark, p -> true, "a permission card").request();
	}
}
