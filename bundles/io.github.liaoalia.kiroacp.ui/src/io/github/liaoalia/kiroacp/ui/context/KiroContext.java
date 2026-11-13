package io.github.liaoalia.kiroacp.ui.context;

import java.util.Map;
import java.util.WeakHashMap;

import org.eclipse.swt.widgets.Display;

import io.github.liaoalia.kiroacp.core.session.ChatController;
import io.github.liaoalia.kiroacp.core.session.ChatHost;
import io.github.liaoalia.kiroacp.core.session.context.ChatContext;

/**
 * File, selection and image context in Eclipse: the core's
 * {@link ChatContext} on the workbench's active editor, the platform's file
 * dialog and the resource tree's knowledge of what is build output.
 */
public final class KiroContext {

	/** The feature of each controller it was installed in, for the commands that act on it. */
	private static final Map<ChatController, ChatContext> INSTALLED = new WeakHashMap<>();

	private KiroContext() {
	}

	/**
	 * Install the feature in a controller.
	 *
	 * @param host the services the controller itself runs on
	 */
	public static ChatContext install(ChatController controller, ChatHost host, Display display) {
		WorkbenchEditorContext editor = new WorkbenchEditorContext();
		editor.start();
		ChatContext context = ChatContext.install(controller, new ChatContext.Host(host.workspace(), host.settings(), host.scheduler(),
				host.textFiles(), host.logger(), editor, new ImageFileDialog(display), new ResourceExclusions()));
		synchronized (INSTALLED) {
			INSTALLED.put(controller, context);
		}
		return context;
	}

	/** @return the feature installed in the controller, or {@code null} */
	public static ChatContext of(ChatController controller) {
		synchronized (INSTALLED) {
			return INSTALLED.get(controller);
		}
	}
}
