package io.github.liaoalia.kiroacp.ui.services;

import org.eclipse.ui.console.IConsole;
import org.eclipse.ui.console.IConsoleConstants;
import org.eclipse.ui.console.IConsolePageParticipant;
import org.eclipse.ui.console.actions.CloseConsoleAction;
import org.eclipse.ui.part.IPageBookViewPage;

/**
 * Gives the consoles of agent commands a Close button in the Console view's
 * toolbar. A message console has none of its own, and closing the console is
 * how the user stops the commands shown in it.
 *
 * <p>Created by the Console view for consoles of type
 * {@link WorkbenchConsoles#CONSOLE_TYPE}, through the
 * {@code org.eclipse.ui.console.consolePageParticipants} extension point.
 */
public final class TerminalConsoleParticipant implements IConsolePageParticipant {

	@Override
	public void init(IPageBookViewPage page, IConsole console) {
		page.getSite().getActionBars().getToolBarManager().appendToGroup(IConsoleConstants.LAUNCH_GROUP,
				new CloseConsoleAction(console));
	}

	@Override
	public void dispose() {
	}

	@Override
	public void activated() {
	}

	@Override
	public void deactivated() {
	}

	@Override
	public <T> T getAdapter(Class<T> adapter) {
		return null;
	}
}
