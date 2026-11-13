package io.github.liaoalia.kiroacp.ui.plan;

import java.util.function.Consumer;

import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IPersistableElement;

import io.github.liaoalia.kiroacp.core.session.plan.PlanDocuments.Approval;
import io.github.liaoalia.kiroacp.core.session.plan.PlanDocuments.Content;

/**
 * The plan of one session as an editor's input: nothing on disk, only the
 * text the chat keeps up to date. It is not persisted, so a plan editor is
 * not restored with the workbench (its session is gone by then).
 *
 * <p>UI thread only.
 */
final class PlanEditorInput implements IEditorInput {

	private final String sessionId;
	private final Approval approval;
	private Content content;
	/** The editor that shows the input, while there is one. */
	private Consumer<Content> shown;

	PlanEditorInput(String sessionId, Content content, Approval approval) {
		this.sessionId = sessionId;
		this.content = content;
		this.approval = approval;
	}

	String sessionId() {
		return sessionId;
	}

	Content content() {
		return content;
	}

	Approval approval() {
		return approval;
	}

	/** The text or the pending approval changed: the editor showing the input follows. */
	void setContent(Content next) {
		content = next;
		if (shown != null) {
			shown.accept(next);
		}
	}

	/** @param editor told of every change from now on; {@code null} when the editor is gone */
	void shownBy(Consumer<Content> editor) {
		shown = editor;
	}

	@Override
	public boolean exists() {
		return false;
	}

	@Override
	public ImageDescriptor getImageDescriptor() {
		return null;
	}

	@Override
	public String getName() {
		return "Kiro Plan";
	}

	@Override
	public IPersistableElement getPersistable() {
		return null;
	}

	@Override
	public String getToolTipText() {
		return "Kiro plan of session " + sessionId;
	}

	@Override
	public <T> T getAdapter(Class<T> adapter) {
		return null;
	}
}
