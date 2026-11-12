package io.github.liaoalia.kiroacp.ui.chat;

import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.part.ViewPart;

/** The "Kiro Chat" view: a {@link WebChat} page driven by a host. */
public final class ChatView extends ViewPart {

	public static final String ID = "io.github.liaoalia.kiroacp.chat";

	private WebChat chat;
	private DemoHost host;

	@Override
	public void createPartControl(Composite parent) {
		parent.setLayout(new FillLayout());
		// The host is created first; it only talks to the page after its "ready".
		host = new DemoHost(parent.getDisplay(), message -> chat.post(message));
		// Each view instance keeps its own draft and prompt history in the page.
		String secondaryId = getViewSite().getSecondaryId();
		chat = new WebChat(parent, secondaryId == null ? "main" : secondaryId, host::onMessage);
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
