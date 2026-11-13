package io.github.liaoalia.kiroacp.core.session.plan;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.liaoalia.kiroacp.core.host.Logger;
import io.github.liaoalia.kiroacp.core.host.Subscription;
import io.github.liaoalia.kiroacp.core.host.UiScheduler;
import io.github.liaoalia.kiroacp.core.model.FromWebview;
import io.github.liaoalia.kiroacp.core.model.NoticeView.Severity;
import io.github.liaoalia.kiroacp.core.model.PermissionView;
import io.github.liaoalia.kiroacp.core.model.PlanEntryView;
import io.github.liaoalia.kiroacp.core.model.ToolKind;
import io.github.liaoalia.kiroacp.core.pure.permission.PolicyDecision;
import io.github.liaoalia.kiroacp.core.pure.plan.PlanMarkdown;
import io.github.liaoalia.kiroacp.core.pure.transcript.TranscriptState;
import io.github.liaoalia.kiroacp.core.services.PendingPermission;
import io.github.liaoalia.kiroacp.core.services.PermissionManager;
import io.github.liaoalia.kiroacp.core.session.ChatController;
import io.github.liaoalia.kiroacp.core.session.ChatHooks;
import io.github.liaoalia.kiroacp.core.session.plan.PlanDocuments.Content;

/**
 * The plan of a session as a live Markdown document, and the approval of
 * the hand-over from planning to doing (the reference's
 * {@code features/plan.ts}, its decision D11).
 *
 * <p>ACP has no plan-approval method. Kiro's planner is a session mode, and
 * leaving it arrives as a permission request whose tool call has the kind
 * {@code switch_mode}. While one waits, the session's document says so and
 * offers Approve and Reject, which answer that request.
 *
 * <p>Each session has its own document, named by the session's id, where the
 * reference has a single one that follows the focused session: several chat
 * views are open side by side here, each with its sessions. A document opens
 * by itself the first time its session, being the focused one, gets a plan
 * that is not empty; after that only when asked ({@code openPlan} of the
 * page, {@link #open}). It is closed with its session.
 *
 * <p>Controller thread only.
 */
public final class PlanDocument implements AutoCloseable {

	/** As the reference: changes within this time reach the document as one. */
	static final long DEBOUNCE_MS = 50;

	private final ChatController controller;
	private final PermissionManager permissions;
	private final UiScheduler scheduler;
	private final Logger logger;
	private final PlanDocuments documents;
	private final ZoneId zone;
	private final boolean autoOpen;
	private final List<Subscription> subscriptions = new ArrayList<>();
	/** When the plan of a session last changed, in milliseconds since the epoch. */
	private final Map<String, Long> updatedAt = new HashMap<>();
	/** The sessions whose document opened by itself once. */
	private final Set<String> autoOpened = new HashSet<>();
	/** The sessions a document was shown for and not closed here; the user may have closed it. */
	private final Set<String> shown = new LinkedHashSet<>();
	private final Set<String> dirty = new LinkedHashSet<>();
	/**
	 * The request to leave planning each session's document was last written
	 * for, {@code null} when it offered nothing to answer: what its Approve
	 * and Reject answer.
	 */
	private final Map<String, String> shownRequest = new HashMap<>();
	private Subscription refresh;
	private boolean closed;

	private PlanDocument(ChatController controller, PlanDocuments documents, ZoneId zone, boolean autoOpen) {
		this.controller = controller;
		this.permissions = controller.permissions();
		this.scheduler = controller.host().scheduler();
		this.logger = controller.host().logger();
		this.documents = documents;
		this.zone = zone != null ? zone : ZoneId.systemDefault();
		this.autoOpen = autoOpen;
	}

	/** Install the feature in a controller: plan documents that open by themselves, and the handler of {@code openPlan}. */
	public static PlanDocument install(ChatController controller, PlanDocuments documents) {
		return install(controller, documents, null, true);
	}

	/**
	 * @param zone     the time zone of "updated 12:34:56"; {@code null} for the system's
	 * @param autoOpen open a session's document the first time it gets a plan
	 */
	public static PlanDocument install(ChatController controller, PlanDocuments documents, ZoneId zone, boolean autoOpen) {
		PlanDocument plan = new PlanDocument(controller, documents, zone, autoOpen);
		ChatHooks hooks = controller.hooks();
		plan.subscriptions.add(hooks.addListener(plan.new Listener()));
		plan.subscriptions.add(hooks.onMessage(FromWebview.OpenPlan.class, (message, page) -> plan.open(page.sessionId())));
		// From whichever thread answered or registered a request: the note and the buttons follow.
		plan.subscriptions.add(controller.permissions().onDidChange(() -> plan.scheduler.execute(plan::permissionsChanged)));
		return plan;
	}

	private final class Listener implements ChatHooks.SessionListener {

		@Override
		public void update(String sessionId, JsonNode update, boolean replay) {
			// Every session's plan, also one that is not focused; the transcript takes the
			// update in right after this, before the document is refreshed.
			if ("plan".equals(update.path("sessionUpdate").asText(""))) {
				updatedAt.put(sessionId, controller.host().clock().getAsLong());
				touch(sessionId);
			}
		}

		@Override
		public void planChanged(String sessionId, List<PlanEntryView> entries) {
			planOfFocusedSessionChanged(sessionId, entries);
		}

		@Override
		public void permissionRequested(String sessionId, PermissionView request) {
			if (request.kind() == ToolKind.SWITCH_MODE) {
				touch(sessionId);
			}
		}

		@Override
		public void sessionClosing(String sessionId) {
			forget(sessionId);
		}
	}

	// ---------------------------------------------------------------------
	// For the IDE
	// ---------------------------------------------------------------------

	/**
	 * Show the plan document of a session beside the chat.
	 *
	 * @param sessionId {@code null} for the focused session
	 * @return whether a document is shown now
	 */
	public boolean open(String sessionId) {
		String id = sessionId != null ? sessionId : controller.focusedSessionId();
		if (closed || id == null || controller.transcript(id) == null) {
			logger.info("no session to show a plan of");
			return false;
		}
		try {
			PendingPermission pending = pending(id);
			documents.show(id, content(id, pending), new PlanDocuments.Approval() {
				@Override
				public void approve() {
					approveShown(id);
				}

				@Override
				public void reject() {
					rejectShown(id);
				}
			});
			shown.add(id);
			shownRequest.put(id, pending != null ? pending.requestId() : null);
			return true;
		} catch (RuntimeException e) {
			controller.notice(id, Severity.WARNING, "Could not open the plan: " + message(e));
			return false;
		}
	}

	/** The Markdown of a session's plan as its document shows it. */
	public String text(String sessionId) {
		return content(sessionId).markdown();
	}

	/** Whether a request to leave planning waits for the user in that session. */
	public boolean approvalPending(String sessionId) {
		return pending(sessionId) != null;
	}

	/**
	 * Approve the hand-over the agent asked for.
	 *
	 * @param sessionId {@code null} for whichever session waits for an approval, as the reference's command
	 */
	public void approve(String sessionId) {
		answer(sessionId, PolicyDecision.ALLOW);
	}

	/** Reject it; see {@link #approve}. */
	public void reject(String sessionId) {
		answer(sessionId, PolicyDecision.REJECT);
	}

	/**
	 * Approve what the session's document shows: the request its text was
	 * last written for, and no other. A document is brought up to date a
	 * moment after things change, so the request it offers to answer may be
	 * answered already, and the agent may have asked again for a plan the
	 * document does not show yet. Then nothing is answered: the document is
	 * brought up to date, and the user is told to read it again.
	 */
	public void approveShown(String sessionId) {
		answerShown(sessionId, PolicyDecision.ALLOW);
	}

	/** Reject what the session's document shows; see {@link #approveShown}. */
	public void rejectShown(String sessionId) {
		answerShown(sessionId, PolicyDecision.REJECT);
	}

	@Override
	public void close() {
		closed = true;
		subscriptions.forEach(Subscription::close);
		subscriptions.clear();
		if (refresh != null) {
			refresh.close();
			refresh = null;
		}
		for (String sessionId : List.copyOf(shown)) {
			forget(sessionId);
		}
	}

	// ---------------------------------------------------------------------
	// The documents
	// ---------------------------------------------------------------------

	private Content content(String sessionId) {
		return content(sessionId, pending(sessionId));
	}

	/** @param pending the request to leave planning the text is written for, or {@code null} */
	private Content content(String sessionId, PendingPermission pending) {
		TranscriptState transcript = controller.transcript(sessionId);
		List<PlanEntryView> entries = transcript != null ? transcript.plan() : List.of();
		Long at = updatedAt.get(sessionId);
		LocalTime time = at != null ? Instant.ofEpochMilli(at).atZone(zone).toLocalTime() : null;
		return new Content(PlanMarkdown.render(entries, sessionId, time, pending != null), pending != null);
	}

	/** Brings the session's document up to date, and remembers which request it now offers to answer. */
	private void update(String sessionId) {
		dirty.remove(sessionId);
		try {
			PendingPermission pending = pending(sessionId);
			documents.update(sessionId, content(sessionId, pending));
			shownRequest.put(sessionId, pending != null ? pending.requestId() : null);
		} catch (RuntimeException e) {
			logger.warn("plan document of " + sessionId + " could not be updated: " + message(e));
		}
	}

	/**
	 * The plan of the focused session changed: its document opens by itself
	 * the first time the plan is not empty, unless it is open already.
	 */
	private void planOfFocusedSessionChanged(String sessionId, List<PlanEntryView> entries) {
		touch(sessionId);
		if (!autoOpen || closed || entries.isEmpty() || autoOpened.contains(sessionId) || isOpen(sessionId)) {
			return;
		}
		autoOpened.add(sessionId);
		open(sessionId);
	}

	private boolean isOpen(String sessionId) {
		try {
			return documents.isOpen(sessionId);
		} catch (RuntimeException e) {
			logger.warn("plan document of " + sessionId + ": " + message(e));
			return false;
		}
	}

	/** The document of the session is out of date; it is brought up to date shortly, once for all that changes until then. */
	private void touch(String sessionId) {
		if (closed || !shown.contains(sessionId)) {
			return;
		}
		dirty.add(sessionId);
		if (refresh == null) {
			refresh = scheduler.schedule(DEBOUNCE_MS, this::refreshDirty);
		}
	}

	private void refreshDirty() {
		refresh = null;
		List<String> due = List.copyOf(dirty);
		dirty.clear();
		for (String sessionId : due) {
			if (closed || !shown.contains(sessionId)) {
				continue;
			}
			update(sessionId);
		}
	}

	private void permissionsChanged() {
		for (String sessionId : List.copyOf(shown)) {
			touch(sessionId);
		}
	}

	private void forget(String sessionId) {
		updatedAt.remove(sessionId);
		autoOpened.remove(sessionId);
		dirty.remove(sessionId);
		shownRequest.remove(sessionId);
		if (shown.remove(sessionId)) {
			try {
				documents.close(sessionId);
			} catch (RuntimeException e) {
				logger.warn("plan document of " + sessionId + " could not be closed: " + message(e));
			}
		}
	}

	// ---------------------------------------------------------------------
	// Approval
	// ---------------------------------------------------------------------

	/** The oldest {@code switch_mode} request waiting in the session, or in any session when {@code sessionId} is {@code null}. */
	private PendingPermission pending(String sessionId) {
		for (String requestId : permissions.pendingIds()) {
			PendingPermission pending = permissions.getEntry(requestId);
			if (pending != null && pending.toolKind() == ToolKind.SWITCH_MODE
					&& (sessionId == null || sessionId.equals(pending.sessionId()))) {
				return pending;
			}
		}
		return null;
	}

	private void answer(String sessionId, PolicyDecision decision) {
		PendingPermission pending = pending(sessionId);
		if (pending == null) {
			controller.notice(sessionId, Severity.INFO,
					"Nothing is awaiting approval: Kiro has not asked to switch out of planning yet.");
			return;
		}
		decide(pending, decision);
	}

	/** The buttons of a document: they answer the request the document shows, or none. */
	private void answerShown(String sessionId, PolicyDecision decision) {
		String requestId = shownRequest.get(sessionId);
		PendingPermission offered = requestId != null ? permissions.getEntry(requestId) : null;
		if (offered != null) {
			decide(offered, decision);
			return;
		}
		// The document is behind, or offers nothing to answer.
		PendingPermission waiting = pending(sessionId);
		if (!closed && shown.contains(sessionId)) {
			update(sessionId);
		}
		if (waiting != null) {
			controller.notice(sessionId, Severity.INFO,
					"The plan changed after this document was last brought up to date. Review it again, then approve or reject it.");
		} else if (requestId == null) {
			// Nothing was offered: this was the command, with the document in front.
			controller.notice(sessionId, Severity.INFO,
					"Nothing is awaiting approval: Kiro has not asked to switch out of planning yet.");
		}
	}

	private void decide(PendingPermission pending, PolicyDecision decision) {
		if (permissions.decideRequest(pending.requestId(), decision) == null) {
			controller.notice(pending.sessionId(), Severity.WARNING, "The agent offered no \""
					+ (decision == PolicyDecision.ALLOW ? "allow" : "reject") + "\" option for this request; answer it in the chat view.");
		}
	}

	private static String message(Throwable error) {
		return error.getMessage() != null && !error.getMessage().isEmpty() ? error.getMessage() : error.toString();
	}
}
