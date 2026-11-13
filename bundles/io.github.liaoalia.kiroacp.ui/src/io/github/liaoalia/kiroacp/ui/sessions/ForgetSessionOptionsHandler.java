package io.github.liaoalia.kiroacp.ui.sessions;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.ui.handlers.HandlerUtil;

import io.github.liaoalia.kiroacp.core.host.Logger;
import io.github.liaoalia.kiroacp.core.session.history.RememberedOptions;
import io.github.liaoalia.kiroacp.ui.chat.KiroChat;

/**
 * "Forget Remembered Session Options": the next session starts with the
 * agent's defaults again. Works without a chat, as the options are the
 * workspace's and not a chat's.
 */
public final class ForgetSessionOptionsHandler extends AbstractHandler {

	static final String MESSAGE = "Remembered session options forgotten; new sessions use the agent's defaults.";

	@Override
	public Object execute(ExecutionEvent event) {
		KiroChat chat = KiroChat.peek();
		Logger logger = chat != null ? chat.logger() : Logger.NONE;
		RememberedOptions.forget(new PreferenceOptionMemory(logger), logger);
		MessageDialog.openInformation(HandlerUtil.getActiveShell(event), "Kiro", MESSAGE);
		return null;
	}
}
