package io.github.liaoalia.kiroacp.ui.plan;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;

import io.github.liaoalia.kiroacp.core.session.plan.PlanDocuments;

/**
 * The plans of the sessions in editors: the thin Eclipse end of
 * {@code PlanDocument}, which decides everything.
 *
 * <p>UI thread only. The editors open in the editor area and are not
 * activated, so the chat keeps the focus.
 */
public final class EditorPlanDocuments implements PlanDocuments {

	/** The input of each session's document that was shown and not closed by {@link #close}; its editor may be gone. */
	private final Map<String, PlanEditorInput> inputs = new HashMap<>();

	@Override
	public void show(String sessionId, Content content, Approval approval) {
		PlanEditorInput shown = inputs.get(sessionId);
		IEditorPart part = shown != null ? editorOf(shown) : null;
		if (part != null) {
			shown.setContent(content);
			part.getSite().getPage().bringToTop(part);
			return;
		}
		IWorkbenchPage page = activePage();
		if (page == null) {
			throw new IllegalStateException("there is no workbench window to show the plan in");
		}
		PlanEditorInput input = new PlanEditorInput(sessionId, content, approval);
		try {
			inputs.put(sessionId, input);
			page.openEditor(input, PlanEditor.ID, false);
		} catch (PartInitException e) {
			inputs.remove(sessionId, input);
			throw new IllegalStateException(e.getMessage(), e);
		}
	}

	@Override
	public void update(String sessionId, Content content) {
		PlanEditorInput input = inputs.get(sessionId);
		if (input != null) {
			input.setContent(content);
		}
	}

	@Override
	public boolean isOpen(String sessionId) {
		PlanEditorInput input = inputs.get(sessionId);
		return input != null && editorOf(input) != null;
	}

	@Override
	public void close(String sessionId) {
		PlanEditorInput input = inputs.remove(sessionId);
		IEditorPart part = input != null ? editorOf(input) : null;
		if (part != null) {
			part.getSite().getPage().closeEditor(part, false);
		}
	}

	/** The editor that shows the input, in whichever window; {@code null} once the user closed it. */
	private static IEditorPart editorOf(PlanEditorInput input) {
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
