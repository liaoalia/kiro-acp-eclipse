package io.github.liaoalia.kiroacp.ui.plan;

import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;

import io.github.liaoalia.kiroacp.core.session.plan.PlanDocuments.Approval;
import io.github.liaoalia.kiroacp.core.session.plan.PlanDocuments.Content;

/**
 * What a plan editor shows: the plan's Markdown, read-only, under a bar with
 * Approve and Reject that is there only while the agent waits for an answer
 * (the reference puts those two in the editor's title bar, which an Eclipse
 * editor cannot add buttons to).
 *
 * <p>UI thread only.
 */
final class PlanPanel {

	static final String WAITING = "Kiro is waiting for this plan to be approved.";

	private final Composite root;
	private final Composite bar;
	private final Button approve;
	private final Button reject;
	private final StyledText text;

	PlanPanel(Composite parent, Content content, Approval approval) {
		root = new Composite(parent, SWT.NONE);
		GridLayout layout = new GridLayout(1, false);
		layout.marginWidth = 0;
		layout.marginHeight = 0;
		layout.verticalSpacing = 0;
		root.setLayout(layout);

		bar = new Composite(root, SWT.NONE);
		bar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		bar.setLayout(new GridLayout(3, false));
		Label note = new Label(bar, SWT.NONE);
		note.setText(WAITING);
		note.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		approve = new Button(bar, SWT.PUSH);
		approve.setText("Approve");
		approve.setToolTipText("Approve the plan: Kiro starts on it");
		approve.addListener(SWT.Selection, event -> approval.approve());
		reject = new Button(bar, SWT.PUSH);
		reject.setText("Reject");
		reject.setToolTipText("Reject the plan: Kiro stays in planning");
		reject.addListener(SWT.Selection, event -> approval.reject());

		text = new StyledText(root, SWT.MULTI | SWT.READ_ONLY | SWT.WRAP | SWT.V_SCROLL);
		text.setEditable(false);
		text.setFont(JFaceResources.getTextFont());
		text.setMargins(8, 6, 8, 6);
		text.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

		show(content);
	}

	/** Make the panel show {@code content}; where the user scrolled to stays in view. */
	void show(Content content) {
		if (root.isDisposed()) {
			return;
		}
		if (!content.markdown().equals(text.getText())) {
			int top = text.getTopIndex();
			text.setText(content.markdown());
			text.setTopIndex(Math.min(top, Math.max(0, text.getLineCount() - 1)));
		}
		GridData data = (GridData) bar.getLayoutData();
		if (bar.getVisible() != content.approvalPending() || data.exclude == content.approvalPending()) {
			bar.setVisible(content.approvalPending());
			data.exclude = !content.approvalPending();
			root.layout(true, true);
		}
	}

	Composite control() {
		return root;
	}

	void setFocus() {
		if (!text.isDisposed()) {
			text.setFocus();
		}
	}

	// --- what the user sees and does; tests ask and do the same ---

	String text() {
		return text.getText();
	}

	boolean editable() {
		return text.getEditable();
	}

	/** Whether Approve and Reject are there to be pressed. */
	boolean offersApproval() {
		return bar.getVisible() && !((GridData) bar.getLayoutData()).exclude;
	}

	void pressApprove() {
		approve.notifyListeners(SWT.Selection, new Event());
	}

	void pressReject() {
		reject.notifyListeners(SWT.Selection, new Event());
	}
}
