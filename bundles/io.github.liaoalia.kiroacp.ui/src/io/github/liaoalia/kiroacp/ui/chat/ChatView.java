package io.github.liaoalia.kiroacp.ui.chat;

import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.IMemento;
import org.eclipse.ui.IViewSite;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.part.ViewPart;

import io.github.liaoalia.kiroacp.core.session.PageHandle;

/**
 * The "Kiro Chat" view: a {@link WebChat} page attached to the workbench's
 * chat controller. Each view has its own sessions; all share one agent process.
 */
public final class ChatView extends ViewPart {

	public static final String ID = "io.github.liaoalia.kiroacp.chat";

	private String instanceId;
	private ChatPageLink link;

	@Override
	public void init(IViewSite site, IMemento memento) throws PartInitException {
		super.init(site, memento);
		instanceId = ViewInstanceId.resolve(memento == null ? null : memento.getString(ViewInstanceId.MEMENTO_KEY));
	}

	@Override
	public void saveState(IMemento memento) {
		super.saveState(memento);
		memento.putString(ViewInstanceId.MEMENTO_KEY, instanceId);
	}

	@Override
	public void createPartControl(Composite parent) {
		parent.setLayout(new FillLayout());
		KiroChat kiro = KiroChat.get(parent.getDisplay());
		link = new ChatPageLink(parent, instanceId, kiro.controller(), kiro.logger());
	}

	/** @return this view's page on the controller, or {@code null} before the view is created and after it is closed */
	public PageHandle pageHandle() {
		return link == null ? null : link.page();
	}

	@Override
	public void setFocus() {
		if (link == null) {
			return;
		}
		link.chat().setFocus();
		PageHandle page = link.page();
		if (page != null) {
			// Commands without a view of their own act on the session looked at last.
			page.focus();
		}
	}

	@Override
	public void dispose() {
		if (link != null) {
			// A closed view ends its sessions, as a closed chat tab does in the reference.
			link.close();
		}
		super.dispose();
	}
}
