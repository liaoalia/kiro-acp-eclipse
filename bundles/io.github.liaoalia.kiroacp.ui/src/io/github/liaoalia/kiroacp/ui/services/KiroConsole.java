package io.github.liaoalia.kiroacp.ui.services;

import org.eclipse.jface.resource.ResourceLocator;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.console.ConsolePlugin;
import org.eclipse.ui.console.IConsole;
import org.eclipse.ui.console.IConsoleManager;
import org.eclipse.ui.console.MessageConsole;
import org.eclipse.ui.console.MessageConsoleStream;

/**
 * The "Kiro ACP" console of the Console view, created on first use.
 *
 * <p>Without a running workbench, text is dropped. A console moves text into
 * its document with a job on the workbench's display; with no workbench the
 * job never runs, and a thread writing more than the console's queue holds
 * would wait for it forever. That is the state of the plugin's threads while
 * Eclipse shuts down, and of tests in a plain runtime.
 *
 * <p>With a workbench, a thread that writes faster than the UI thread takes
 * the text is slowed down by the console; nothing is lost.
 */
final class KiroConsole implements EclipseLogger.Sink {

	static final String NAME = "Kiro ACP";

	/** Characters kept once the console is trimmed, and the size that triggers trimming. */
	static final int LOW_WATER_MARK = 400_000;
	static final int HIGH_WATER_MARK = 500_000;

	private MessageConsole console;
	private MessageConsoleStream stream;

	@Override
	public void append(String lines) {
		MessageConsoleStream out;
		synchronized (this) {
			out = open() ? stream : null;
		}
		if (out != null) {
			// One call per batch of lines: the stream keeps a call's text together.
			out.print(lines);
		}
	}

	@Override
	public void show() {
		MessageConsole shown;
		synchronized (this) {
			shown = open() ? console : null;
		}
		if (shown != null) {
			// Opens the Console view from a UI job of its own, so any thread may ask.
			ConsolePlugin.getDefault().getConsoleManager().showConsoleView(shown);
		}
	}

	private boolean open() {
		if (!PlatformUI.isWorkbenchRunning()) {
			return false;
		}
		if (console == null) {
			IConsoleManager manager = ConsolePlugin.getDefault().getConsoleManager();
			MessageConsole found = existing(manager);
			if (found == null) {
				found = new MessageConsole(NAME,
						ResourceLocator.imageDescriptorFromBundle(PreferenceSettings.QUALIFIER, "icons/kiro.png")
								.orElse(null));
				found.setWaterMarks(LOW_WATER_MARK, HIGH_WATER_MARK);
				manager.addConsoles(new IConsole[] { found });
			}
			stream = found.newMessageStream();
			console = found;
		}
		return true;
	}

	/** A second logger writes to the console the first one made instead of adding a twin. */
	private static MessageConsole existing(IConsoleManager manager) {
		for (IConsole each : manager.getConsoles()) {
			if (each instanceof MessageConsole message && NAME.equals(each.getName())) {
				return message;
			}
		}
		return null;
	}
}
