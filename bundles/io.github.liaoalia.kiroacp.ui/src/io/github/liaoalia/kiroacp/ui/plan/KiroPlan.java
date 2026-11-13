package io.github.liaoalia.kiroacp.ui.plan;

import java.util.Map;
import java.util.WeakHashMap;

import io.github.liaoalia.kiroacp.core.session.ChatController;
import io.github.liaoalia.kiroacp.core.session.plan.PlanDocument;

/** The plan document in Eclipse: the core's {@link PlanDocument} on editors of the workbench. */
public final class KiroPlan {

	/** The feature of each controller it was installed in, for the commands that act on it. */
	private static final Map<ChatController, PlanDocument> INSTALLED = new WeakHashMap<>();

	private KiroPlan() {
	}

	/** Install the feature in a controller. */
	public static PlanDocument install(ChatController controller) {
		PlanDocument plan = PlanDocument.install(controller, new EditorPlanDocuments());
		synchronized (INSTALLED) {
			INSTALLED.put(controller, plan);
		}
		return plan;
	}

	/** @return the feature installed in the controller, or {@code null} */
	public static PlanDocument of(ChatController controller) {
		synchronized (INSTALLED) {
			return INSTALLED.get(controller);
		}
	}
}
