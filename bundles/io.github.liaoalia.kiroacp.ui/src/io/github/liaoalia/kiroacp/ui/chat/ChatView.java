package io.github.liaoalia.kiroacp.ui.chat;

import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.IMemento;
import org.eclipse.ui.IViewSite;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.part.ViewPart;

/** The "Kiro Chat" view: a {@link WebChat} page driven by a host. */
public final class ChatView extends ViewPart {

	public static final String ID = "io.github.liaoalia.kiroacp.chat";

	private String instanceId;
	private WebChat chat;
	private DemoHost host;

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
		// The host is created first; it only talks to the page after its "ready".
		host = new DemoHost(parent.getDisplay(), message -> chat.post(message));
		// The page keeps this view's draft and prompt history under its instance id.
		chat = new WebChat(parent, instanceId, host::onMessage);
	}

	@Override
	public void setFocus() {
		if (chat != null) {
			chat.setFocus();
		}
	}

	@Override
	public void dispose() {
		if (host != null) {
			host.dispose();
		}
		super.dispose();
	}
}
