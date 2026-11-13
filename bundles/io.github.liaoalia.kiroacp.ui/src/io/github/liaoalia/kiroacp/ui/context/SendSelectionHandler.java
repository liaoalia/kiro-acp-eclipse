package io.github.liaoalia.kiroacp.ui.context;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.handlers.HandlerUtil;

import io.github.liaoalia.kiroacp.core.session.context.ChatContext;
import io.github.liaoalia.kiroacp.ui.chat.ChatView;
import io.github.liaoalia.kiroacp.ui.chat.KiroChat;

/**
 * "Send Selection to Kiro Chat" (the reference's {@code kiroAcp.sendSelection}):
 * the active editor's selection, or its current line when nothing is
 * selected, becomes a chip of the chat, and the chat gets the focus so the
 * question about it can be typed at once.
 */
public final class SendSelectionHandler extends AbstractHandler {

	static final String NO_EDITOR = "Open a file and select some text first.";

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindow(event);
		if (window == null) {
			return null;
		}
		ChatContext context = KiroContext.of(KiroChat.get(window.getShell().getDisplay()).controller());
		// Before the chat is shown: the selection is the one of the editor that is active now.
		if (context == null || !context.addSelectionChip()) {
			MessageDialog.openInformation(window.getShell(), "Kiro", NO_EDITOR);
			return null;
		}
		showChat(window.getActivePage());
		return null;
	}

	/** Gives the focus to a chat view: one that is in sight, else any, else a new one. */
	private static void showChat(IWorkbenchPage page) throws ExecutionException {
		if (page == null) {
			return;
		}
		IViewPart open = null;
		for (IViewReference reference : page.getViewReferences()) {
			if (!ChatView.ID.equals(reference.getId())) {
				continue;
			}
			IViewPart view = reference.getView(false);
			if (view != null && page.isPartVisible(view)) {
				page.activate(view);
				return;
			}
			if (open == null) {
				open = view;
			}
		}
		if (open != null) {
			page.activate(open);
			return;
		}
		try {
			page.showView(ChatView.ID);
		} catch (PartInitException e) {
			throw new ExecutionException("Could not open the Kiro Chat view", e);
		}
	}
}
