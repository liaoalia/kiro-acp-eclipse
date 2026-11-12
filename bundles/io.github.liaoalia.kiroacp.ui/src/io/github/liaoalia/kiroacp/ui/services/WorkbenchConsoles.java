package io.github.liaoalia.kiroacp.ui.services;

import java.util.Arrays;

import org.eclipse.jface.resource.ResourceLocator;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.console.ConsolePlugin;
import org.eclipse.ui.console.IConsole;
import org.eclipse.ui.console.IConsoleConstants;
import org.eclipse.ui.console.IConsoleListener;
import org.eclipse.ui.console.IConsoleManager;
import org.eclipse.ui.console.IConsoleView;
import org.eclipse.ui.console.MessageConsole;
import org.eclipse.ui.console.MessageConsoleStream;

/**
 * Consoles of the Console view.
 *
 * <p>Only with a running workbench, for the reasons given at
 * {@link KiroConsole}: without one a message console cannot be set up, and a
 * thread writing to one would wait for a UI job that never runs. No console is
 * created then, and text for an existing one is dropped (Eclipse is shutting
 * down).
 */
final class WorkbenchConsoles implements ConsoleHost {

	/**
	 * The type of the consoles made here. {@link TerminalConsoleParticipant} is
	 * bound to it in {@code plugin.xml}; it gives them their Close button.
	 */
	static final String CONSOLE_TYPE = "io.github.liaoalia.kiroacp.terminal";

	@Override
	public Sink create(String name, Runnable removed) {
		if (!PlatformUI.isWorkbenchRunning()) {
			return null;
		}
		MessageConsole console = new MessageConsole(name, CONSOLE_TYPE,
				ResourceLocator.imageDescriptorFromBundle(PreferenceSettings.QUALIFIER, "icons/kiro.png").orElse(null),
				true);
		console.setWaterMarks(KiroConsole.LOW_WATER_MARK, KiroConsole.HIGH_WATER_MARK);
		IConsoleManager manager = ConsolePlugin.getDefault().getConsoleManager();
		ViewConsole sink = new ViewConsole(manager, console, removed);
		// Listening first: the console may be removed as soon as it is in the view.
		manager.addConsoleListener(sink);
		manager.addConsoles(new IConsole[] { console });
		return sink;
	}

	private static final class ViewConsole implements Sink, IConsoleListener {

		private final IConsoleManager manager;
		private final MessageConsole console;
		private final MessageConsoleStream stream;
		private final Runnable removed;
		private volatile boolean gone;

		ViewConsole(IConsoleManager manager, MessageConsole console, Runnable removed) {
			this.manager = manager;
			this.console = console;
			this.stream = console.newMessageStream();
			this.removed = removed;
		}

		@Override
		public void append(String text) {
			if (!gone && PlatformUI.isWorkbenchRunning()) {
				stream.print(text);
			}
		}

		@Override
		public void show(boolean focus) {
			if (gone || !PlatformUI.isWorkbenchRunning()) {
				return;
			}
			if (!focus) {
				// Opens the view if need be and puts this console on top of it, from a
				// UI job of the console manager; the part the user works in stays active.
				manager.showConsoleView(console);
				return;
			}
			IWorkbench workbench = PlatformUI.getWorkbench();
			Display display = workbench.getDisplay();
			try {
				display.asyncExec(() -> activate(workbench));
			} catch (SWTException disposedMeanwhile) {
				// Nothing left to show it in.
			}
		}

		private void activate(IWorkbench workbench) {
			IWorkbenchWindow window = workbench.getActiveWorkbenchWindow();
			IWorkbenchPage page = window == null ? null : window.getActivePage();
			if (page == null || gone) {
				return;
			}
			try {
				if (page.showView(IConsoleConstants.ID_CONSOLE_VIEW) instanceof IConsoleView view) {
					view.display(console);
				}
			} catch (PartInitException e) {
				ConsolePlugin.log(e);
			}
		}

		@Override
		public void remove() {
			manager.removeConsoles(new IConsole[] { console });
		}

		@Override
		public void consolesAdded(IConsole[] consoles) {
		}

		@Override
		public void consolesRemoved(IConsole[] consoles) {
			if (Arrays.asList(consoles).contains(console)) {
				gone = true;
				manager.removeConsoleListener(this);
				removed.run();
			}
		}
	}
}
