package io.github.liaoalia.kiroacp.ui.diff;

import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;

import io.github.liaoalia.kiroacp.core.host.Logger;
import io.github.liaoalia.kiroacp.core.session.diff.DiffEditors;

/**
 * Proposed changes in compare editors (the reference's native diff editor,
 * D7): the thin Eclipse end of {@code DiffReview}, which decides everything.
 *
 * <p>UI thread only. The editors open in the editor area and are not
 * activated, so the chat keeps the focus.
 */
public final class CompareDiffEditors implements DiffEditors {

	/** The id the compare plug-in registers its editor with. */
	static final String COMPARE_EDITOR = "org.eclipse.compare.CompareEditor";

	private final Logger logger;
	/** The input of each proposal that was opened and not closed by {@link #close}; its editor may be gone. */
	private final Map<String, ProposalCompareInput> inputs = new HashMap<>();

	public CompareDiffEditors(Logger logger) {
		this.logger = logger != null ? logger : Logger.NONE;
	}

	@Override
	public void open(Proposal proposal, Review review) {
		ProposalCompareInput shown = inputs.get(proposal.id());
		IEditorPart part = shown != null ? editorOf(shown) : null;
		if (part != null && shown.shows(proposal)) {
			shown.setPending(proposal.pending());
			part.getSite().getPage().bringToTop(part);
			return;
		}
		IWorkbenchPage page = part != null ? part.getSite().getPage() : activePage();
		if (page == null) {
			throw new IllegalStateException("there is no workbench window to show " + proposal.title() + " in");
		}
		ProposalCompareInput input = new ProposalCompareInput(proposal, review);
		try {
			// Here, not by the compare plug-in when the editor opens: that would run it in a
			// progress dialog, with an event loop of its own in the middle of the caller's work.
			input.run(new NullProgressMonitor());
			if (part != null) {
				// What it shows is out of date, and whatever was edited in it has been asked for.
				page.closeEditor(part, false);
			}
			inputs.put(proposal.id(), input);
			page.openEditor(input, COMPARE_EDITOR, false);
		} catch (PartInitException | InvocationTargetException e) {
			inputs.remove(proposal.id(), input);
			throw new IllegalStateException(e.getMessage(), e);
		} catch (InterruptedException e) {
			inputs.remove(proposal.id(), input);
			Thread.currentThread().interrupt();
		}
	}

	@Override
	public String proposedText(String id) {
		ProposalCompareInput input = inputs.get(id);
		if (input == null || editorOf(input) == null) {
			return null;
		}
		try {
			return input.currentText();
		} catch (CoreException e) {
			logger.warn("Could not take the edits of " + input.proposal().title() + ": " + e.getMessage());
			return null;
		}
	}

	@Override
	public void setPending(String id, boolean pending) {
		ProposalCompareInput input = inputs.get(id);
		if (input != null) {
			input.setPending(pending);
		}
	}

	@Override
	public void close(String id) {
		ProposalCompareInput input = inputs.remove(id);
		IEditorPart part = input != null ? editorOf(input) : null;
		if (part != null) {
			part.getSite().getPage().closeEditor(part, false);
		}
	}

	/** The editor that shows the input, in whichever window; {@code null} once the user closed it. */
	private static IEditorPart editorOf(ProposalCompareInput input) {
		if (!PlatformUI.isWorkbenchRunning()) {
			return null;
		}
		for (IWorkbenchWindow window : PlatformUI.getWorkbench().getWorkbenchWindows()) {
			for (IWorkbenchPage page : window.getPages()) {
				IEditorPart part = page.findEditor(input);
				if (part != null) {
					return part;
				}
			}
		}
		return null;
	}

	private static IWorkbenchPage activePage() {
		if (!PlatformUI.isWorkbenchRunning()) {
			return null;
		}
		IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
		if (window == null && PlatformUI.getWorkbench().getWorkbenchWindowCount() > 0) {
			// No window of ours is the active one (another application has the focus).
			window = PlatformUI.getWorkbench().getWorkbenchWindows()[0];
		}
		return window == null ? null : window.getActivePage();
	}
}
