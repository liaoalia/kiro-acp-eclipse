package io.github.liaoalia.kiroacp.ui.handlers;

import java.util.UUID;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.handlers.HandlerUtil;

import io.github.liaoalia.kiroacp.ui.chat.ChatView;
import io.github.liaoalia.kiroacp.ui.chat.KiroChat;

/** The chat commands of the reference that make sense without its tabs: one handler, told apart by command id. */
public final class ChatCommandHandler extends AbstractHandler {

	static final String PREFIX = "io.github.liaoalia.kiroacp.command.";
	static final String FOCUS = PREFIX + "focusChat";
	static final String NEW_SESSION = PREFIX + "newSession";
	static final String CANCEL = PREFIX + "cancelTurn";
	static final String RESTART = PREFIX + "restartAgent";
	static final String SHOW_LOG = PREFIX + "showLog";
	static final String NEW_VIEW = PREFIX + "newChatView";

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		String command = event.getCommand().getId();
		IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindow(event);
		if (SHOW_LOG.equals(command)) {
			KiroChat chat = window == null ? KiroChat.peek() : KiroChat.get(window.getShell().getDisplay());
			if (chat != null) {
				chat.logger().show();
			}
			return null;
		}
		if (NEW_VIEW.equals(command)) {
			// One more chat view beside the ones there are: each view has its own sessions.
			IWorkbenchPage page = window == null ? null : window.getActivePage();
			if (page != null) {
				try {
					page.showView(ChatView.ID, UUID.randomUUID().toString(), IWorkbenchPage.VIEW_ACTIVATE);
				} catch (PartInitException e) {
					throw new ExecutionException("Could not open another Kiro Chat view", e);
				}
			}
			return null;
		}
		if (FOCUS.equals(command) || NEW_SESSION.equals(command)) {
			ChatView view = showView(window, HandlerUtil.getActivePart(event));
			if (view != null && NEW_SESSION.equals(command)) {
				KiroChat.get(window.getShell().getDisplay()).controller().newSession(view.pageHandle());
			}
			return null;
		}
		// Cancel and Restart never start anything: without a chat there is nothing to act on.
		KiroChat chat = KiroChat.peek();
		if (chat == null) {
			return null;
		}
		if (CANCEL.equals(command)) {
			// null: the session of the view looked at last.
			chat.controller().cancel(null);
		} else if (RESTART.equals(command)) {
			chat.controller().restart();
		}
		return null;
	}

	/** The active chat view, else the one of the page, opened if there is none. */
	private static ChatView showView(IWorkbenchWindow window, IWorkbenchPart active) throws ExecutionException {
		if (active instanceof ChatView view) {
			return view;
		}
		IWorkbenchPage page = window == null ? null : window.getActivePage();
		if (page == null) {
			return null;
		}
		try {
			return page.showView(ChatView.ID) instanceof ChatView view ? view : null;
		} catch (PartInitException e) {
			throw new ExecutionException("Could not open the Kiro Chat view", e);
		}
	}
}
