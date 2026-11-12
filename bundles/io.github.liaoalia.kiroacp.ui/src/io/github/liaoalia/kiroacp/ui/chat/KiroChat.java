package io.github.liaoalia.kiroacp.ui.chat;

import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchListener;
import org.eclipse.ui.PlatformUI;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

import io.github.liaoalia.kiroacp.core.session.ChatController;
import io.github.liaoalia.kiroacp.core.session.ChatHost;
import io.github.liaoalia.kiroacp.ui.services.ConsoleTerminalDisplay;
import io.github.liaoalia.kiroacp.ui.services.DialogPrompts;
import io.github.liaoalia.kiroacp.ui.services.DisplayScheduler;
import io.github.liaoalia.kiroacp.ui.services.EclipseExternalActions;
import io.github.liaoalia.kiroacp.ui.services.EclipseLogger;
import io.github.liaoalia.kiroacp.ui.services.EclipseTextFiles;
import io.github.liaoalia.kiroacp.ui.services.EclipseWorkspace;
import io.github.liaoalia.kiroacp.ui.services.PreferenceSettings;
import io.github.liaoalia.kiroacp.ui.services.Prompts;

/**
 * The one chat controller of the running workbench, with the Eclipse services
 * behind it. All chat views share it, and with it one agent process.
 *
 * <p>Created when the first view needs it and shut down with the workbench.
 * UI thread only.
 */
public final class KiroChat {

	private static KiroChat instance;

	private final ChatController controller;
	private final EclipseLogger logger;
	private final EclipseWorkspace workspace;

	private KiroChat(Display display) {
		PreferenceSettings settings = new PreferenceSettings();
		logger = new EclipseLogger(settings);
		workspace = new EclipseWorkspace();
		workspace.start();
		Prompts prompts = new DialogPrompts(display);
		ChatHost host = new ChatHost(settings, workspace, logger, new DisplayScheduler(display),
				new EclipseTextFiles(display, prompts, logger), new ConsoleTerminalDisplay(),
				new EclipseExternalActions(prompts, logger), new EclipseChatActions(logger), version());
		controller = ChatController.create(host);
	}

	/** @return the shared instance, created on first use */
	public static KiroChat get(Display display) {
		if (instance == null) {
			KiroChat created = new KiroChat(display);
			instance = created;
			if (PlatformUI.isWorkbenchRunning()) {
				PlatformUI.getWorkbench().addWorkbenchListener(new IWorkbenchListener() {
					@Override
					public boolean preShutdown(IWorkbench workbench, boolean forced) {
						return true;
					}

					@Override
					public void postShutdown(IWorkbench workbench) {
						created.shutdown();
					}
				});
			}
		}
		return instance;
	}

	/** @return the shared instance if one exists; commands use it so they never start the agent by themselves */
	public static KiroChat peek() {
		return instance;
	}

	public ChatController controller() {
		return controller;
	}

	public EclipseLogger logger() {
		return logger;
	}

	private void shutdown() {
		if (instance == this) {
			instance = null;
		}
		// On the UI thread this takes effect at once, which matters here: the display is about to go.
		controller.shutdown();
		workspace.dispose();
	}

	private static String version() {
		Bundle bundle = FrameworkUtil.getBundle(KiroChat.class);
		return bundle == null ? "0.0.0" : bundle.getVersion().toString();
	}
}
