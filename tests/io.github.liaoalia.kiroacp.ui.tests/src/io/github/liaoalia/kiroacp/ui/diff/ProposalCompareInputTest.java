package io.github.liaoalia.kiroacp.ui.diff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.compare.ITypedElement;
import org.eclipse.compare.structuremergeviewer.ICompareInput;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.liaoalia.kiroacp.core.session.diff.DiffEditors.Proposal;
import io.github.liaoalia.kiroacp.core.session.diff.DiffEditors.Review;

/**
 * The input of a proposal's compare editor, built in a shell that is never
 * opened. There is no workbench here, so no compare editor: what the editor
 * would do with the input (create its contents, save it) is done by hand.
 */
class ProposalCompareInputTest {

	private static final class Heard implements Review {
		final List<String> calls = new ArrayList<>();

		@Override
		public void saved(String text) {
			calls.add("saved " + text);
		}

		@Override
		public void accept() {
			calls.add("accept");
		}

		@Override
		public void reject() {
			calls.add("reject");
		}
	}

	private Display display;
	private boolean ownDisplay;
	private Shell shell;
	private final Heard heard = new Heard();

	@BeforeEach
	void createShell() {
		display = Display.getCurrent();
		if (display == null) {
			display = new Display();
			ownDisplay = true;
		}
		shell = new Shell(display);
	}

	@AfterEach
	void dispose() {
		if (shell != null && !shell.isDisposed()) {
			shell.dispose();
		}
		if (ownDisplay && !display.isDisposed()) {
			display.dispose();
		}
	}

	private static Proposal proposal(boolean pending) {
		return new Proposal("diff-1", "C:\\ws\\src\\hello.txt", "hello.txt (Kiro proposal)", "old\n", "new \u00e4\n", pending);
	}

	@Test
	void theSidesAreTheOriginalReadOnlyAndTheProposalEditable() throws Exception {
		ProposalCompareInput input = new ProposalCompareInput(proposal(true), heard);
		input.run(new NullProgressMonitor());

		ICompareInput compared = (ICompareInput) input.getCompareResult();
		assertNotNull(compared, "there is always something to show, also when both sides are equal");
		ProposalSide left = (ProposalSide) compared.getLeft();
		ProposalSide right = (ProposalSide) compared.getRight();
		assertEquals("old\n", new String(left.getContents().readAllBytes(), StandardCharsets.UTF_8));
		assertEquals("new \u00e4\n", new String(right.getContents().readAllBytes(), StandardCharsets.UTF_8));
		assertEquals("UTF-8", right.getCharset());
		assertFalse(left.isEditable());
		assertTrue(right.isEditable());
		assertFalse(input.getCompareConfiguration().isLeftEditable());
		assertTrue(input.getCompareConfiguration().isRightEditable());
		assertEquals("hello.txt", right.getName());
		assertEquals("txt", right.getType(), "the extension chooses the viewer");
		assertEquals("hello.txt (Kiro proposal)", input.getTitle());
		assertEquals("hello.txt (current)", input.getCompareConfiguration().getLeftLabel(compared));
		assertEquals("hello.txt (proposed by Kiro, editable)", input.getCompareConfiguration().getRightLabel(compared));

		// What the compare editor does when it saves the right side.
		right.setContent("edited \u00e4\n".getBytes(StandardCharsets.UTF_8));
		assertEquals(List.of("saved edited \u00e4\n"), heard.calls);
		assertEquals("edited \u00e4\n", right.text());
		// The left side takes nothing.
		left.setContent("no".getBytes(StandardCharsets.UTF_8));
		assertEquals("old\n", left.text());
		assertEquals(1, heard.calls.size());
	}

	@Test
	void aFileWithoutAnExtensionIsText() {
		assertEquals(ITypedElement.TEXT_TYPE, ProposalSide.typeOf("Makefile"));
		assertEquals(ITypedElement.TEXT_TYPE, ProposalSide.typeOf(".gitignore"));
		assertEquals("java", ProposalSide.typeOf("A.java"));
		assertEquals("Makefile", ProposalSide.fileName("/ws/Makefile"));
		assertEquals("file", ProposalSide.fileName("/ws/"));
	}

	@Test
	void theBarAnswersAndWhatWasTypedIsSavedWhenAskedFor() throws Exception {
		ProposalCompareInput input = new ProposalCompareInput(proposal(true), heard);
		input.run(new NullProgressMonitor());
		Control contents = input.createContents(shell);
		shell.layout(true, true);

		ToolBar bar = find(contents, ToolBar.class).get(0);
		ToolItem accept = bar.getItem(0);
		ToolItem reject = bar.getItem(1);
		assertEquals("Accept", accept.getText());
		assertEquals("Reject", reject.getText());
		assertTrue(accept.isEnabled() && reject.isEnabled(), "a request waits for an answer");

		// The proposed side is the one that takes typing.
		List<StyledText> texts = find(contents, StyledText.class);
		StyledText proposed = texts.stream().filter(text -> "new \u00e4\n".equals(text.getText())).findFirst().orElseThrow();
		StyledText original = texts.stream().filter(text -> "old\n".equals(text.getText())).findFirst().orElseThrow();
		assertTrue(proposed.getEditable());
		assertFalse(original.getEditable());
		proposed.setSelection(proposed.getCharCount());
		proposed.insert("typed\n");
		assertTrue(input.isSaveNeeded(), "typing makes the editor dirty");
		assertEquals(List.of(), heard.calls, "nothing is reported before it is saved");

		assertEquals("new \u00e4\ntyped\n", input.currentText());
		assertEquals(List.of("saved new \u00e4\ntyped\n"), heard.calls);
		assertFalse(input.isSaveNeeded());
		assertTrue(input.shows(new Proposal("diff-1", "C:\\ws\\src\\hello.txt", "hello.txt (Kiro proposal)", "old\n",
				"new \u00e4\ntyped\n", true)));
		assertFalse(input.shows(proposal(true)), "the agent's text is no longer what it shows");

		// A press is reported in a turn of the event loop of its own.
		accept.notifyListeners(SWT.Selection, new Event());
		assertEquals(1, heard.calls.size());
		drain();
		assertEquals("accept", heard.calls.get(1));
		reject.notifyListeners(SWT.Selection, new Event());
		drain();
		assertEquals("reject", heard.calls.get(2));

		input.setPending(false);
		assertFalse(accept.isEnabled() || reject.isEnabled(), "nothing to answer, nothing offered");
	}

	@Test
	void withoutARequestTheButtonsAreOff() throws Exception {
		ProposalCompareInput input = new ProposalCompareInput(proposal(false), heard);
		input.run(new NullProgressMonitor());
		Control contents = input.createContents(shell);
		ToolBar bar = find(contents, ToolBar.class).get(0);
		assertFalse(bar.getItem(0).isEnabled());
		assertFalse(bar.getItem(1).isEnabled());
		input.setPending(true);
		assertTrue(bar.getItem(0).isEnabled());
		assertTrue(bar.getItem(1).isEnabled());
	}

	private void drain() {
		while (display.readAndDispatch()) {
			// Until nothing is queued.
		}
	}

	private static <T extends Control> List<T> find(Control root, Class<T> type) {
		List<T> found = new ArrayList<>();
		collect(root, type, found);
		return found;
	}

	private static <T extends Control> void collect(Control control, Class<T> type, List<T> found) {
		if (type.isInstance(control)) {
			found.add(type.cast(control));
		}
		if (control instanceof Composite composite) {
			for (Control child : composite.getChildren()) {
				collect(child, type, found);
			}
		}
	}
}
