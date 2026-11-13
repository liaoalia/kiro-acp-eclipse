package io.github.liaoalia.kiroacp.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.liaoalia.kiroacp.core.host.Settings.AutoOpenDiffs;
import io.github.liaoalia.kiroacp.core.model.FromWebview;
import io.github.liaoalia.kiroacp.core.model.PermissionView;
import io.github.liaoalia.kiroacp.core.model.ToWebview;
import io.github.liaoalia.kiroacp.core.model.ToolKind;
import io.github.liaoalia.kiroacp.core.session.Harness.Client;
import io.github.liaoalia.kiroacp.core.session.diff.DiffReview;
import io.github.liaoalia.kiroacp.core.session.diff.FakeDiffEditors;
import io.github.liaoalia.kiroacp.core.session.diff.FakeDiffEditors.Editor;

/**
 * Diff review against the fake agent (reference e2e F2, {@code f2-diff.e2e.ts}):
 * the proposal opens in an editor, Accept and Reject there or on the chat
 * card answer the permission, and what the user made of the proposal is what
 * lands in the file. The editors are records ({@link FakeDiffEditors}); the
 * compare editor itself needs a workbench.
 */
class ChatDiffReviewTest {

	private static final String EDITED = "hello from kiro\nedited by user\n";

	@TempDir
	Path dir;

	/** A controller with diff review installed. */
	private final class Review implements AutoCloseable {
		final Harness h = Harness.launch(dir);
		final FakeDiffEditors editors = new FakeDiffEditors(h.scheduler::isControllerThread, h.signal::changed);
		final DiffReview review = DiffReview.install(h.controller, h.host, editors);

		Review(AutoOpenDiffs autoOpen) {
			h.settings.autoOpenDiffs = autoOpen;
		}

		Editor awaitEditor() throws Exception {
			h.await(() -> editors.count() == 1, "the proposal's editor; " + editors.events);
			return editors.only();
		}

		void awaitNoEditor() throws Exception {
			h.await(() -> editors.count() == 0, "the editors to close; " + editors.events);
		}

		/** Runs what the user does in an editor, on the controller thread. */
		void user(Runnable action) throws Exception {
			h.ask(() -> {
				action.run();
				return null;
			});
		}

		@Override
		public void close() throws Exception {
			h.close();
			assertEquals(List.of(), List.copyOf(editors.problems), "the editors are only touched on the controller thread");
		}
	}

	@Test
	void acceptingAnEditedProposalWritesTheUsersTextAndClosesTheEditor() throws Exception {
		Files.write(dir.resolve("README.md"), "# Tour\n".getBytes(StandardCharsets.UTF_8));
		try (Review r = new Review(AutoOpenDiffs.WHEN_PERMISSION_REQUIRED)) {
			Client view = r.h.openView("view");
			int mark = view.mark();
			view.prompt("/scenario tour");
			PermissionView permission = awaitPermission(view, mark);
			assertTrue(permission.hasDiff());
			assertEquals(ToolKind.EDIT, permission.kind(), "permission tool kind");
			assertEquals("call-edit", permission.toolCallId(), "permission tool call id");
			assertEquals("Create hello.txt", permission.title(), "permission title");
			assertEquals(4, permission.options().size(), "four permission options");
			assertEquals("fs_write", permission.consent().capability(), "Kiro consent context is surfaced");

			Editor editor = r.awaitEditor();
			assertEquals("hello.txt (Kiro proposal)", editor.proposal().title());
			assertEquals(dir.resolve("hello.txt").toString(), Path.of(editor.proposal().path()).toString());
			assertEquals("", editor.proposal().original(), "a file that does not exist starts empty");
			assertEquals("hello from kiro\n", editor.proposal().proposed(), "proposed text before editing");
			assertTrue(editor.pending(), "Accept and Reject are offered");

			// Edit the proposal before accepting.
			editor.type(EDITED);
			assertEquals(EDITED, r.h.ask(() -> r.review.currentProposedText(view.sessionId(), "call-edit", 0)),
					"the review sees the edit");

			r.user(editor::accept);

			assertEquals("end_turn", view.awaitTurnEnd(mark).stopReason());
			JsonNode state = view.snapshot();
			assertEquals("completed", state.at("/toolCalls/call-edit/status").asText(), "edit tool call status");
			assertTrue(state.at("/toolCalls/call-edit/failureReason").isMissingNode(), "edit tool call failure reason");
			assertEquals(EDITED, read("hello.txt"), "hello.txt on disk");
			r.awaitNoEditor();
			assertEquals(List.of(), r.review.openToolCallIds(view.sessionId()), "no diffs remain registered");
			assertEquals(List.of(), r.review.pendingSubstitutions(view.sessionId(), "call-edit"), "the substitution was used");
			assertEquals(List.of(new ToWebview.PermissionResolved(permission.requestId())),
					view.page.ofType(ToWebview.PermissionResolved.class, mark), "permissionResolved was posted");
			assertEquals(1, r.editors.opens(), "one editor, opened once");
			assertTrue(r.h.logger.has("diff: substituted user edits for"), r.h.logger.lines.toString());
		}
	}

	@Test
	void rejectingAProposalFailsTheToolCallWithReasonDeniedAndWritesNothing() throws Exception {
		try (Review r = new Review(AutoOpenDiffs.WHEN_PERMISSION_REQUIRED)) {
			Client view = r.h.openView("view");
			int mark = view.mark();
			view.prompt("/scenario permission-reject");
			PermissionView permission = awaitPermission(view, mark);
			assertEquals("Overwrite config.json", permission.title(), "permission title");
			Editor editor = r.awaitEditor();
			assertEquals("{}", editor.proposal().original(), "original side shows oldText");
			assertEquals("{ \"debug\": true }\n", editor.proposal().proposed());
			assertEquals("config.json (Kiro proposal)", editor.proposal().title());

			r.user(editor::reject);

			assertEquals("end_turn", view.awaitTurnEnd(mark).stopReason());
			JsonNode state = view.snapshot();
			assertEquals("failed", state.at("/toolCalls/call-edit/status").asText(), "edit tool call status");
			assertEquals("denied", state.at("/toolCalls/call-edit/failureReason").asText(), "edit tool call failure reason");
			assertFalse(Files.exists(dir.resolve("config.json")), "config.json was not written");
			r.awaitNoEditor();
			assertEquals(0, r.h.controller.permissions().size());
			assertEquals(List.of(), r.review.openToolCallIds(view.sessionId()));
		}
	}

	@Test
	void autoOpenDiffsNeverLeavesTheProposalClosedWhileThePermissionIsPending() throws Exception {
		try (Review r = new Review(AutoOpenDiffs.NEVER)) {
			Client view = r.h.openView("view");
			int mark = view.mark();
			view.prompt("/scenario permission-reject");
			PermissionView permission = awaitPermission(view, mark);
			r.h.scheduler.flush();
			assertEquals(List.of(), List.copyOf(r.editors.events), "no proposal editor opened for the pending permission");
			assertEquals(1, r.h.controller.permissions().size(), "the permission is still pending");

			view.send(new FromWebview.PermissionResponse(permission.requestId(), "reject"));
			assertEquals("end_turn", view.awaitTurnEnd(mark).stopReason());
			assertEquals("denied", view.snapshot().at("/toolCalls/call-edit/failureReason").asText(), "the edit was denied");
			assertFalse(Files.exists(dir.resolve("config.json")), "config.json was not written");
			assertEquals(List.of(), List.copyOf(r.editors.events), "still no proposal editor after the answer");
		}
	}

	@Test
	void allowingOnTheChatCardWritesWhatTheEditorHoldsAndClosesIt() throws Exception {
		try (Review r = new Review(AutoOpenDiffs.WHEN_PERMISSION_REQUIRED)) {
			Client view = r.h.openView("view");
			int mark = view.mark();
			view.prompt("/scenario permission-reject");
			PermissionView permission = awaitPermission(view, mark);
			Editor editor = r.awaitEditor();
			// Not saved: the card's button takes the text as the editor has it.
			editor.type("{ \"debug\": false }\n");

			view.send(new FromWebview.PermissionResponse(permission.requestId(), "accept"));

			assertEquals("end_turn", view.awaitTurnEnd(mark).stopReason());
			assertEquals("completed", view.snapshot().at("/toolCalls/call-edit/status").asText());
			assertEquals("{ \"debug\": false }\n", read("config.json"));
			r.awaitNoEditor();
		}
	}

	@Test
	void savedEditsOutliveTheirEditorAndUnsavedOnesDoNot() throws Exception {
		try (Review r = new Review(AutoOpenDiffs.WHEN_PERMISSION_REQUIRED)) {
			Client view = r.h.openView("view");

			// Saved, then the editor is closed: the card's Allow writes the saved text.
			int mark1 = view.mark();
			view.prompt("/scenario permission-reject");
			PermissionView first = awaitPermission(view, mark1);
			Editor saved = r.awaitEditor();
			saved.type("saved\n");
			r.user(saved::save);
			saved.closeByUser();
			assertEquals("saved\n", r.h.ask(() -> r.review.currentProposedText(view.sessionId(), "call-edit", 0)));
			view.send(new FromWebview.PermissionResponse(first.requestId(), "accept"));
			assertEquals("end_turn", view.awaitTurnEnd(mark1).stopReason());
			assertEquals("saved\n", read("config.json"));

			// Typed, then closed without saving: the agent's text is written.
			Files.delete(dir.resolve("config.json"));
			int mark2 = view.mark();
			view.prompt("/scenario permission-reject");
			PermissionView second = awaitPermission(view, mark2);
			Editor unsaved = r.awaitEditor();
			assertNotEquals(saved.id(), unsaved.id(), "the same tool call id in a later turn gets an editor of its own");
			assertEquals("{ \"debug\": true }\n", unsaved.proposal().proposed(), "and starts from the agent's text again");
			unsaved.type("never saved\n");
			unsaved.closeByUser();
			view.send(new FromWebview.PermissionResponse(second.requestId(), "accept"));
			assertEquals("end_turn", view.awaitTurnEnd(mark2).stopReason());
			assertEquals("{ \"debug\": true }\n", read("config.json"));
			assertEquals(List.of(), r.review.openToolCallIds(view.sessionId()));
		}
	}

	@Test
	void theLinkInTheChatOpensTheProposalAndShowsTheOneThatIsOpenAgain() throws Exception {
		try (Review r = new Review(AutoOpenDiffs.NEVER)) {
			Client view = r.h.openView("view");
			int mark = view.mark();
			view.prompt("/scenario permission-reject");
			PermissionView permission = awaitPermission(view, mark);
			r.h.scheduler.flush();
			assertEquals(0, r.editors.count());

			view.send(new FromWebview.OpenDiff("call-edit", 0));
			Editor editor = r.awaitEditor();
			assertTrue(editor.pending(), "the request waits, so the editor can answer it");
			assertEquals("{}", editor.proposal().original());

			// Again, with edits in the editor: the same editor, and the edits stay.
			editor.type("mine\n");
			view.send(new FromWebview.OpenDiff("call-edit", 0));
			r.h.await(() -> r.editors.opens() == 2, "the editor to be shown again");
			assertEquals(1, r.editors.count());
			assertEquals(editor.id(), r.editors.only().id());
			assertEquals("mine\n", r.editors.only().text());

			// What is not a diff of a known tool call opens nothing.
			view.send(new FromWebview.OpenDiff("call-edit", 7));
			view.send(new FromWebview.OpenDiff("no-such-call", 0));
			r.h.scheduler.flush();
			assertEquals(2, r.editors.opens());

			view.send(new FromWebview.PermissionResponse(permission.requestId(), "reject"));
			assertEquals("end_turn", view.awaitTurnEnd(mark).stopReason());
			r.awaitNoEditor();
			assertFalse(Files.exists(dir.resolve("config.json")));
		}
	}

	@Test
	void alwaysOpensTheProposalOnceAndStoppingTheTurnClosesIt() throws Exception {
		try (Review r = new Review(AutoOpenDiffs.ALWAYS)) {
			Client view = r.h.openView("view");
			int mark = view.mark();
			view.prompt("/scenario permission-reject");
			PermissionView permission = awaitPermission(view, mark);
			Editor editor = r.awaitEditor();
			// Opened for the tool call or for the request, whichever came first; the request makes it answerable.
			r.h.await(editor::pending, "Accept and Reject to be offered");
			r.h.scheduler.flush();
			assertEquals(1, r.editors.opens(), "the tool call and its request open one editor, once");

			view.send(new FromWebview.Cancel());
			assertEquals("cancelled", view.awaitTurnEnd(mark).stopReason());
			assertEquals(List.of(new ToWebview.PermissionResolved(permission.requestId())),
					view.page.ofType(ToWebview.PermissionResolved.class, mark));
			r.awaitNoEditor();
			assertEquals(List.of(), r.review.openToolCallIds(view.sessionId()));
			assertFalse(Files.exists(dir.resolve("config.json")));
		}
	}

	@Test
	void theSameToolCallIdInTwoSessionsIsTwoProposals() throws Exception {
		try (Review r = new Review(AutoOpenDiffs.WHEN_PERMISSION_REQUIRED)) {
			Client a = r.h.openView("a");
			Client b = r.h.openView("b");
			assertNotEquals(a.sessionId(), b.sessionId());
			int markA = a.mark();
			a.prompt("/scenario permission-reject");
			awaitPermission(a, markA);
			Editor ofA = r.awaitEditor();
			int markB = b.mark();
			b.prompt("/scenario permission-reject");
			PermissionView askedB = awaitPermission(b, markB);
			r.h.await(() -> r.editors.count() == 2, "an editor for each session");
			Editor ofB = r.editors.editors().get(1);
			assertEquals(List.of("call-edit"), r.review.openToolCallIds(a.sessionId()));
			assertEquals(List.of("call-edit"), r.review.openToolCallIds(b.sessionId()));

			// Rejecting in A's editor answers A's request only.
			r.user(ofA::reject);
			assertEquals("end_turn", a.awaitTurnEnd(markA).stopReason());
			assertEquals("denied", a.snapshot().at("/toolCalls/call-edit/failureReason").asText());
			assertEquals(List.of(ofB.id()), r.editors.editors().stream().map(Editor::id).toList());
			assertEquals(askedB.requestId(), r.h.controller.permissions().pending().get(0).requestId(), "B still waits");

			ofB.type("{ \"b\": 1 }\n");
			r.user(ofB::accept);
			assertEquals("end_turn", b.awaitTurnEnd(markB).stopReason());
			assertEquals("{ \"b\": 1 }\n", read("config.json"));
			r.awaitNoEditor();
		}
	}

	@Test
	void closingTheSessionClosesItsProposals() throws Exception {
		try (Review r = new Review(AutoOpenDiffs.WHEN_PERMISSION_REQUIRED)) {
			Client view = r.h.openView("view");
			String waiting = view.sessionId();
			int mark = view.mark();
			view.prompt("/scenario permission-reject");
			awaitPermission(view, mark);
			r.awaitEditor();

			view.send(new FromWebview.CloseSession(waiting));

			r.awaitNoEditor();
			r.h.await(() -> r.h.logger.has("session " + waiting + " closed"), "the close to be logged");
			assertEquals(List.of(), r.review.openToolCallIds(waiting));
			assertEquals(0, r.h.controller.permissions().size());
		}
	}

	@Test
	void anEditorThatCannotOpenLeavesTheCardToAnswer() throws Exception {
		try (Review r = new Review(AutoOpenDiffs.WHEN_PERMISSION_REQUIRED)) {
			r.editors.failToOpen = true;
			Client view = r.h.openView("view");
			int mark = view.mark();
			view.prompt("/scenario permission-reject");
			PermissionView permission = awaitPermission(view, mark);
			r.h.await(() -> r.h.logger.has("diff: opening the editor of"), "the failure to be logged");

			view.send(new FromWebview.PermissionResponse(permission.requestId(), "accept"));
			assertEquals("end_turn", view.awaitTurnEnd(mark).stopReason());
			assertEquals("{ \"debug\": true }\n", read("config.json"));
		}
	}

	private String read(String name) throws Exception {
		return new String(Files.readAllBytes(dir.resolve(name)), StandardCharsets.UTF_8);
	}

	private static PermissionView awaitPermission(Client view, int mark) throws InterruptedException {
		return view.page.await(ToWebview.Permission.class, mark, p -> true, "a permission card").request();
	}
}
