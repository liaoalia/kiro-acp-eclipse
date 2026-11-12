package io.github.liaoalia.kiroacp.core.host;

import io.github.liaoalia.kiroacp.core.model.ToWebview;

/**
 * One chat page as the controller sees it. Called on the controller thread.
 *
 * <p>A page that is not up yet, or is gone, drops the message: the controller
 * sends a full {@code state} snapshot whenever a page says {@code ready}.
 */
@FunctionalInterface
public interface ChatPage {

	void post(ToWebview message);
}
