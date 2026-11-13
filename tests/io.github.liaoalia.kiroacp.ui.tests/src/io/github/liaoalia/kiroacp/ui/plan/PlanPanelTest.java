package io.github.liaoalia.kiroacp.ui.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.liaoalia.kiroacp.core.session.plan.PlanDocuments.Approval;
import io.github.liaoalia.kiroacp.core.session.plan.PlanDocuments.Content;

/**
 * What a plan editor shows, in a shell that is never opened: its widgets
 * exist, nothing is shown, and what a user would do is done to them. The
 * editor part around it and the workbench's editor area need a workbench,
 * which these tests do not have.
 */
class PlanPanelTest {

	private static final String PLAN = "# Kiro plan\n\n**0 of 1 done**\n\n- [ ] Add a greeting function\n";

	private final List<String> pressed = new ArrayList<>();
	private final Approval approval = new Approval() {
		@Override
		public void approve() {
			pressed.add("approve");
		}

		@Override
		public void reject() {
			pressed.add("reject");
		}
	};

	private Display display;
	private boolean ownDisplay;
	private Shell shell;

	@BeforeEach
	void createShell() {
		display = Display.getCurrent();
		if (display == null) {
			display = new Display();
			ownDisplay = true;
		}
		shell = new Shell(display);
		shell.setLayout(new FillLayout());
		shell.setSize(480, 320);
	}

	@AfterEach
	void close() {
		if (!shell.isDisposed()) {
			shell.dispose();
		}
		if (ownDisplay && !display.isDisposed()) {
			display.dispose();
		}
	}

	@Test
	void thePlanIsShownAsReadOnlyTextAndFollowsItsUpdates() {
		PlanPanel panel = new PlanPanel(shell, new Content(PLAN, false), approval);
		shell.layout();

		assertFalse(shell.isVisible(), "the test shows nothing");
		assertEquals(PLAN, panel.text());
		assertFalse(panel.editable(), "read-only");
		assertFalse(panel.offersApproval(), "no buttons while nothing waits for an answer");

		String revised = PLAN.replace("[ ]", "[x]").replace("0 of 1", "1 of 1");
		panel.show(new Content(revised, false));
		assertEquals(revised, panel.text());
		assertEquals(List.of(), pressed);
	}

	@Test
	void approveAndRejectAreOfferedOnlyWhileTheAgentWaitsAndAnswerIt() {
		PlanPanel panel = new PlanPanel(shell, new Content(PLAN, false), approval);
		shell.layout();

		panel.show(new Content("> waiting\n" + PLAN, true));
		assertTrue(panel.offersApproval());
		assertEquals("> waiting\n" + PLAN, panel.text());

		panel.pressApprove();
		panel.pressReject();
		assertEquals(List.of("approve", "reject"), pressed);

		panel.show(new Content(PLAN, false));
		assertFalse(panel.offersApproval(), "the buttons go with the request");

		// A panel may start with a request waiting.
		PlanPanel waiting = new PlanPanel(shell, new Content(PLAN, true), approval);
		assertTrue(waiting.offersApproval());

		// Its editor was closed: an update that comes late changes nothing and does not fail.
		waiting.control().dispose();
		waiting.show(new Content("late", false));
	}

	@Test
	void anEditorInputHandsTheUpdatesToTheEditorThatShowsIt() {
		PlanEditorInput input = new PlanEditorInput("sess-1", new Content(PLAN, false), approval);
		assertEquals("Kiro Plan", input.getName());
		assertEquals("Kiro plan of session sess-1", input.getToolTipText());
		assertFalse(input.exists());
		assertEquals(null, input.getPersistable(), "a plan editor is not restored with the workbench");

		// No editor yet: the input keeps what an editor will start with.
		input.setContent(new Content("first", true));
		PlanPanel panel = new PlanPanel(shell, input.content(), input.approval());
		input.shownBy(panel::show);
		assertEquals("first", panel.text());
		assertTrue(panel.offersApproval());

		input.setContent(new Content("second", false));
		assertEquals("second", panel.text());
		assertFalse(panel.offersApproval());

		input.shownBy(null);
		input.setContent(new Content("third", false));
		assertEquals("second", panel.text(), "a closed editor is left alone");
		assertEquals("third", input.content().markdown());
	}

	@Test
	void withoutAWorkbenchThereIsNowhereToShowAPlanAndNothingElseFails() {
		assertFalse(PlatformUI.isWorkbenchRunning(), "these tests are written for a runtime without a workbench");
		EditorPlanDocuments documents = new EditorPlanDocuments();

		IllegalStateException refused = assertThrows(IllegalStateException.class,
				() -> documents.show("sess-1", new Content(PLAN, false), approval));
		assertEquals("there is no workbench window to show the plan in", refused.getMessage());
		assertFalse(documents.isOpen("sess-1"));
		documents.update("sess-1", new Content(PLAN, true));
		documents.close("sess-1");
	}
}
