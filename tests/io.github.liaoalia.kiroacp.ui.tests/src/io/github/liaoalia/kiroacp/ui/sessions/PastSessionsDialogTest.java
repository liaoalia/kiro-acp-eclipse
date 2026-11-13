package io.github.liaoalia.kiroacp.ui.sessions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.liaoalia.kiroacp.core.session.history.PastSessions.Entry;

/**
 * The dialog of the past sessions, created and never opened: its widgets
 * exist, nothing is shown, and what a user would do is done to them.
 */
class PastSessionsDialogTest {

	private static final List<Entry> ENTRIES = List.of(
			new Entry("past-1", "Investigate flaky CI", "4d ago", "C:\\work\\shop", "Investigate flaky CI", "C:\\work\\shop",
					"2026-11-09T10:00:00.000Z"),
			new Entry("past-2", "Add retry to fetch", "5d ago", "C:\\work\\shop", "Add retry to fetch", "C:\\work\\shop",
					"2026-11-08T15:30:00.000Z"),
			new Entry("sess-9", "sess-9", "", "C:\\work\\docs", null, "C:\\work\\docs", null));

	private Display display;
	private boolean ownDisplay;
	private Shell shell;
	private PastSessionsDialog dialog;

	@BeforeEach
	void createDialog() {
		display = Display.getCurrent();
		if (display == null) {
			display = new Display();
			ownDisplay = true;
		}
		shell = new Shell(display);
		dialog = new PastSessionsDialog(shell, ENTRIES);
		dialog.create();
	}

	@AfterEach
	void close() {
		dialog.close();
		if (!shell.isDisposed()) {
			shell.dispose();
		}
		if (ownDisplay && !display.isDisposed()) {
			display.dispose();
		}
	}

	@Test
	void theSessionsAreListedAsGivenWithTheirTimeAndFolderAndTheFirstIsSelected() {
		assertEquals("Kiro: past sessions", dialog.getShell().getText());
		assertFalse(dialog.getShell().isVisible(), "the test shows nothing");
		assertEquals(List.of(List.of("Investigate flaky CI", "4d ago", "C:\\work\\shop"),
				List.of("Add retry to fetch", "5d ago", "C:\\work\\shop"), List.of("sess-9", "", "C:\\work\\docs")), dialog.rows());
		assertTrue(dialog.canRestore());

		// Enter, or the Restore button, without choosing: the most recent one.
		dialog.restore();

		assertEquals("past-1", dialog.picked());
		assertEquals(Window.OK, dialog.getReturnCode());
		assertNull(dialog.getShell(), "the dialog is closed");
	}

	@Test
	void theChosenSessionIsTheOneRestored() {
		dialog.select(1);

		dialog.restore();

		assertEquals("past-2", dialog.picked());
	}

	@Test
	void typingNarrowsTheListAndTheFirstOfWhatIsLeftIsSelected() {
		dialog.type("retry");
		assertEquals(List.of(List.of("Add retry to fetch", "5d ago", "C:\\work\\shop")), dialog.rows());
		assertTrue(dialog.canRestore());

		dialog.type("DOCS");
		assertEquals(List.of(List.of("sess-9", "", "C:\\work\\docs")), dialog.rows(), "the folder is searched as well, whatever the case");

		dialog.type("shop 4d");
		assertEquals(1, dialog.rows().size(), "every word, in any column");

		dialog.restore();
		assertEquals("past-1", dialog.picked());
	}

	@Test
	void nothingCanBeRestoredWhileNothingMatches() {
		dialog.type("no such session");

		assertEquals(List.of(), dialog.rows());
		assertFalse(dialog.canRestore());
		dialog.restore();
		assertNull(dialog.picked());
		assertTrue(dialog.getShell() != null && !dialog.getShell().isDisposed(), "the dialog stays");

		dialog.type("");
		assertEquals(3, dialog.rows().size());
		assertTrue(dialog.canRestore());
	}

	@Test
	void cancellingChoosesNothing() {
		dialog.select(2);

		dialog.cancel();

		assertNull(dialog.picked());
		assertEquals(Window.CANCEL, dialog.getReturnCode());
	}
}
