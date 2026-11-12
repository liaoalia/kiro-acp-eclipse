package io.github.liaoalia.kiroacp.core.session;

import java.util.concurrent.CompletableFuture;

import io.github.liaoalia.kiroacp.core.host.ChatPage;
import io.github.liaoalia.kiroacp.core.model.FromWebview;
import io.github.liaoalia.kiroacp.core.model.ToWebview;
import io.github.liaoalia.kiroacp.core.model.UiState;
import io.github.liaoalia.kiroacp.core.session.AttachOptions.Prefer;

/** An attached page as the controller keeps it ({@code ViewBinding} in the reference). */
final class PageBinding implements PageHandle {

	final ChatPage page;
	final String groupId;
	private final ChatController controller;
	/** Read by other threads through {@link #sessionId()}; written on the controller thread. */
	volatile String displayed;
	/** Pending initial assignment; cleared once a session is displayed. */
	Prefer prefer;
	/** In-flight initial assignment: {@code ready} and a first prompt must not open two sessions. */
	CompletableFuture<Void> assigning;
	/** What the last {@code sessions} list posted to this page was made of. */
	String lastSessionsKey = "";

	PageBinding(ChatController controller, ChatPage page, AttachOptions options) {
		this.controller = controller;
		this.page = page;
		this.prefer = options.prefer();
		this.groupId = options.groupId();
	}

	@Override
	public String sessionId() {
		return displayed;
	}

	@Override
	public String groupId() {
		return groupId;
	}

	@Override
	public void onMessage(FromWebview message) {
		controller.handle(message, this);
	}

	@Override
	public void setSession(String sessionId) {
		controller.async.run(() -> controller.setPageSession(this, sessionId));
	}

	@Override
	public void focus() {
		controller.async.run(() -> controller.focusPage(this));
	}

	@Override
	public UiState snapshot() {
		return controller.snapshots.forPage(this);
	}

	@Override
	public void post(ToWebview message) {
		controller.postPage(this, message);
	}

	@Override
	public void detach() {
		controller.async.run(() -> controller.detachPage(this));
	}

	@Override
	public void close() {
		controller.async.run(() -> {
			controller.detachPage(this);
			if (groupId != null) {
				controller.async.detached(controller.closeGroup(groupId), "closing the sessions of " + groupId);
			}
		});
	}
}
