package io.github.liaoalia.kiroacp.ui.chat;

import java.util.Optional;

import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.IMemento;
import org.eclipse.ui.IViewSite;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.part.ViewPart;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.liaoalia.kiroacp.core.model.FromWebview;
import io.github.liaoalia.kiroacp.core.model.UiJson;
import io.github.liaoalia.kiroacp.core.session.AttachOptions;
import io.github.liaoalia.kiroacp.core.session.PageHandle;

/**
 * The "Kiro Chat" view: a {@link WebChat} page attached to the workbench's
 * chat controller. Each view has its own sessions; all share one agent process.
 */
public final class ChatView extends ViewPart {

	public static final String ID = "io.github.liaoalia.kiroacp.chat";

	private String instanceId;
	private WebChat chat;
	private KiroChat kiro;
	private PageHandle page;

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
		kiro = KiroChat.get(parent.getDisplay());
		// The page keeps this view's draft and prompt history under its instance id.
		chat = new WebChat(parent, instanceId, this::fromPage);
		// Nothing is posted before the page says "ready"; the controller answers that with a snapshot.
		page = kiro.controller().attach(message -> chat.post(UiJson.toJson(message)), AttachOptions.ownGroup(instanceId));
	}

	private void fromPage(JsonNode json) {
		if (page == null) {
			return;
		}
		Optional<FromWebview> message = UiJson.fromWebview(json);
		if (message.isPresent()) {
			page.onMessage(message.get());
		} else {
			kiro.logger().warn("Ignored a message from the chat page that this version does not understand: "
					+ json.path("type").asText("(no type)"));
		}
	}

	/** @return this view's page on the controller, or {@code null} before the view is created and after it is closed */
	public PageHandle pageHandle() {
		return page;
	}

	@Override
	public void setFocus() {
		if (chat != null) {
			chat.setFocus();
		}
		if (page != null) {
			// Commands without a view of their own act on the session looked at last.
			page.focus();
		}
	}

	@Override
	public void dispose() {
		if (page != null) {
			// A closed view ends its sessions, as a closed chat tab does in the reference.
			page.close();
			page = null;
		}
		super.dispose();
	}
}
