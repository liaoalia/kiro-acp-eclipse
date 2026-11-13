package io.github.liaoalia.kiroacp.ui.plan;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.ui.handlers.HandlerUtil;

import io.github.liaoalia.kiroacp.core.session.plan.PlanDocument;
import io.github.liaoalia.kiroacp.ui.chat.KiroChat;

/**
 * "Open Kiro Plan", "Approve Kiro Plan" and "Reject Kiro Plan" (the
 * reference's {@code kiroAcp.openPlan}, {@code approvePlan} and
 * {@code rejectPlan}). Open shows the plan of the session used last. Approve
 * and Reject answer the request the active plan editor shows, as its buttons
 * do, else that of whichever session waits for an approval.
 */
public final class PlanCommandHandler extends AbstractHandler {

	static final String PREFIX = "io.github.liaoalia.kiroacp.command.";

	@Override
	public Object execute(ExecutionEvent event) {
		// Never starts the agent by itself: without a chat there is no plan.
		KiroChat chat = KiroChat.peek();
		PlanDocument plan = chat != null ? KiroPlan.of(chat.controller()) : null;
		if (plan == null) {
			return null;
		}
		String command = event.getCommand().getId();
		String session = HandlerUtil.getActiveEditor(event) instanceof PlanEditor editor ? editor.sessionId() : null;
		if (command.equals(PREFIX + "openPlan")) {
			plan.open(null);
		} else if (command.equals(PREFIX + "approvePlan")) {
			// With a plan editor in front, the command is that editor's button: it answers what the editor shows.
			if (session != null) {
				plan.approveShown(session);
			} else {
				plan.approve(null);
			}
		} else if (command.equals(PREFIX + "rejectPlan")) {
			if (session != null) {
				plan.rejectShown(session);
			} else {
				plan.reject(null);
			}
		}
		return null;
	}
}
