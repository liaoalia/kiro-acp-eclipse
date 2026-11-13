package io.github.liaoalia.kiroacp.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.liaoalia.kiroacp.core.model.FromWebview;
import io.github.liaoalia.kiroacp.core.model.PermissionView;
import io.github.liaoalia.kiroacp.core.model.ToWebview;
import io.github.liaoalia.kiroacp.core.model.ToolKind;
import io.github.liaoalia.kiroacp.core.pure.plan.PlanMarkdown;
import io.github.liaoalia.kiroacp.core.session.Harness.Client;
import io.github.liaoalia.kiroacp.core.session.plan.FakePlanDocuments;
import io.github.liaoalia.kiroacp.core.session.plan.PlanDocument;

/**
 * The plan document and the approval of a plan (reference D11; e2e F3),
 * against the fake agent. The harness pins the clock at 22:13:20 UTC.
 */
class ChatPlanTest {

	private static final String TOUR_PLAN = "**1 of 3 done · 1 in progress**\n\n- [x] **(high)** Inspect the workspace\n"
			+ "- [ ] ⏳ Run a command\n- [ ] (low) Create hello.txt\n";

	@TempDir
	Path dir;

	/** The harness with the feature installed on documents that exist only as records. */
	private final class Plans implements AutoCloseable {
		final Harness h = Harness.launch(dir);
		final FakePlanDocuments documents = new FakePlanDocuments(h.scheduler::isControllerThread, h.signal::changed);
		final PlanDocument plan;

		Plans(boolean autoOpen) {
			plan = PlanDocument.install(h.controller, documents, ZoneOffset.UTC, autoOpen);
		}

		String text(String sessionId) throws Exception {
			return h.ask(() -> plan.text(sessionId));
		}

		/** Runs what the user does in a document, on the controller thread. */
		void user(Runnable action) throws Exception {
			h.ask(() -> {
				action.run();
				return null;
			});
		}

		/** The tour, with its one permission request (the edit) accepted. */
		void tour(Client view) throws Exception {
			int mark = view.mark();
			view.prompt("/scenario tour");
			PermissionView permission = view.page.await(ToWebview.Permission.class, mark, m -> true, "the permission request").request();
			view.send(new FromWebview.PermissionResponse(permission.requestId(), "accept"));
			assertEquals("end_turn", view.awaitTurnEnd(mark).stopReason());
		}

		@Override
		public void close() throws Exception {
			user(plan::close);
			h.close();
			assertEquals(List.of(), List.copyOf(documents.problems), "the documents are only touched on the controller thread");
		}
	}

	private static String header(String sessionId) {
		return "# Kiro plan\n_Session " + sessionId + " · updated 22:13:20_\n\n";
	}

	private static List<String> notices(Client view, int mark) {
		return view.page.ofType(ToWebview.Notice.class, mark).stream().map(notice -> notice.notice().text()).toList();
	}

	private static List<String> agentTexts(Client view) throws Exception {
		List<String> out = new ArrayList<>();
		for (JsonNode item : view.snapshot().get("items")) {
			if ("message".equals(item.path("type").asText()) && "agent".equals(item.path("message").path("role").asText())) {
				StringBuilder text = new StringBuilder();
				item.at("/message/segments").forEach(segment -> text.append(segment.path("text").asText("")));
				out.add(text.toString());
			}
		}
		return out;
	}

	@Test
	void theToursPlanUpdatesRenderInTheSessionsDocument() throws Exception {
		Files.write(dir.resolve("README.md"), "# Tour\n".getBytes(StandardCharsets.UTF_8));
		try (Plans p = new Plans(true)) {
			Client view = p.h.openView("view");
			String id = view.sessionId();
			assertEquals("# Kiro plan\n_Session " + id + "_\n\n" + PlanMarkdown.EMPTY_TEXT + "\n", p.text(id), "before any plan");
			assertEquals(List.of(), p.documents.openSessions(), "nothing opens without a plan");

			int mark = view.mark();
			p.tour(view);

			assertTrue(view.page.ofType(ToWebview.Plan.class, mark).size() >= 2, "two plan updates");
			assertEquals(3, view.snapshot().get("plan").size(), "plan entries in the UI state");
			// The document opened by itself with the first plan and followed the second.
			assertEquals(List.of(id), p.documents.openSessions());
			assertEquals("show " + id, p.documents.calls.get(0));
			assertEquals(1, p.documents.calls.stream().filter(call -> call.startsWith("show")).count(), "opened once");
			String text = p.documents.text(id);
			assertEquals(header(id) + TOUR_PLAN, text);
			assertEquals(text, p.text(id));
			assertFalse(p.documents.offersApproval(id), "nothing to approve in the tour");

			// Closed by the user, it stays closed: it opens by itself once per session.
			p.documents.userCloses(id);
			p.tour(view);
			assertEquals(List.of(), p.documents.openSessions());

			// The page's "open plan" brings it back, up to date.
			view.send(new FromWebview.OpenPlan());
			p.h.await(() -> p.documents.text(id) != null, "the plan document to be shown");
			assertEquals(header(id) + TOUR_PLAN, p.documents.text(id));

			// Approve and Reject without a request say so.
			int asked = view.mark();
			p.user(() -> p.plan.approve(id));
			assertEquals(List.of("Nothing is awaiting approval: Kiro has not asked to switch out of planning yet."), notices(view, asked));

			// The document goes with its session.
			p.h.get(p.h.controller.closeSession(id));
			assertEquals(List.of(), p.documents.openSessions());
			assertEquals("close " + id, p.documents.calls.get(p.documents.calls.size() - 1));
		}
	}

	@Test
	void approvingASwitchModeRequestFromThePlanDocumentResumesTheAgent() throws Exception {
		try (Plans p = new Plans(true)) {
			Client view = p.h.openView("view");
			String id = view.sessionId();
			int mark = view.mark();
			view.prompt("/scenario switch-mode");
			PermissionView permission = view.page.await(ToWebview.Permission.class, mark, m -> true, "a switch_mode permission").request();
			assertEquals(ToolKind.SWITCH_MODE, permission.kind());
			assertEquals("Approve plan and start implementation", permission.title(), "permission title");
			assertEquals("call-switch", permission.toolCallId(), "tool call id");

			p.h.await(() -> p.documents.offersApproval(id), "Approve and Reject in the plan document");
			String text = p.documents.text(id);
			assertEquals(header(id) + PlanMarkdown.APPROVAL_NOTE + "\n\n**0 of 2 done**\n\n- [ ] **(high)** Add a greeting function\n"
					+ "- [ ] Write a unit test\n", text, "the approval note and the proposed entries");
			assertTrue(text.contains("waiting for approval"));
			assertTrue(p.h.ask(() -> p.plan.approvalPending(id)));
			assertEquals("pending", view.snapshot().at("/toolCalls/call-switch/status").asText(), "the switch_mode card is pending");

			p.user(() -> p.documents.buttons(id).approve());

			assertEquals("end_turn", view.awaitTurnEnd(mark).stopReason());
			assertEquals(0, p.h.controller.permissions().size(), "the request is resolved");
			JsonNode state = view.snapshot();
			assertEquals("completed", state.at("/toolCalls/call-switch/status").asText(), "switch_mode tool call status");
			assertEquals("vibe", state.at("/modes/currentModeId").asText(), "current mode after approval");
			assertTrue(agentTexts(view).contains("Starting implementation."), "the agent continued after approval: " + agentTexts(view));
			p.h.await(() -> !p.documents.offersApproval(id) && !p.documents.text(id).contains("waiting for approval"),
					"the approval note to disappear");
			assertFalse(p.h.ask(() -> p.plan.approvalPending(id)));
		}
	}

	@Test
	void rejectingKeepsTheAgentPlanning() throws Exception {
		try (Plans p = new Plans(true)) {
			Client view = p.h.openView("view");
			String id = view.sessionId();
			int mark = view.mark();
			view.prompt("/scenario switch-mode");
			p.h.await(() -> p.documents.offersApproval(id), "Approve and Reject in the plan document");

			p.user(() -> p.documents.buttons(id).reject());

			assertEquals("end_turn", view.awaitTurnEnd(mark).stopReason());
			assertTrue(agentTexts(view).contains("Plan rejected; staying in plan mode."), agentTexts(view).toString());
			p.h.await(() -> !p.documents.offersApproval(id), "the buttons to go");
		}
	}

	@Test
	void eachSessionHasItsOwnDocumentAndAnApprovalWithoutASessionAnswersWhicheverWaits() throws Exception {
		Files.write(dir.resolve("README.md"), "# Tour\n".getBytes(StandardCharsets.UTF_8));
		try (Plans p = new Plans(true)) {
			Client a = p.h.openView("a");
			Client b = p.h.openView("b");
			String waiting = a.sessionId();
			String touring = b.sessionId();
			int mark = a.mark();
			a.prompt("/scenario switch-mode");
			p.h.await(() -> p.documents.offersApproval(waiting), "the approval in the first session's document");

			// The other view's session, now the focused one, gets a document of its own.
			p.tour(b);
			assertEquals(List.of(waiting, touring), p.documents.openSessions());
			assertEquals(header(touring) + TOUR_PLAN, p.documents.text(touring));
			assertTrue(p.documents.text(waiting).contains("Add a greeting function"), "the first document still shows its own plan");
			assertFalse(p.documents.offersApproval(touring));

			// The command without an editor: whichever session waits, as the reference's.
			p.user(() -> p.plan.approve(null));
			assertEquals("end_turn", a.awaitTurnEnd(mark).stopReason());
			// The first session is not the focused one: its document is brought up to date all the same.
			p.h.await(() -> !p.documents.offersApproval(waiting) && !p.documents.text(waiting).contains("waiting for approval"),
					"the note to leave the document of the session that is not focused");

			p.h.get(p.h.controller.closeSession(waiting));
			assertEquals(List.of(touring), p.documents.openSessions(), "only the closed session's document went");
		}
	}

	@Test
	void withoutOpeningByItselfTheDocumentIsShownWhenAskedForAndAFailureToShowItIsSaid() throws Exception {
		try (Plans p = new Plans(false)) {
			Client view = p.h.openView("view");
			String id = view.sessionId();
			int mark = view.mark();
			view.prompt("/scenario switch-mode");
			PermissionView permission = view.page.await(ToWebview.Permission.class, mark, m -> true, "a switch_mode permission").request();
			p.h.scheduler.flush();
			assertEquals(List.of(), List.copyOf(p.documents.calls), "nothing opened by itself");
			assertTrue(p.text(id).contains("waiting for approval"), "the text is there for whoever asks");

			p.documents.refusal = "there is no workbench window to show the plan in";
			view.send(new FromWebview.OpenPlan());
			view.page.await(ToWebview.Notice.class, mark, n -> true, "a notice");
			assertEquals(List.of("Could not open the plan: there is no workbench window to show the plan in"), notices(view, mark));
			assertNull(p.documents.text(id));

			p.documents.refusal = null;
			assertTrue(p.h.ask(() -> p.plan.open(null)), "the focused session's plan");
			assertNotNull(p.documents.text(id));
			assertTrue(p.documents.offersApproval(id));

			// Answered in the chat instead: the document follows.
			view.send(new FromWebview.PermissionResponse(permission.requestId(), "reject"));
			assertEquals("end_turn", view.awaitTurnEnd(mark).stopReason());
			p.h.await(() -> !p.documents.offersApproval(id), "the buttons to go");
		}
	}
}
