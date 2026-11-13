package io.github.liaoalia.kiroacp.ui.diff;

import org.eclipse.compare.CompareConfiguration;
import org.eclipse.compare.CompareEditorInput;
import org.eclipse.compare.structuremergeviewer.DiffNode;
import org.eclipse.compare.structuremergeviewer.Differencer;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;

import io.github.liaoalia.kiroacp.core.session.diff.DiffEditors.Proposal;
import io.github.liaoalia.kiroacp.core.session.diff.DiffEditors.Review;

/**
 * A proposed change as the input of a compare editor: the original text on
 * the left, read-only, the proposal on the right, editable, and a bar with
 * Accept and Reject above them.
 *
 * <p>Nothing is decided here. Saving reports the proposed side's text, the
 * buttons report that they were pressed, each from a turn of the event loop
 * of its own, because what follows closes this editor.
 */
final class ProposalCompareInput extends CompareEditorInput {

	private final Proposal proposal;
	private final Review review;
	private final ProposalSide original;
	private final ProposalSide proposed;
	private boolean pending;
	private ToolItem accept;
	private ToolItem reject;

	ProposalCompareInput(Proposal proposal, Review review) {
		super(configuration(proposal));
		this.proposal = proposal;
		this.review = review;
		this.pending = proposal.pending();
		this.original = new ProposalSide(proposal.path(), proposal.original(), null);
		this.proposed = new ProposalSide(proposal.path(), proposal.proposed(), review::saved);
		setTitle(proposal.title());
	}

	private static CompareConfiguration configuration(Proposal proposal) {
		String name = ProposalSide.fileName(proposal.path());
		CompareConfiguration configuration = new CompareConfiguration();
		configuration.setLeftEditable(false);
		configuration.setRightEditable(true);
		configuration.setLeftLabel(name + " (current)");
		configuration.setRightLabel(name + " (proposed by Kiro, editable)");
		return configuration;
	}

	/** Nothing to compute, so this may be run on the UI thread before the editor opens. */
	@Override
	protected Object prepareInput(IProgressMonitor monitor) {
		return new DiffNode(null, Differencer.CHANGE, null, original, proposed);
	}

	@Override
	public String getToolTipText() {
		return proposal.path() + " (Kiro proposal)";
	}

	@Override
	public Control createContents(Composite parent) {
		Composite area = new Composite(parent, SWT.NONE);
		GridLayout layout = new GridLayout(1, false);
		layout.marginWidth = 0;
		layout.marginHeight = 0;
		layout.verticalSpacing = 0;
		area.setLayout(layout);
		ToolBar bar = new ToolBar(area, SWT.FLAT | SWT.RIGHT | SWT.HORIZONTAL);
		bar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		accept = button(bar, "Accept", "Allow the change as the right side shows it", review::accept);
		reject = button(bar, "Reject", "Reject the proposed change", review::reject);
		showPending();
		Control compare = super.createContents(area);
		compare.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
		return area;
	}

	private static ToolItem button(ToolBar bar, String text, String toolTip, Runnable pressed) {
		ToolItem item = new ToolItem(bar, SWT.PUSH);
		item.setText(text);
		item.setToolTipText(toolTip);
		item.addListener(SWT.Selection, event -> {
			// Not from inside the button's own event: the answer disposes the button.
			if (!item.isDisposed()) {
				item.getDisplay().asyncExec(pressed);
			}
		});
		return item;
	}

	Proposal proposal() {
		return proposal;
	}

	/** Whether this editor shows exactly that: then it only needs to be brought up. */
	boolean shows(Proposal other) {
		return proposal.original().equals(other.original()) && proposed.text().equals(other.proposed())
				&& proposal.path().equals(other.path()) && proposal.title().equals(other.title());
	}

	/** Accept and Reject are offered only while a permission request waits for them. */
	void setPending(boolean pending) {
		this.pending = pending;
		showPending();
	}

	private void showPending() {
		for (ToolItem item : new ToolItem[] { accept, reject }) {
			if (item != null && !item.isDisposed()) {
				item.setEnabled(pending);
			}
		}
	}

	/**
	 * The proposed side as the editor has it now: unsaved edits are saved into
	 * the proposal first, which reports them.
	 */
	String currentText() throws CoreException {
		saveChanges(new NullProgressMonitor());
		return proposed.text();
	}
}
