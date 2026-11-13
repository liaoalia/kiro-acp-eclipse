package io.github.liaoalia.kiroacp.ui.chat;

import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchListener;
import org.eclipse.ui.PlatformUI;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

import io.github.liaoalia.kiroacp.core.session.ChatController;
import io.github.liaoalia.kiroacp.core.session.ChatHost;
import io.github.liaoalia.kiroacp.core.session.SignInFlow;
import io.github.liaoalia.kiroacp.core.session.history.PastSessions;
import io.github.liaoalia.kiroacp.core.session.history.RememberedOptions;
import io.github.liaoalia.kiroacp.ui.services.ConsoleTerminalDisplay;
import io.github.liaoalia.kiroacp.ui.services.DialogPrompts;
import io.github.liaoalia.kiroacp.ui.services.DisplayScheduler;
import io.github.liaoalia.kiroacp.ui.services.EclipseExternalActions;
import io.github.liaoalia.kiroacp.ui.services.EclipseLogger;
import io.github.liaoalia.kiroacp.ui.services.EclipseTextFiles;
import io.github.liaoalia.kiroacp.ui.services.EclipseWorkspace;
import io.github.liaoalia.kiroacp.ui.services.PreferenceSettings;
import io.github.liaoalia.kiroacp.ui.services.Prompts;
import io.github.liaoalia.kiroacp.ui.sessions.DialogSessionPicker;
import io.github.liaoalia.kiroacp.ui.sessions.PreferenceOptionMemory;

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

	/** The Eclipse services a controller runs on, and the two of them that have to be reached again later. */
	record Services(ChatHost host, EclipseLogger logger, EclipseWorkspace workspace) {
	}

	/**
	 * The host of a chat controller in Eclipse. Nothing in it needs a running
	 * workbench; what only a workbench can do (consoles, the active editor,
	 * opening files) is then left out.
	 *
	 * @param prompts where its questions to the user go; dialogs in the product
	 */
	static Services services(Display display, Prompts prompts) {
		PreferenceSettings settings = new PreferenceSettings();
		EclipseLogger logger = new EclipseLogger(settings);
		EclipseWorkspace workspace = new EclipseWorkspace();
		workspace.start();
		ChatHost host = new ChatHost(settings, workspace, logger, new DisplayScheduler(display),
				new EclipseTextFiles(display, prompts, logger), new ConsoleTerminalDisplay(),
				new EclipseExternalActions(prompts, logger), new EclipseChatActions(logger), version());
		return new Services(host, logger, workspace);
	}

	private KiroChat(Display display) {
		Services services = services(display, new DialogPrompts(display));
		logger = services.logger();
		workspace = services.workspace();
		controller = ChatController.create(services.host());
		SignInFlow.install(controller);
		PastSessions.install(controller, new DialogSessionPicker(display));
		RememberedOptions.install(controller, new PreferenceOptionMemory(logger));
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
