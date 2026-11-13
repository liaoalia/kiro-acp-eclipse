package io.github.liaoalia.kiroacp.core.session;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.liaoalia.kiroacp.core.acp.api.AgentInfo;
import io.github.liaoalia.kiroacp.core.acp.api.AgentProcessOptions;
import io.github.liaoalia.kiroacp.core.acp.api.ConnectionStateChange;
import io.github.liaoalia.kiroacp.core.acp.api.KiroAgent;
import io.github.liaoalia.kiroacp.core.host.Logger;
import io.github.liaoalia.kiroacp.core.host.Settings;
import io.github.liaoalia.kiroacp.core.host.Subscription;
import io.github.liaoalia.kiroacp.core.model.AuthMethodView;
import io.github.liaoalia.kiroacp.core.model.ConnectionStatus;
import io.github.liaoalia.kiroacp.core.model.ConnectionView;
import io.github.liaoalia.kiroacp.core.model.NoticeView.Severity;
import io.github.liaoalia.kiroacp.core.model.ToWebview;

/**
 * The agent process as the chat sees it: started when first needed, replaced
 * on restart, and reported to the pages as {@code connection} messages.
 * Controller thread only.
 */
final class AgentLifecycle {

	/** One notice per distinct kiro-cli stderr error per this interval. */
	static final long STDERR_NOTICE_INTERVAL_MS = 60_000;

	private final ChatController c;
	private final AgentFactory agents;
	/** What the agent process logs to; listens to its stderr on the way. */
	final Logger agentLog;
	private final List<Subscription> agentSubscriptions = new ArrayList<>();
	private CompletableFuture<Void> starting;
	/** When a given kiro-cli stderr error was last turned into a notice. */
	private final Map<String, Long> stderrNoticedAt = new HashMap<>();

	AgentLifecycle(ChatController controller, AgentFactory agents) {
		this.c = controller;
		this.agents = agents != null ? agents : AgentFactory.DEFAULT;
		this.agentLog = new AgentLog(controller.logger, line -> {
			// On the thread that reads the agent's stderr: only what becomes a notice crosses over.
			String detail = AgentLog.errorDetail(line);
			if (detail != null) {
				controller.async.later(() -> noteAgentError(detail));
			}
		});
	}

	/** The agent's listeners are removed; what it still reports reaches nobody. */
	void unsubscribe() {
		agentSubscriptions.forEach(Subscription::close);
		agentSubscriptions.clear();
	}

	boolean agentDown() {
		return c.agent == null || isDown(c.agent.status());
	}

	private static boolean isDown(ConnectionStatus status) {
		return status == ConnectionStatus.STOPPED || status == ConnectionStatus.ERROR;
	}

	CompletableFuture<Void> ensureStarted() {
		return ensureStarted(true);
	}

	/**
	 * @param openSessions whether the new process opens a first session and gives the pages one;
	 *                     not after a sign-in, which hands them their own sessions back
	 *                     ({@link SessionRestore}). A start already under way is joined as it is
	 */
	private CompletableFuture<Void> ensureStarted(boolean openSessions) {
		if (c.closed) {
			return Async.done(null);
		}
		if (starting != null) {
			return starting;
		}
		if (c.agent != null && !isDown(c.agent.status())) {
			return Async.done(null);
		}
		CompletableFuture<Void> run = new CompletableFuture<>();
		starting = run;
		CompletableFuture<Void> started;
		try {
			started = startAgent(openSessions);
		} catch (RuntimeException e) {
			started = CompletableFuture.failedFuture(e);
		}
		started.whenComplete((done, error) -> {
			if (starting == run) {
				starting = null;
			}
			if (error != null) {
				c.logger.error("agent start failed: " + Async.message(error), Async.unwrap(error));
			}
			run.complete(null);
		});
		return run;
	}

	private CompletableFuture<Void> startAgent(boolean openSessions) {
		Settings settings = c.host.settings();
		String command = settings.cliPath();
		List<String> configured = settings.cliArgs();
		List<String> args = arguments(configured);
		Path cwd = c.host.workspace().defaultCwd();
		c.logger.info("spawning: " + command + " " + String.join(" ", args) + " (cwd " + cwd + ")");
		KiroAgent next = agents.create(
				new AgentProcessOptions(command, args, cwd, System.getenv(), agentLog, c.host.clientVersion()), c.services);
		// Sessions belong to the process; a new process starts without any, and the old
		// capabilities and auth methods must not leak into its `starting` view.
		c.roster.endAllSessions();
		return teardownAgent().thenCompose(gone -> {
			if (c.closed) {
				return Async.done(null);
			}
			c.agentInfo = null;
			c.agent = next;
			agentSubscriptions.add(next.onConnectionChange(change -> c.async.later(() -> {
				if (c.agent == next) {
					onConnectionChange(change);
				}
			})));
			agentSubscriptions.add(next.onSessionEvent(event -> {
				// On the reader thread, where a permission request about this tool call is answered next.
				c.kinds.record(event);
				c.async.later(() -> {
					if (c.agent == next) {
						c.events.handle(event);
					}
				});
			}));
			setConnection(new ConnectionView(ConnectionStatus.STARTING, null, null, "Starting " + baseName(command) + "\u2026",
					null, null, null));
			return c.async.ui(next.start()).handle((info, error) -> started(next, info, error, openSessions))
					.thenCompose(flow -> flow);
		});
	}

	private CompletableFuture<Void> started(KiroAgent next, AgentInfo info, Throwable error, boolean openSessions) {
		if (c.agent != next) {
			// Replaced or shut down while it was starting: whoever did that reports for the new one.
			return Async.done(null);
		}
		if (error != null) {
			String message = Async.message(error);
			c.logger.error("agent start failed: " + message);
			setConnection(new ConnectionView(ConnectionStatus.ERROR, null, null, message, null, null, null));
			c.tell(null, Severity.ERROR, "Could not start Kiro CLI: " + message);
			return Async.done(null);
		}
		c.agentInfo = info;
		setConnection(connectionFromAgent(ConnectionStatus.READY, null));
		return openSessions ? openFirstSessions() : Async.done(null);
	}

	/** A first session when none exists, and a session for every page still without one. */
	CompletableFuture<Void> openFirstSessions() {
		CompletableFuture<Void> first = Async.done(null);
		if (c.sessions.isEmpty()) {
			first = c.roster.createSession(c.roster.groupFor(null)).thenAccept(session -> {
				if (session != null) {
					c.focus(session.id());
				}
			});
		}
		return first.thenCompose(done -> c.roster.assignPendingPages());
	}

	/**
	 * The arguments the CLI is started with. As in the reference the setting
	 * holds extra arguments and {@code acp} always comes first. Until 0.2.0 the
	 * setting was the whole list, so one that already begins with {@code acp}
	 * is taken as it is.
	 */
	static List<String> arguments(List<String> configured) {
		List<String> args = new ArrayList<>();
		if (configured == null || configured.isEmpty() || !"acp".equals(configured.get(0))) {
			args.add("acp");
		}
		if (configured != null) {
			args.addAll(configured);
		}
		return List.copyOf(args);
	}

	private CompletableFuture<Void> teardownAgent() {
		unsubscribe();
		KiroAgent old = c.agent;
		c.agent = null;
		if (old == null) {
			return Async.done(null);
		}
		return c.async.ui(old.stop()).handle((done, error) -> {
			// Its sessions were ended before it was told to stop, and until it was gone it
			// could still ask for a command. Whatever that started has no owner.
			try {
				c.terminal.disposeAll();
			} catch (RuntimeException e) {
				c.logger.warn("could not end the terminals of the stopped agent: " + Async.message(e));
			}
			return null;
		});
	}

	/**
	 * Spawn a fresh agent process. With {@code restoreSessions} (a sign-in)
	 * every session with a conversation is loaded back once the new process
	 * is ready, so signing in loses neither the conversation a page shows nor
	 * the others in its list (reference D25).
	 */
	CompletableFuture<Void> doRestart(boolean restoreSessions) {
		List<SessionRestore.Entry> plan = restoreSessions ? c.restore.plan() : List.of();
		c.roster.endAllSessions();
		return teardownAgent().thenCompose(gone -> {
			setConnection(ConnectionView.of(ConnectionStatus.STOPPED));
			// A start that was under way belongs to the agent just torn down: let it settle, then start anew.
			CompletableFuture<Void> stale = starting != null ? starting : Async.done(null);
			// With sessions to bring back the new process opens none of its own first: it
			// would linger as an empty "New session" in the focused page.
			return stale.thenCompose(settled -> ensureStarted(plan.isEmpty()));
		}).thenCompose(started -> plan.isEmpty() ? Async.done(null) : c.restore.run(plan));
	}

	ConnectionView connectionFromAgent(ConnectionStatus status, String message) {
		AgentInfo info = c.agentInfo;
		String name = null;
		String version = null;
		List<AuthMethodView> methods = null;
		Boolean canBrowse = null;
		Boolean acceptsImages = null;
		if (info != null) {
			JsonNode about = info.agentInfo();
			if (about != null && about.isObject()) {
				name = about.path("title").isTextual() ? about.path("title").textValue() : about.path("name").asText(null);
				version = about.path("version").asText(null);
			}
			if (!info.authMethods().isEmpty()) {
				methods = info.authMethods().stream().map(AgentLifecycle::toAuthMethodView).toList();
			}
			canBrowse = info.canLoadSessions() && info.canListSessions();
			acceptsImages = info.acceptsImages();
		}
		return new ConnectionView(status, name, version, message == null || message.isEmpty() ? null : message, methods,
				canBrowse, acceptsImages);
	}

	private static AuthMethodView toAuthMethodView(JsonNode method) {
		return new AuthMethodView(method.path("id").asText(""), method.path("name").asText(""),
				method.path("description").asText(null), "terminal".equals(method.path("type").asText(null)));
	}

	private void onConnectionChange(ConnectionStateChange change) {
		if (change.agent() != null) {
			c.agentInfo = change.agent();
		}
		if (isDown(change.status())) {
			// The sessions stay visible (status `error`) until the agent restarts.
			for (ChatSession session : List.copyOf(c.sessions.values())) {
				c.permissions.cancelAll(session.id());
				if (session.turnActive) {
					c.turns.endTurn(session, "error");
				}
			}
			killTerminals("the agent is gone");
		}
		setConnection(connectionFromAgent(change.status(), change.message()));
		if (change.status() == ConnectionStatus.ERROR && change.message() != null && !change.message().isEmpty()) {
			c.tell(null, Severity.ERROR, change.message());
		}
		c.listing.schedule();
	}

	/**
	 * Ends every command the agent started: they are children of this
	 * process, not of the agent, and nothing else ends them once the agent
	 * cannot. The terminals stay readable, for the tool cards of the sessions
	 * that are still shown.
	 */
	void killTerminals(String why) {
		CompletableFuture<List<String>> killing;
		try {
			killing = c.terminal.killAll();
		} catch (RuntimeException e) {
			killing = CompletableFuture.failedFuture(e);
		}
		killing.whenComplete((killed, error) -> {
			// On whichever thread did the killing: only the log is touched.
			if (error != null) {
				c.logger.warn(why + ": could not kill the running terminals: " + Async.message(error));
			} else if (killed != null && !killed.isEmpty()) {
				c.logger.info(why + ": killed running terminal(s) " + String.join(", ", killed));
			}
		});
	}

	void setConnection(ConnectionView view) {
		c.connection = view;
		c.broadcast(new ToWebview.Connection(view));
		c.listing.schedule();
	}

	/**
	 * kiro-cli logs its backend failures only to stderr while the turn may
	 * stay open: the error is shown in every session whose turn is running,
	 * so the user knows why nothing arrives (reference D27).
	 */
	private void noteAgentError(String detail) {
		long now = c.host.clock().getAsLong();
		Long last = stderrNoticedAt.get(detail);
		if (last != null && now - last < STDERR_NOTICE_INTERVAL_MS) {
			return;
		}
		stderrNoticedAt.put(detail, now);
		for (ChatSession session : List.copyOf(c.sessions.values())) {
			if (session.turnActive) {
				c.tell(session, Severity.WARNING, "kiro-cli reported " + detail
						+ " while talking to its backend. If the turn does not finish, press Esc and send the message again.");
			}
		}
	}

	private static String baseName(String command) {
		String trimmed = command == null ? "" : command.trim();
		int slash = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
		return slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
	}
}
