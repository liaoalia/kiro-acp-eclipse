package io.github.liaoalia.kiroacp.ui.auth;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.handlers.HandlerUtil;

import io.github.liaoalia.kiroacp.core.model.FromWebview;
import io.github.liaoalia.kiroacp.ui.chat.ChatView;

/**
 * "Log In to Kiro" ({@code Kiro: Log In} of the reference): the sign-in of
 * the chat's banner, available at any time, for instance to switch accounts
 * while still signed in (reference D25).
 *
 * <p>The chat view is shown first and the request goes the way its button
 * goes, as a {@code login} message of that page: what follows (the notice to
 * finish in the terminal, a failure, the sessions coming back) is told in
 * the chat, so there has to be one to tell it in.
 */
public final class SignInHandler extends AbstractHandler {

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		ChatView view = HandlerUtil.getActivePart(event) instanceof ChatView active ? active : null;
		if (view == null) {
			IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindow(event);
			IWorkbenchPage page = window == null ? null : window.getActivePage();
			if (page == null) {
				return null;
			}
			try {
				view = page.showView(ChatView.ID) instanceof ChatView shown ? shown : null;
			} catch (PartInitException e) {
				throw new ExecutionException("Could not open the Kiro Chat view", e);
			}
		}
		if (view != null && view.pageHandle() != null) {
			// No method named: the agent's terminal method, else `kiro-cli logout` and `kiro-cli login`.
			view.pageHandle().onMessage(new FromWebview.Login(null));
		}
		return null;
	}
}
