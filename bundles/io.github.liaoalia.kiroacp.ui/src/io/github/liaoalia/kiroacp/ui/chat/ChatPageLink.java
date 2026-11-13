package io.github.liaoalia.kiroacp.ui.chat;

import java.util.Optional;

import org.eclipse.swt.widgets.Composite;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.liaoalia.kiroacp.core.host.Logger;
import io.github.liaoalia.kiroacp.core.model.FromWebview;
import io.github.liaoalia.kiroacp.core.model.UiJson;
import io.github.liaoalia.kiroacp.core.session.AttachOptions;
import io.github.liaoalia.kiroacp.core.session.ChatController;
import io.github.liaoalia.kiroacp.core.session.PageHandle;

/**
 * One chat page and its place on a chat controller: what the controller says
 * goes to the page, what the page says goes to the controller. The page has a
 * session group of its own, named by its instance id.
 *
 * <p>This is all a chat view is besides being a workbench part, so the same
 * link is what the end-to-end tests run, without a workbench. UI thread only.
 */
final class ChatPageLink {

	private final WebChat chat;
	private final Logger logger;
	/** {@code null} once closed. */
	private PageHandle page;

	ChatPageLink(Composite parent, String instanceId, ChatController controller, Logger logger) {
		this(parent, instanceId, controller, logger, WebChat.Seams.defaults());
	}

	ChatPageLink(Composite parent, String instanceId, ChatController controller, Logger logger, WebChat.Seams seams) {
		this.logger = logger != null ? logger : Logger.NONE;
		// The page keeps this view's draft and prompt history under its instance id.
		chat = new WebChat(parent, instanceId, this::fromPage, seams);
		// Nothing is posted before the page says "ready"; the controller answers that with a snapshot.
		page = controller.attach(message -> chat.post(UiJson.toJson(message)), AttachOptions.ownGroup(instanceId));
	}

	private void fromPage(JsonNode json) {
		if (page == null) {
			return;
		}
		Optional<FromWebview> message = UiJson.fromWebview(json);
		if (message.isPresent()) {
			page.onMessage(message.get());
		} else {
			logger.warn("Ignored a message from the chat page that this version does not understand: "
					+ json.path("type").asText("(no type)"));
		}
	}

	WebChat chat() {
		return chat;
	}

	/** @return the page on the controller, or {@code null} after {@link #close()} */
	PageHandle page() {
		return page;
	}

	/** The page is gone for good: its sessions end, as a closed chat tab's do in the reference. */
	void close() {
		if (page != null) {
			page.close();
			page = null;
		}
	}
}
