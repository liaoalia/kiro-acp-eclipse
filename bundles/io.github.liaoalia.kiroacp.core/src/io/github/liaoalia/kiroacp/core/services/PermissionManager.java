package io.github.liaoalia.kiroacp.core.services;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.liaoalia.kiroacp.core.host.Logger;
import io.github.liaoalia.kiroacp.core.host.Subscription;
import io.github.liaoalia.kiroacp.core.model.PermissionOptionView;
import io.github.liaoalia.kiroacp.core.model.PermissionScope;
import io.github.liaoalia.kiroacp.core.model.PermissionView;
import io.github.liaoalia.kiroacp.core.model.ToolContentView;
import io.github.liaoalia.kiroacp.core.model.ToolKind;
import io.github.liaoalia.kiroacp.core.pure.permission.PermissionPolicies;
import io.github.liaoalia.kiroacp.core.pure.permission.PermissionPolicy;
import io.github.liaoalia.kiroacp.core.pure.permission.PolicyDecision;
import io.github.liaoalia.kiroacp.core.pure.permission.PolicySubject;

/**
 * Pending {@code session/request_permission} registry and session policy
 * (reference D6, D23).
 *
 * <p>The ACP layer calls {@link #request}; the chat card, the compare editor's
 * buttons and the plan approval resolve entries through {@link #resolve},
 * {@link #decideRequest} and {@link #setPolicy}.
 *
 * <p>Thread-safe: requests arrive on the connection's reader thread, answers
 * from the controller thread, cancellation from wherever the turn was
 * cancelled. Listeners are called on the thread that caused the change, after
 * the manager's state is updated and before the request's future completes, so
 * what a resolution listener does is done when the agent gets its answer. They
 * are never called under the manager's lock and may call back into it; a
 * listener that needs the controller thread hops there itself.
 */
public final class PermissionManager implements AutoCloseable {

	/**
	 * Kind of an already-known tool call of a session (from that session's
	 * transcript), used when {@code session/request_permission.toolCall} carries
	 * no {@code kind}: the real Kiro CLI omits it (D23). Tool call ids are only
	 * unique within a session, so the lookup must never consult other sessions.
	 *
	 * <p>Called on the connection's reader thread, right after that thread
	 * delivered the {@code tool_call} update the request refers to. An
	 * implementation that keeps its transcript on another thread must therefore
	 * record kinds where the reader thread can see them at once.
	 */
	@FunctionalInterface
	public interface ToolCallKinds {

		/** @return the kind, or {@code null} when the tool call is not known */
		ToolKind resolveKind(String sessionId, String toolCallId);
	}

	private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

	private final ToolCallKinds kinds;
	private final Logger logger;
	private final Object lock = new Object();
	private final PermissionPolicy policy = new PermissionPolicy();
	/** Insertion order: the pending list is shown oldest first. */
	private final Map<String, Entry> entries = new LinkedHashMap<>();
	private long counter;
	private boolean disposed;
	private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();
	private final List<Consumer<PermissionResolution>> resolveListeners = new CopyOnWriteArrayList<>();

	private static final class Entry {
		/** Set once, under the manager's lock, when the request becomes pending. */
		PendingPermission record;
		final CompletableFuture<JsonNode> future = new CompletableFuture<>();
		/** Guarded by the manager's lock. */
		boolean cancelled;
	}

	public PermissionManager() {
		this(null, null);
	}

	/**
	 * @param kinds  where the kind of a request without one comes from; may be {@code null}
	 * @param logger reports listeners that throw; may be {@code null}
	 */
	public PermissionManager(ToolCallKinds kinds, Logger logger) {
		this.kinds = kinds;
		this.logger = logger != null ? logger : Logger.NONE;
	}

	/**
	 * Fires after any change to the pending set.
	 *
	 * @return removes the listener
	 */
	public Subscription onDidChange(Runnable listener) {
		changeListeners.add(listener);
		return () -> changeListeners.remove(listener);
	}

	/**
	 * Fires when a request is answered (selected or cancelled).
	 *
	 * @return removes the listener
	 */
	public Subscription onDidResolve(Consumer<PermissionResolution> listener) {
		resolveListeners.add(listener);
		return () -> resolveListeners.remove(listener);
	}

	// ---------------------------------------------------------------------
	// ClientServices entry
	// ---------------------------------------------------------------------

	/**
	 * Answer a permission request: at once from the session policy, or by
	 * registering a pending entry the UI resolves later.
	 *
	 * @param request   the ACP {@code RequestPermissionRequest}
	 * @param cancelled completes (in any way) when the agent's turn was cancelled: the request is
	 *                  then answered {@code cancelled}; may be {@code null}
	 * @return the ACP {@code RequestPermissionResponse}; never fails
	 */
	public CompletableFuture<JsonNode> request(JsonNode request, CompletionStage<Void> cancelled) {
		JsonNode req = request != null ? request : MissingNode.getInstance();
		JsonNode toolCall = req.path("toolCall");
		String sessionId = req.path("sessionId").asText("");
		String toolCallId = toolCall.path("toolCallId").isTextual() ? toolCall.path("toolCallId").textValue() : "";
		// The request's own kind, else the kind of the tool call it refers to in
		// the request's session (Kiro V3 sends none on the request), else other.
		ToolKind known = null;
		if (PermissionPolicies.toolKindOf(toolCall.get("kind")) == null && !toolCallId.isEmpty() && kinds != null) {
			known = kinds.resolveKind(sessionId, toolCallId);
		}
		ToolKind toolKind = PermissionPolicies.resolveKind(toolCall.get("kind"), known);
		List<PermissionOptionView> options = optionsOf(req.get("options"));
		PermissionView.Consent consent = consentOf(req.get("_meta"));
		List<ToolContentView.Diff> diffs = diffsOf(toolCall.get("content"));
		String title = toolCall.path("title").isTextual() ? toolCall.path("title").textValue()
				: toolCall.path("toolCallId").isTextual() ? toolCallId : "Permission request";

		Entry entry = new Entry();
		if (cancelled != null) {
			// Before the entry is registered: a turn that is already cancelled must not
			// show a request at all, and one cancelled later finds the entry registered.
			cancelled.whenComplete((done, failure) -> onCancelled(entry));
		}
		synchronized (lock) {
			// Deciding and registering under one lock: a rule recorded by a concurrent
			// setPolicy is either seen here, or this request is pending before it.
			String auto = PermissionPolicies.decide(policy, sessionId, PolicySubject.of(toolKind, consent), options);
			if (auto != null) {
				return CompletableFuture.completedFuture(selected(auto));
			}
			if (entry.cancelled || disposed) {
				return CompletableFuture.completedFuture(cancelledResponse());
			}
			String requestId = "perm-" + (++counter);
			entry.record = new PendingPermission(requestId, sessionId, toolCallId, toolKind, title, options,
					!diffs.isEmpty(), consent, diffs, System.currentTimeMillis());
			entries.put(requestId, entry);
		}
		fireChanged();
		return entry.future;
	}

	private void onCancelled(Entry entry) {
		synchronized (lock) {
			entry.cancelled = true;
			if (entry.record == null || entries.get(entry.record.requestId()) != entry) {
				return;
			}
			entries.remove(entry.record.requestId());
		}
		finish(entry, cancelledResponse(), PermissionResolution.Outcome.CANCELLED, null);
	}

	// ---------------------------------------------------------------------
	// Resolution
	// ---------------------------------------------------------------------

	/**
	 * Resolve a pending request with one of the option ids the agent supplied.
	 *
	 * @throws IllegalArgumentException when the request is unknown or the option id is not offered
	 */
	public void resolve(String requestId, String optionId) {
		Entry entry;
		synchronized (lock) {
			entry = entries.get(requestId);
			if (entry == null) {
				throw new IllegalArgumentException("Unknown permission request: " + requestId);
			}
			if (optionId == null || entry.record.options().stream().noneMatch(o -> optionId.equals(o.optionId()))) {
				throw new IllegalArgumentException(
						"Option \"" + optionId + "\" is not offered by request " + requestId);
			}
			entries.remove(requestId);
		}
		PolicyDecision decision = PermissionPolicies.decisionOfOption(entry.record.options(), optionId);
		finish(entry, selected(optionId),
				PermissionResolution.Outcome.of(decision != null ? decision : PolicyDecision.ALLOW), optionId);
	}

	/**
	 * Resolve a pending request by decision, picking the agent's
	 * {@code allow_once} (else {@code allow_always}) / {@code reject_once} (else
	 * {@code reject_always}) option.
	 *
	 * @return the chosen option id, or {@code null} when the request is unknown or offers no
	 *         matching option (the request stays pending)
	 */
	public String decideRequest(String requestId, PolicyDecision decision) {
		Entry entry;
		String optionId;
		synchronized (lock) {
			entry = entries.get(requestId);
			if (entry == null) {
				return null;
			}
			optionId = PermissionPolicies.selectOption(entry.record.options(), decision);
			if (optionId == null) {
				return null;
			}
			entries.remove(requestId);
		}
		finish(entry, selected(optionId), PermissionResolution.Outcome.of(decision), optionId);
		return optionId;
	}

	/** {@link #setPolicy(String, PolicyDecision, PermissionScope)} for the request's tool kind. */
	public void setPolicy(String requestId, PolicyDecision decision) {
		setPolicy(requestId, decision, PermissionScope.KIND);
	}

	/**
	 * Remember {@code decision} for the rest of the session, for the request's
	 * tool kind, its consent capability or its exact consent resource, and
	 * answer the request accordingly.
	 *
	 * @param scope {@code null} means the tool kind
	 * @throws IllegalArgumentException when the request is unknown or has nothing to key
	 *                                  {@code scope} on
	 */
	public void setPolicy(String requestId, PolicyDecision decision, PermissionScope scope) {
		PermissionScope by = scope != null ? scope : PermissionScope.KIND;
		Entry entry;
		String optionId;
		synchronized (lock) {
			entry = entries.get(requestId);
			if (entry == null) {
				throw new IllegalArgumentException("Unknown permission request: " + requestId);
			}
			String key = PermissionPolicies.policyKey(by, subjectOf(entry.record));
			if (key == null) {
				throw new IllegalArgumentException("Permission request " + requestId + " has no "
						+ scopeName(by) + " to remember");
			}
			policy.set(entry.record.sessionId(), key, decision);
			// As in decideRequest: without a matching option the rule is kept and the
			// request stays pending.
			optionId = PermissionPolicies.selectOption(entry.record.options(), decision);
			if (optionId == null) {
				return;
			}
			entries.remove(requestId);
		}
		finish(entry, selected(optionId), PermissionResolution.Outcome.of(decision), optionId);
	}

	/** Forget the remembered policies of one session. */
	public void clearPolicies(String sessionId) {
		synchronized (lock) {
			policy.clear(sessionId);
		}
	}

	/** Forget the remembered policies of every session. */
	public void clearPolicies() {
		synchronized (lock) {
			policy.clear();
		}
	}

	/** Answer every pending request of {@code sessionId} with {@code cancelled}. */
	public void cancelAll(String sessionId) {
		cancelMatching(sessionId, false);
	}

	/** Answer every pending request with {@code cancelled}. */
	public void cancelAll() {
		cancelMatching(null, true);
	}

	private void cancelMatching(String sessionId, boolean all) {
		List<Entry> taken = new ArrayList<>();
		synchronized (lock) {
			entries.values().removeIf(entry -> {
				if (all || entry.record.sessionId().equals(sessionId)) {
					taken.add(entry);
					return true;
				}
				return false;
			});
		}
		for (Entry entry : taken) {
			finish(entry, cancelledResponse(), PermissionResolution.Outcome.CANCELLED, null);
		}
	}

	// ---------------------------------------------------------------------
	// Queries
	// ---------------------------------------------------------------------

	/** Pending requests as chat page view models, oldest first. */
	public List<PermissionView> pending() {
		synchronized (lock) {
			return entries.values().stream().map(entry -> toView(entry.record)).toList();
		}
	}

	/** The ids of the pending requests, oldest first. */
	public List<String> pendingIds() {
		synchronized (lock) {
			return List.copyOf(entries.keySet());
		}
	}

	/** @return the pending request, or {@code null} */
	public PermissionView get(String requestId) {
		PendingPermission record = getEntry(requestId);
		return record != null ? toView(record) : null;
	}

	/** Full pending record including session id and diff contents, or {@code null}. */
	public PendingPermission getEntry(String requestId) {
		synchronized (lock) {
			Entry entry = entries.get(requestId);
			return entry != null ? entry.record : null;
		}
	}

	/** @return the oldest pending request of the tool call, or {@code null} */
	public PermissionView pendingForToolCall(String toolCallId) {
		synchronized (lock) {
			for (Entry entry : entries.values()) {
				if (entry.record.toolCallId().equals(toolCallId)) {
					return toView(entry.record);
				}
			}
			return null;
		}
	}

	/** First pending request whose tool kind is {@code kind} (e.g. {@code switch_mode}), or {@code null}. */
	public PermissionView findByKind(ToolKind kind) {
		synchronized (lock) {
			for (Entry entry : entries.values()) {
				if (entry.record.toolKind() == kind) {
					return toView(entry.record);
				}
			}
			return null;
		}
	}

	public int size() {
		synchronized (lock) {
			return entries.size();
		}
	}

	/**
	 * Answers everything pending with {@code cancelled} and stops telling
	 * listeners; later requests are answered {@code cancelled} at once.
	 */
	@Override
	public void close() {
		synchronized (lock) {
			if (disposed) {
				return;
			}
			disposed = true;
		}
		cancelAll();
	}

	// ---------------------------------------------------------------------
	// Internals
	// ---------------------------------------------------------------------

	/** The entry has already left {@link #entries}, so exactly one caller gets here with it. */
	private void finish(Entry entry, JsonNode response, PermissionResolution.Outcome outcome, String optionId) {
		boolean silent;
		synchronized (lock) {
			silent = disposed;
		}
		if (!silent) {
			PendingPermission record = entry.record;
			PermissionResolution resolution = new PermissionResolution(record.requestId(), record.sessionId(),
					record.toolCallId(), record.toolKind(), outcome, optionId);
			for (Consumer<PermissionResolution> listener : resolveListeners) {
				try {
					listener.accept(resolution);
				} catch (RuntimeException e) {
					logger.error("A permission resolution listener failed", e);
				}
			}
		}
		fireChanged();
		entry.future.complete(response);
	}

	private void fireChanged() {
		synchronized (lock) {
			if (disposed) {
				return;
			}
		}
		for (Runnable listener : changeListeners) {
			try {
				listener.run();
			} catch (RuntimeException e) {
				logger.error("A permission change listener failed", e);
			}
		}
	}

	private static ObjectNode selected(String optionId) {
		ObjectNode response = JSON.objectNode();
		response.putObject("outcome").put("outcome", "selected").put("optionId", optionId);
		return response;
	}

	private static ObjectNode cancelledResponse() {
		ObjectNode response = JSON.objectNode();
		response.putObject("outcome").put("outcome", "cancelled");
		return response;
	}

	private static PolicySubject subjectOf(PendingPermission record) {
		return PolicySubject.of(record.toolKind(), record.consent());
	}

	private static PermissionView toView(PendingPermission record) {
		return new PermissionView(record.requestId(), record.toolCallId(), record.title(), record.toolKind(),
				record.options(), record.hasDiff(), record.consent(),
				PermissionPolicies.offeredPolicies(subjectOf(record)));
	}

	/** A consent object without any usable field is no consent context. */
	private static PermissionView.Consent consentOf(JsonNode meta) {
		PermissionView.Consent consent = PermissionPolicies.consentOf(meta);
		if (consent == null
				|| (consent.capability() == null && consent.resource() == null && consent.persistableConsent() == null)) {
			return null;
		}
		return consent;
	}

	/** The agent's options. One without an id could never be selected and is left out. */
	private static List<PermissionOptionView> optionsOf(JsonNode options) {
		List<PermissionOptionView> out = new ArrayList<>();
		if (options == null || !options.isArray()) {
			return out;
		}
		for (JsonNode option : options) {
			JsonNode id = option.get("optionId");
			if (id == null || !id.isTextual()) {
				continue;
			}
			out.add(new PermissionOptionView(id.textValue(), option.path("name").asText(""),
					optionKindOf(option.path("kind").asText(""))));
		}
		return out;
	}

	/** @return {@code null} for a kind ACP does not define: such an option is offered but implements no decision */
	private static PermissionOptionView.Kind optionKindOf(String kind) {
		switch (kind) {
		case "allow_once":
			return PermissionOptionView.Kind.ALLOW_ONCE;
		case "allow_always":
			return PermissionOptionView.Kind.ALLOW_ALWAYS;
		case "reject_once":
			return PermissionOptionView.Kind.REJECT_ONCE;
		case "reject_always":
			return PermissionOptionView.Kind.REJECT_ALWAYS;
		default:
			return null;
		}
	}

	private static List<ToolContentView.Diff> diffsOf(JsonNode content) {
		List<ToolContentView.Diff> diffs = new ArrayList<>();
		if (content == null || !content.isArray()) {
			return diffs;
		}
		for (JsonNode item : content) {
			if ("diff".equals(item.path("type").asText(null)) && item.path("path").isTextual()) {
				JsonNode oldText = item.get("oldText");
				JsonNode newText = item.get("newText");
				diffs.add(new ToolContentView.Diff(item.get("path").textValue(),
						oldText != null && oldText.isTextual() ? oldText.textValue() : null,
						newText != null && newText.isTextual() ? newText.textValue() : ""));
			}
		}
		return diffs;
	}

	/** The scope as the reference's messages spell it. */
	private static String scopeName(PermissionScope scope) {
		switch (scope) {
		case CAPABILITY:
			return "capability";
		case RESOURCE:
			return "resource";
		default:
			return "kind";
		}
	}
}
