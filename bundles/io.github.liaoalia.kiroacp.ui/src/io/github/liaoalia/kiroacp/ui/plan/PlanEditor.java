package io.github.liaoalia.kiroacp.ui.plan;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorSite;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.part.EditorPart;

/**
 * The read-only editor a session's plan is shown in (the reference's
 * {@code kiro-plan:} document, D11). It shows the Markdown as text: Eclipse
 * has no Markdown editor of its own to count on.
 */
public final class PlanEditor extends EditorPart {

	/** The id the editor is registered with in plugin.xml. */
	public static final String ID = "io.github.liaoalia.kiroacp.planEditor";

	private PlanPanel panel;

	@Override
	public void init(IEditorSite site, IEditorInput input) throws PartInitException {
		if (!(input instanceof PlanEditorInput)) {
			throw new PartInitException("The Kiro plan editor only shows the plan of a chat session.");
		}
		setSite(site);
		setInput(input);
		setPartName(input.getName());
		setTitleToolTip(input.getToolTipText());
	}

	/** The session whose plan this editor shows. */
	public String sessionId() {
		return ((PlanEditorInput) getEditorInput()).sessionId();
	}

	@Override
	public void createPartControl(Composite parent) {
		PlanEditorInput input = (PlanEditorInput) getEditorInput();
		panel = new PlanPanel(parent, input.content(), input.approval());
		input.shownBy(panel::show);
	}

	@Override
	public void dispose() {
		if (getEditorInput() instanceof PlanEditorInput input) {
			input.shownBy(null);
		}
		super.dispose();
	}

	@Override
	public void setFocus() {
		if (panel != null) {
			panel.setFocus();
		}
	}

	@Override
	public void doSave(IProgressMonitor monitor) {
		// Read-only: there is never anything to save.
	}

	@Override
	public void doSaveAs() {
	}

	@Override
	public boolean isDirty() {
		return false;
	}

	@Override
	public boolean isSaveAsAllowed() {
		return false;
	}
}
