package io.github.liaoalia.kiroacp.core.session.commands;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import io.github.liaoalia.kiroacp.core.acp.api.AgentInfo;
import io.github.liaoalia.kiroacp.core.acp.api.KiroAgent;
import io.github.liaoalia.kiroacp.core.host.Logger;
import io.github.liaoalia.kiroacp.core.host.Subscription;
import io.github.liaoalia.kiroacp.core.model.SessionUsage;
import io.github.liaoalia.kiroacp.core.model.ToWebview;
import io.github.liaoalia.kiroacp.core.pure.slash.Slash;
import io.github.liaoalia.kiroacp.core.pure.usage.Usage;
import io.github.liaoalia.kiroacp.core.session.ChatController;
import io.github.liaoalia.kiroacp.core.session.ChatHooks;

/**
 * Context-window and token usage of each session, from whatever the agent
 * reports (reference D26; the numbers are folded by {@link Usage}).
 *
 * <p>Only changes are posted ({@code usage}), the page's {@code state}
 * carries the current value, and a session starts with nothing. Nothing is
 * estimated here. When a turn ends and the session still has no percentage,
 * the advertised {@code _kiro/session/context} is asked for it; an agent
 * that rejects the method is not asked again until it is restarted. The
 * first reply per session and one example of every
 * {@code session_info_update} kind are logged, so the real shapes can be
 * recorded.
 *
 * <p>Controller thread only.
 */
public final class UsageTracker implements AutoCloseable {

	private static final int LOGGED_CHARS = 2000;

	private final ChatController controller;
	private final Logger logger;
	private final List<Subscription> subscriptions = new ArrayList<>();
	private final Map<String, SessionUsage> usage = new HashMap<>();
	private final Map<String, Set<String>> loggedInfoKinds = new HashMap<>();
	private final Set<String> contextReplyLogged = new HashSet<>();
	/** The agent process that rejected the poll. */
	private KiroAgent pollUnsupported;

	private UsageTracker(ChatController controller) {
		this.controller = controller;
		this.logger = controller.host().logger();
	}

	public static UsageTracker install(ChatController controller) {
		UsageTracker tracker = new UsageTracker(controller);
		ChatHooks hooks = controller.hooks();
		hooks.setUsageSource(tracker::usage);
		tracker.subscriptions.add(() -> hooks.setUsageSource(null));
		tracker.subscriptions.add(hooks.addListener(new ChatHooks.SessionListener() {
			@Override
			public void update(String sessionId, JsonNode update, boolean replay) {
				tracker.update(sessionId, update, replay);
			}

			@Override
			public void afterTurn(String sessionId, String stopReason) {
				tracker.pollContextUsage(sessionId);
			}

			@Override
			public void sessionClosing(String sessionId) {
				tracker.usage.remove(sessionId);
				tracker.loggedInfoKinds.remove(sessionId);
				tracker.contextReplyLogged.remove(sessionId);
			}
		}));
		return tracker;
	}

	@Override
	public void close() {
		subscriptions.forEach(Subscription::close);
		subscriptions.clear();
	}

	/** What is known of a session's usage; {@code null} while the agent reported nothing. */
	public SessionUsage usage(String sessionId) {
		return usage.get(sessionId);
	}

	private void update(String sessionId, JsonNode update, boolean replay) {
		SessionUsage next = Usage.applyUsageUpdate(usage.getOrDefault(sessionId, SessionUsage.EMPTY), update);
		if (next != null) {
			set(sessionId, next);
		}
		if (!replay && "session_info_update".equals(update.path("sessionUpdate").asText(""))) {
			// One example of every kind per session, to record the real shapes (usage, turn
			// metering, ...): the documentation names the kinds, not the fields.
			JsonNode kiro = Usage.kiroMeta(update);
			String kind = kiro != null && kiro.path("kind").isTextual() ? kiro.get("kind").textValue()
					: update.path("title").isTextual() ? "title" : "untyped";
			if (loggedInfoKinds.computeIfAbsent(sessionId, id -> new HashSet<>()).add(kind)) {
				logger.info("session " + sessionId + " session_info_update (" + kind + "): " + truncate(update.toString()));
			}
		}
	}

	private void set(String sessionId, SessionUsage next) {
		usage.put(sessionId, next);
		controller.post(sessionId, new ToWebview.Usage(next));
	}

	/**
	 * After a turn, ask {@code _kiro/session/context} for the context-window
	 * numbers when the agent never reported a percentage itself.
	 */
	private void pollContextUsage(String sessionId) {
		KiroAgent agent = controller.agent();
		AgentInfo info = controller.agentInfo();
		String method = info != null ? Slash.findExtension(info.kiroExtensionMethods(), Slash.ExtCommand.CONTEXT) : null;
		if (agent == null || method == null || agent == pollUnsupported || controller.transcript(sessionId) == null) {
			return;
		}
		SessionUsage known = usage.get(sessionId);
		if (known != null && known.contextPercent() != null) {
			return;
		}
		CompletableFuture<JsonNode> asked;
		try {
			asked = agent.callExtension(method, JsonNodeFactory.instance.objectNode().put("sessionId", sessionId));
		} catch (RuntimeException e) {
			asked = CompletableFuture.failedFuture(e);
		}
		asked.whenComplete((result, error) -> controller.host().scheduler().execute(() -> {
			if (error != null) {
				pollUnsupported = agent;
				logger.info(method + " after the turn failed: " + message(error) + "; not polling again");
				return;
			}
			// Closed meanwhile, or the session of an agent that is gone.
			if (controller.transcript(sessionId) == null || controller.agent() != agent) {
				return;
			}
			if (contextReplyLogged.add(sessionId)) {
				logger.info("session " + sessionId + " " + method + " reply: " + truncate(String.valueOf(result)));
			}
			SessionUsage next = Usage.applyContextResult(usage.getOrDefault(sessionId, SessionUsage.EMPTY), result);
			if (next != null) {
				set(sessionId, next);
			}
		}));
	}

	private static String truncate(String text) {
		return text.length() <= LOGGED_CHARS ? text : text.substring(0, LOGGED_CHARS) + "…";
	}

	private static String message(Throwable error) {
		Throwable cause = error;
		while (cause instanceof CompletionException && cause.getCause() != null) {
			cause = cause.getCause();
		}
		return cause.getMessage() != null && !cause.getMessage().isEmpty() ? cause.getMessage() : cause.toString();
	}
}
