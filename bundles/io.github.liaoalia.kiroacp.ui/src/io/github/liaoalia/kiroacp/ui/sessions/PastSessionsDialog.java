package io.github.liaoalia.kiroacp.ui.sessions;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

import io.github.liaoalia.kiroacp.core.session.history.PastSessions.Entry;

/**
 * The agent's past sessions to choose one from: a box to type in, which
 * narrows the list by title, time and folder, and the list, most recent
 * first (the reference's QuickPick).
 */
final class PastSessionsDialog extends Dialog {

	static final String TITLE = "Kiro: past sessions";

	private final List<Entry> entries;
	private Text filter;
	private TableViewer viewer;
	private String picked;

	PastSessionsDialog(Shell parent, List<Entry> entries) {
		super(parent);
		this.entries = List.copyOf(entries);
	}

	/** The id of the session the user chose, or {@code null}. */
	String picked() {
		return picked;
	}

	@Override
	protected void configureShell(Shell shell) {
		super.configureShell(shell);
		shell.setText(TITLE);
	}

	@Override
	protected boolean isResizable() {
		return true;
	}

	@Override
	protected Control createDialogArea(Composite parent) {
		Composite area = (Composite) super.createDialogArea(parent);
		filter = new Text(area, SWT.SEARCH | SWT.ICON_CANCEL | SWT.BORDER);
		filter.setMessage("Select a session to restore");
		filter.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

		viewer = new TableViewer(area, SWT.SINGLE | SWT.FULL_SELECTION | SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL);
		viewer.getTable().setHeaderVisible(true);
		GridData tableData = new GridData(SWT.FILL, SWT.FILL, true, true);
		tableData.widthHint = convertWidthInCharsToPixels(100);
		tableData.heightHint = convertHeightInCharsToPixels(14);
		viewer.getTable().setLayoutData(tableData);
		column("Session", 44, Entry::label);
		column("Last used", 14, Entry::description);
		column("Folder", 42, Entry::detail);
		viewer.setContentProvider(ArrayContentProvider.getInstance());
		viewer.addFilter(new ViewerFilter() {
			@Override
			public boolean select(Viewer ignored, Object parentElement, Object element) {
				return ((Entry) element).matches(filter.getText());
			}
		});
		viewer.setInput(entries);

		filter.addModifyListener(event -> {
			viewer.refresh();
			selectFirst();
		});
		viewer.addSelectionChangedListener(event -> updateButtons());
		viewer.addOpenListener(event -> okPressed());
		selectFirst();
		return area;
	}

	private void column(String title, int chars, Function<Entry, String> text) {
		TableViewerColumn column = new TableViewerColumn(viewer, SWT.LEFT);
		column.getColumn().setText(title);
		column.getColumn().setWidth(convertWidthInCharsToPixels(chars));
		column.setLabelProvider(new ColumnLabelProvider() {
			@Override
			public String getText(Object element) {
				return text.apply((Entry) element);
			}
		});
	}

	@Override
	protected void createButtonsForButtonBar(Composite parent) {
		createButton(parent, IDialogConstants.OK_ID, "Restore", true);
		createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
		updateButtons();
	}

	@Override
	protected void okPressed() {
		Entry selected = selected();
		if (selected == null) {
			return;
		}
		picked = selected.sessionId();
		super.okPressed();
	}

	private Entry selected() {
		IStructuredSelection selection = viewer.getStructuredSelection();
		return selection.getFirstElement() instanceof Entry entry ? entry : null;
	}

	/** The most recent of what is listed is the one Enter restores. */
	private void selectFirst() {
		Object first = viewer.getElementAt(0);
		viewer.setSelection(first == null ? StructuredSelection.EMPTY : new StructuredSelection(first), true);
		updateButtons();
	}

	private void updateButtons() {
		Button ok = getButton(IDialogConstants.OK_ID);
		if (ok != null) {
			ok.setEnabled(selected() != null);
		}
	}

	// --- what a user does, for the tests: the dialog is created there and never shown ---

	void type(String text) {
		filter.setText(text);
	}

	/** The rows as they are listed: one text per column. */
	List<List<String>> rows() {
		List<List<String>> rows = new ArrayList<>();
		for (TableItem item : viewer.getTable().getItems()) {
			rows.add(List.of(item.getText(0), item.getText(1), item.getText(2)));
		}
		return rows;
	}

	void select(int row) {
		viewer.setSelection(new StructuredSelection(viewer.getElementAt(row)), true);
	}

	boolean canRestore() {
		return getButton(IDialogConstants.OK_ID).isEnabled();
	}

	void restore() {
		buttonPressed(IDialogConstants.OK_ID);
	}

	void cancel() {
		buttonPressed(IDialogConstants.CANCEL_ID);
	}
}
