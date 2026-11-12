package io.github.liaoalia.kiroacp.core.acp.impl;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.liaoalia.kiroacp.core.acp.api.AcpException;
import io.github.liaoalia.kiroacp.core.acp.api.AgentInfo;
import io.github.liaoalia.kiroacp.core.acp.api.AgentProcessOptions;
import io.github.liaoalia.kiroacp.core.acp.api.ClientServices;
import io.github.liaoalia.kiroacp.core.acp.api.ConnectionStateChange;
import io.github.liaoalia.kiroacp.core.acp.api.KiroAgent;
import io.github.liaoalia.kiroacp.core.acp.api.ModelState;
import io.github.liaoalia.kiroacp.core.acp.api.SessionEvent;
import io.github.liaoalia.kiroacp.core.acp.api.SessionListEntry;
import io.github.liaoalia.kiroacp.core.acp.api.SessionListPage;
import io.github.liaoalia.kiroacp.core.acp.api.SessionState;
import io.github.liaoalia.kiroacp.core.acp.process.AgentProcess;
import io.github.liaoalia.kiroacp.core.acp.process.CliPathResolver;
import io.github.liaoalia.kiroacp.core.acp.rpc.ConnectionClosedException;
import io.github.liaoalia.kiroacp.core.acp.rpc.JsonRpcConnection;
import io.github.liaoalia.kiroacp.core.acp.rpc.JsonRpcConnection.Responder;
import io.github.liaoalia.kiroacp.core.acp.rpc.JsonRpcException;
import io.github.liaoalia.kiroacp.core.host.Logger;
import io.github.liaoalia.kiroacp.core.host.Subscription;
import io.github.liaoalia.kiroacp.core.model.ConnectionStatus;

/**
 * The ACP client over one {@code kiro-cli acp} process at a time.
 *
 * <p><b>Threads.</b> Everything the agent causes is handled on the reader
 * thread of the connection, in wire order: session updates, the events that
 * follow a response, and the completion of the future the caller holds, which
 * comes last. {@code TurnStarted} is delivered on the calling thread, before
 * the prompt is written, because nothing on the wire stands for it. Status
 * changes come from whichever thread caused them (a caller, the reader, the
 * one that sees the process end) and are delivered one at a time in the order
 * they happened. Listeners are never called with a lock held.
 */
final class KiroAgentImpl implements KiroAgent {

	private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
	private static final String SESSION_UPDATE = "session/update";

	/**
	 * The update kinds delivered as {@link SessionEvent.Update}. Any other kind
	 * is surfaced as an {@link SessionEvent.ExtensionNotification} with the whole
	 * notification, as the reference does, so consumers of updates see a closed
	 * set and nothing is dropped.
	 */
	private static final Set<String> KNOWN_SESSION_UPDATES = Set.of("user_message_chunk", "agent_message_chunk",
			"agent_thought_chunk", "tool_call", "tool_call_update", "plan", "available_commands_update",
			"current_mode_update", "config_option_update", "session_info_update", "usage_update", "notice",
			"compaction_update", "compaction_summary_chunk");

	/** How long the output of a process that has exited may take to end before the exit is reported anyway. */
	private static final long DRAIN_SECONDS = 2;
	/** How long a process whose output has ended may take to exit before it is terminated. */
	private static final long EXIT_SECONDS = 1;
	private static final int UNKNOWN_REQUEST_LOG_LIMIT = 500;

	private final AgentProcessOptions options;
	private final ClientServices services;
	private final Logger logger;
	private final List<Consumer<ConnectionStateChange>> connectionListeners = new CopyOnWriteArrayList<>();
	private final List<Consumer<SessionEvent>> sessionListeners = new CopyOnWriteArrayList<>();

	/** Guards every field below and the mutable state of each {@link Run}. */
	private final Object lock = new Object();
	private volatile ConnectionStatus status = ConnectionStatus.STOPPED;
	private volatile AgentInfo agent;
	private volatile String resolvedCommand;
	private Run run;
	private CompletableFuture<AgentInfo> starting;
	private CompletableFuture<Void> stopping;
	/** Counts stops, so a start that was spawning meanwhile knows it has been overtaken. */
	private int stops;
	private final Queue<ConnectionStateChange> changes = new ArrayDeque<>();
	private boolean deliveringChanges;

	KiroAgentImpl(AgentProcessOptions options, ClientServices services) {
		this.options = options;
		this.services = services;
		this.logger = options.logger() == null ? Logger.NONE : options.logger();
	}

	@Override
	public ConnectionStatus status() {
		return status;
	}

	@Override
	public AgentInfo agent() {
		return agent;
	}

	@Override
	public SessionState session(String sessionId) {
		synchronized (lock) {
			return run == null ? null : run.sessions.get(sessionId);
		}
	}

	@Override
	public Subscription onConnectionChange(Consumer<ConnectionStateChange> listener) {
		connectionListeners.add(listener);
		return () -> connectionListeners.remove(listener);
	}

	@Override
	public Subscription onSessionEvent(Consumer<SessionEvent> listener) {
		sessionListeners.add(listener);
		return () -> sessionListeners.remove(listener);
	}

	@Override
	public CompletableFuture<AgentInfo> start() {
		CompletableFuture<AgentInfo> result = new CompletableFuture<>();
		CompletableFuture<Void> before = null;
		int stopsSeen;
		synchronized (lock) {
			if (starting != null) {
				return starting;
			}
			if (stopping != null) {
				before = stopping;
			} else if (run != null) {
				if (!run.connection.isClosed()) {
					return CompletableFuture.completedFuture(agent);
				}
				// The process is on its way out; a new one starts once that is settled.
				before = run.ended;
			}
			if (before == null) {
				starting = result;
				enqueueStatus(ConnectionStatus.STARTING, null, null);
			}
			stopsSeen = stops;
		}
		if (before != null) {
			return before.handle((done, failure) -> null).thenCompose(done -> start());
		}
		deliverChanges();
		// Looking through the PATH and creating a process are too slow for a UI thread.
		Thread thread = new Thread(() -> launch(result, stopsSeen), "acp-start");
		thread.setDaemon(true);
		thread.start();
		return result;
	}

	private void launch(CompletableFuture<AgentInfo> result, int stopsSeen) {
		String command = options.command();
		AgentProcess process;
		try {
			command = CliPathResolver.resolve(options.command(), options.env());
			resolvedCommand = command;
			logger.info(("Starting agent: " + command + " " + String.join(" ", options.args())).trim());
			process = AgentProcess.start(command, options.args(), options.cwd(), options.env(), logger);
		} catch (IOException | RuntimeException e) {
			AcpException error = toAcp(e, null, "failed to spawn agent");
			synchronized (lock) {
				starting = null;
				if (stops == stopsSeen) {
					enqueueStatus(ConnectionStatus.ERROR, null, "Failed to start " + command + ": " + error.getMessage());
				}
			}
			deliverChanges();
			result.completeExceptionally(error);
			return;
		}

		Run started = new Run(process);
		boolean overtaken;
		synchronized (lock) {
			overtaken = stops != stopsSeen;
			if (overtaken) {
				starting = null;
			} else {
				run = started;
			}
		}
		if (overtaken) {
			process.terminate();
			result.completeExceptionally(new AcpException("agent stopped"));
			return;
		}
		started.open();
		started.connection.request("initialize", initializeParams())
				.whenComplete((response, failure) -> initialized(started, response, failure, result));
	}

	private ObjectNode initializeParams() {
		ObjectNode params = JSON.objectNode();
		params.put("protocolVersion", KiroAgents.PROTOCOL_VERSION);
		ObjectNode capabilities = params.putObject("clientCapabilities");
		capabilities.putObject("fs").put("readTextFile", true).put("writeTextFile", true);
		capabilities.put("terminal", true);
		capabilities.putObject("auth").put("terminal", true);
		ObjectNode meta = capabilities.putObject("_meta");
		meta.putObject("kiro").put("openExternalUrl", true);
		meta.put("terminal-auth", true);
		ObjectNode client = params.putObject("clientInfo");
		client.put("name", KiroAgents.CLIENT_NAME);
		client.put("title", KiroAgents.CLIENT_TITLE);
		client.put("version", options.clientVersion() == null ? "0.0.0" : options.clientVersion());
		return params;
	}

	private void initialized(Run started, JsonNode response, Throwable failure, CompletableFuture<AgentInfo> result) {
		if (failure == null) {
			AgentInfo info = AcpPayloads.agentInfo(response);
			boolean current;
			synchronized (lock) {
				starting = null;
				current = run == started;
				if (current) {
					agent = info;
					enqueueStatus(ConnectionStatus.READY, info, null);
				}
			}
			if (!current) {
				result.completeExceptionally(new AcpException("agent stopped"));
				return;
			}
			JsonNode about = info.agentInfo();
			logger.info(("Agent ready: " + about.path("name").asText("unknown") + " " + about.path("version").asText("")).trim()
					+ " (protocol " + info.protocolVersion() + ")");
			deliverChanges();
			result.complete(info);
			return;
		}
		AcpException error = toAcp(failure, started, "initialize failed");
		if (started.stopping) {
			synchronized (lock) {
				starting = null;
			}
			result.completeExceptionally(error);
			return;
		}
		logger.error("initialize failed: " + error.getMessage());
		stop().whenComplete((done, ignored) -> {
			synchronized (lock) {
				starting = null;
				enqueueStatus(ConnectionStatus.ERROR, null, "initialize failed: " + error.getMessage());
			}
			deliverChanges();
			result.completeExceptionally(error);
		});
	}

	@Override
	public CompletableFuture<Void> stop() {
		Run target;
		CompletableFuture<Void> result = new CompletableFuture<>();
		synchronized (lock) {
			if (stopping != null) {
				return stopping;
			}
			stops++;
			target = run;
			run = null;
			if (target == null) {
				if (status != ConnectionStatus.STOPPED) {
					enqueueStatus(ConnectionStatus.STOPPED, null, "agent stopped");
				}
			} else {
				target.stopping = true;
				stopping = result;
			}
		}
		if (target == null) {
			deliverChanges();
			return CompletableFuture.completedFuture(null);
		}
		// The connection is not closed here: the end of the process ends the reader, which fails
		// what is pending. Closing a pipe another thread is blocked reading can itself block.
		target.process.terminate();
		// After the kill, so that the agent does not get to end a turn as cancelled on the way
		// out: the hosts stop asking, and the pending prompt fails as stopped.
		target.abortAllPermissions();
		target.ended.whenComplete((done, failure) -> {
			synchronized (lock) {
				stopping = null;
			}
			result.complete(null);
		});
		return result;
	}

	/** The process has exited and its output has been read to the end (or was given up on). */
	private void ended(Run target, int exitCode) {
		try {
			target.connection.close();
			target.abortAllPermissions();
			String description = "kiro-cli exited with code " + exitCode;
			synchronized (lock) {
				if (run == target) {
					run = null;
				}
				if (target.stopping) {
					if (status != ConnectionStatus.STOPPED) {
						enqueueStatus(ConnectionStatus.STOPPED, null, "agent stopped");
					}
				} else if (exitCode == 0) {
					enqueueStatus(ConnectionStatus.STOPPED, null, description);
				} else {
					enqueueStatus(ConnectionStatus.ERROR, null, description);
				}
			}
			if (!target.stopping) {
				if (exitCode == 0) {
					logger.info(description);
				} else {
					logger.error(description);
				}
			}
			deliverChanges();
		} finally {
			target.ended.complete(null);
		}
	}

	@Override
	public CompletableFuture<SessionState> newSession(Path cwd, List<JsonNode> mcpServers, JsonNode meta) {
		Run target = current();
		if (target == null) {
			return notRunning();
		}
		String dir = cwd.toString();
		ObjectNode params = JSON.objectNode();
		params.put("cwd", dir);
		params.set("mcpServers", array(mcpServers));
		if (meta != null && !meta.isNull()) {
			params.set("_meta", meta);
		}
		return call(target, "session/new", params, response -> {
			SessionState state = AcpPayloads.sessionState(dir, response);
			if (state == null) {
				throw new AcpException("session/new returned no session id");
			}
			synchronized (lock) {
				target.sessions.put(state.sessionId(), state);
				target.lastSessionId = state.sessionId();
			}
			signedIn(target);
			emit(new SessionEvent.State(state));
			return state;
		}, error -> {
			AgentInfo info = agent;
			if (info != null && (!info.authMethods().isEmpty() || error.isAuthRequired())) {
				markAuthRequired(target, "session/new", error);
			}
		});
	}

	@Override
	public CompletableFuture<SessionListPage> listSessions(Path cwd, String cursor) {
		Run target = current();
		if (target == null) {
			return notRunning();
		}
		AgentInfo info = agent;
		if (info == null || !info.canListSessions()) {
			return failed(new AcpException("the agent did not advertise session/list"));
		}
		ObjectNode params = JSON.objectNode();
		if (cwd != null) {
			params.put("cwd", cwd.toString());
		}
		if (cursor != null) {
			params.put("cursor", cursor);
		}
		return call(target, "session/list", params, response -> {
			List<SessionListEntry> sessions = new ArrayList<>();
			for (JsonNode raw : response.path("sessions")) {
				SessionListEntry entry = AcpPayloads.sessionListEntry(raw);
				if (entry != null) {
					sessions.add(entry);
				}
			}
			JsonNode next = response.path("nextCursor");
			return new SessionListPage(sessions, next.isTextual() ? next.asText() : null);
		}, null);
	}

	@Override
	public CompletableFuture<SessionState> loadSession(String sessionId, Path cwd, List<JsonNode> mcpServers) {
		Run target = current();
		if (target == null) {
			return notRunning();
		}
		AgentInfo info = agent;
		if (info == null || !info.canLoadSessions()) {
			return failed(new AcpException("the agent did not advertise session/load"));
		}
		String dir = cwd.toString();
		SessionState placeholder = new SessionState(sessionId, dir, null, null, null, List.of(), null);
		SessionState previous;
		// Registered before the request is written, so that the history the agent replays is
		// routed to this session and flagged as replay while the load is in flight.
		synchronized (lock) {
			previous = target.sessions.put(sessionId, placeholder);
			target.loading.add(sessionId);
		}
		ObjectNode params = JSON.objectNode();
		params.put("sessionId", sessionId);
		params.put("cwd", dir);
		params.set("mcpServers", array(mcpServers));
		return call(target, "session/load", params, response -> {
			SessionState state;
			synchronized (lock) {
				target.loading.remove(sessionId);
				SessionState now = target.sessions.getOrDefault(sessionId, placeholder);
				JsonNode modes = AcpPayloads.objectOrNull(response.path("modes"));
				JsonNode options = response.path("configOptions");
				ModelState models = AcpPayloads.modelState(response);
				JsonNode meta = AcpPayloads.objectOrNull(response.path("_meta"));
				state = new SessionState(sessionId, dir, modes != null ? modes : now.modes(),
						options.isArray() ? AcpPayloads.elements(options) : now.configOptions(),
						models != null ? models : now.models(), now.availableCommands(), meta != null ? meta : now.meta());
				target.sessions.put(sessionId, state);
				target.lastSessionId = sessionId;
			}
			signedIn(target);
			emit(new SessionEvent.State(state));
			return state;
		}, error -> {
			synchronized (lock) {
				target.loading.remove(sessionId);
				if (previous != null) {
					target.sessions.put(sessionId, previous);
				} else {
					target.sessions.remove(sessionId);
				}
			}
			if (error.isAuthRequired()) {
				markAuthRequired(target, "session/load", error);
			}
		});
	}

	@Override
	public CompletableFuture<JsonNode> prompt(String sessionId, List<JsonNode> content) {
		Run target = current();
		if (target == null) {
			return notRunning();
		}
		emit(new SessionEvent.TurnStarted(sessionId));
		ObjectNode params = JSON.objectNode();
		params.put("sessionId", sessionId);
		params.set("prompt", array(content));
		// The turn ends when session/prompt returns and at no other signal (reference D5).
		return call(target, "session/prompt", params, response -> {
			JsonNode stopReason = response.path("stopReason");
			emit(new SessionEvent.TurnFinished(sessionId, stopReason.isTextual() ? stopReason.asText() : null));
			return response;
		}, error -> {
			if (error.isAuthRequired()) {
				markAuthRequired(target, "session/prompt", error);
			}
			emit(new SessionEvent.TurnFailed(sessionId, error));
		});
	}

	@Override
	public CompletableFuture<Void> cancel(String sessionId) {
		Run target = current();
		if (target == null) {
			return CompletableFuture.completedFuture(null);
		}
		try {
			target.connection.notify("session/cancel", JSON.objectNode().put("sessionId", sessionId));
		} catch (ConnectionClosedException e) {
			logger.warn("session/cancel failed: " + e.getMessage());
		}
		target.abortPermissions(sessionId);
		return CompletableFuture.completedFuture(null);
	}

	@Override
	public CompletableFuture<Void> setMode(String sessionId, String modeId) {
		Run target = current();
		if (target == null) {
			return notRunning();
		}
		ObjectNode params = JSON.objectNode();
		params.put("sessionId", sessionId);
		params.put("modeId", modeId);
		return call(target, "session/set_mode", params, response -> {
			// Usually the agent's current_mode_update has already said so.
			updateSession(target, sessionId,
					session -> session.modes() != null && modeId.equals(session.modes().path("currentModeId").asText(null))
							? null
							: session.withModes(AcpPayloads.withCurrentMode(session.modes(), modeId)));
			return null;
		}, null);
	}

	@Override
	public CompletableFuture<List<JsonNode>> setConfigOption(String sessionId, String configId, JsonNode value) {
		Run target = current();
		if (target == null) {
			return notRunning();
		}
		ObjectNode params = JSON.objectNode();
		params.put("sessionId", sessionId);
		params.put("configId", configId);
		if (value != null && value.isBoolean()) {
			params.put("type", "boolean");
		}
		params.set("value", value);
		return call(target, "session/set_config_option", params, response -> {
			List<JsonNode> configOptions = List.copyOf(AcpPayloads.elements(response.path("configOptions")));
			updateSession(target, sessionId, session -> session.withConfigOptions(configOptions));
			return configOptions;
		}, null);
	}

	@Override
	public CompletableFuture<Void> setModel(String sessionId, String modelId) {
		Run target = current();
		if (target == null) {
			return notRunning();
		}
		SessionState session = session(sessionId);
		if (session == null || session.models() == null) {
			return failed(new AcpException(
					"The agent did not advertise models for this session (session/set_model unavailable)"));
		}
		ObjectNode params = JSON.objectNode();
		params.put("sessionId", sessionId);
		params.put("modelId", modelId);
		return call(target, KiroAgents.SESSION_SET_MODEL, params, response -> {
			updateSession(target, sessionId, state -> state.models() == null ? null
					: state.withModels(new ModelState(modelId, state.models().availableModels())));
			return null;
		}, null);
	}

	@Override
	public CompletableFuture<Void> authenticate(String methodId) {
		AgentInfo info = agent;
		if (info == null) {
			return failed(new AcpException("agent is not initialized"));
		}
		JsonNode method = info.authMethods().stream().filter(m -> m.path("id").asText("").equals(methodId)).findFirst()
				// The fallback is always accepted: a /login typed while still signed in.
				.orElse(KiroAgents.FALLBACK_LOGIN_METHOD_ID.equals(methodId) ? KiroAgents.FALLBACK_LOGIN_METHOD : null);
		if (method == null) {
			return failed(new AcpException("Unknown auth method '" + methodId + "'"));
		}
		if (isTerminalMethod(method)) {
			return runTerminalAuth(method, null);
		}
		Run target = current();
		if (target == null) {
			return notRunning();
		}
		CompletableFuture<Void> result = new CompletableFuture<>();
		target.connection.request("authenticate", JSON.objectNode().put("methodId", methodId))
				.whenComplete((response, failure) -> {
					if (failure == null) {
						signedIn(target);
						result.complete(null);
						return;
					}
					AcpException error = toAcp(failure, target, "authenticate failed");
					if (!error.isAuthRequired()) {
						result.completeExceptionally(error);
						return;
					}
					// Kiro answers authenticate with "You are not logged in. Please log in with
					// `kiro-cli login`" instead of signing in (reference, observed 2026-11-12):
					// do what it asks for, in a terminal.
					logger.warn("authenticate '" + methodId + "' rejected (" + code(error) + "): " + error.getMessage()
							+ "; falling back to the terminal login");
					runTerminalAuth(KiroAgents.FALLBACK_LOGIN_METHOD, error).whenComplete((done, terminalFailure) -> {
						if (terminalFailure == null) {
							result.complete(null);
						} else {
							result.completeExceptionally(terminalFailure);
						}
					});
				});
		return result;
	}

	/**
	 * @param unsupported what to fail with when the host has no terminal sign-in, or {@code null} for
	 *					the message naming the method
	 */
	private CompletableFuture<Void> runTerminalAuth(JsonNode method, AcpException unsupported) {
		// An agent's own method extends the configured invocation (kiro-cli acp ... --login);
		// the fallback runs kiro-cli login on its own.
		boolean standalone = method == KiroAgents.FALLBACK_LOGIN_METHOD;
		String command = resolvedCommand != null ? resolvedCommand : options.command();
		CompletableFuture<Void> host;
		try {
			host = services.runTerminalAuth(method, command, standalone ? List.of() : options.args());
		} catch (RuntimeException e) {
			return failed(toAcp(e, null, "terminal sign-in failed"));
		}
		if (host == null) {
			return failed(unsupported != null ? unsupported
					: new AcpException("Auth method '" + method.path("id").asText()
							+ "' requires a terminal, which the host does not provide"));
		}
		CompletableFuture<Void> result = new CompletableFuture<>();
		host.whenComplete((done, failure) -> {
			if (failure == null) {
				result.complete(null);
			} else {
				result.completeExceptionally(toAcp(failure, null, "terminal sign-in failed"));
			}
		});
		return result;
	}

	@Override
	public CompletableFuture<JsonNode> callExtension(String method, JsonNode params) {
		Run target = current();
		if (target == null) {
			return notRunning();
		}
		// Not gated on the advertised list: Kiro answers methods it does not advertise (_session/steer).
		return call(target, method, params, response -> response, null);
	}

	/**
	 * A request was rejected because the user is signed out. Only a terminal
	 * login is offered: Kiro 3.x also advertises {@code aws-builder-id} and
	 * {@code aws-iam-identity-center}, but its {@code authenticate} merely tries
	 * to refresh the cached token, which is exactly what just failed. Without a
	 * terminal method of the agent's the fallback login is offered (reference D25).
	 */
	private void markAuthRequired(Run target, String request, AcpException error) {
		synchronized (lock) {
			AgentInfo info = agent;
			if (info == null || run != target) {
				return;
			}
			List<JsonNode> terminal = info.authMethods().stream().filter(KiroAgentImpl::isTerminalMethod).toList();
			agent = new AgentInfo(info.protocolVersion(), info.agentCapabilities(), info.agentInfo(),
					terminal.isEmpty() ? List.of(KiroAgents.FALLBACK_LOGIN_METHOD) : terminal, info.kiroExtensionMethods());
			enqueueStatus(ConnectionStatus.AUTH_REQUIRED, agent, error.getMessage());
		}
		logger.warn(request + " rejected (" + code(error) + "): " + error.getMessage() + "; sign-in required");
		deliverChanges();
	}

	/** A request that needs a signed-in user went through. */
	private void signedIn(Run target) {
		synchronized (lock) {
			if (run != target || status != ConnectionStatus.AUTH_REQUIRED) {
				return;
			}
			enqueueStatus(ConnectionStatus.READY, agent, null);
		}
		deliverChanges();
	}

	private static boolean isTerminalMethod(JsonNode method) {
		return "terminal".equals(method.path("type").asText(null));
	}

	private static String code(AcpException error) {
		return error.code() == null ? "no code" : error.code().toString();
	}

	private Run current() {
		synchronized (lock) {
			return run;
		}
	}

	private void updateSession(Run target, String sessionId, UnaryOperator<SessionState> change) {
		SessionState next;
		synchronized (lock) {
			SessionState now = target.sessions.get(sessionId);
			next = now == null ? null : change.apply(now);
			if (next == null) {
				return;
			}
			target.sessions.put(sessionId, next);
		}
		emit(new SessionEvent.State(next));
	}

	/** Must be called with the lock held, and followed by {@link #deliverChanges()} without it. */
	private void enqueueStatus(ConnectionStatus next, AgentInfo about, String message) {
		status = next;
		AgentInfo reported = about;
		if (reported == null && next != ConnectionStatus.STOPPED && next != ConnectionStatus.ERROR) {
			reported = agent;
		}
		changes.add(new ConnectionStateChange(next, reported, message));
	}

	/**
	 * Status changes come from several threads (callers, the reader, the one
	 * that sees the process exit). They are queued under the lock and delivered
	 * by one thread at a time, so listeners see them in the order they happened
	 * and are never called while this object is locked.
	 */
	private void deliverChanges() {
		synchronized (lock) {
			if (deliveringChanges) {
				return;
			}
			deliveringChanges = true;
		}
		for (;;) {
			ConnectionStateChange change;
			synchronized (lock) {
				change = changes.poll();
				if (change == null) {
					deliveringChanges = false;
					return;
				}
			}
			for (Consumer<ConnectionStateChange> listener : connectionListeners) {
				try {
					listener.accept(change);
				} catch (RuntimeException e) {
					logger.error("connection listener failed: " + e.getMessage(), e);
				}
			}
		}
	}

	private void emit(SessionEvent event) {
		for (Consumer<SessionEvent> listener : sessionListeners) {
			try {
				listener.accept(event);
			} catch (RuntimeException e) {
				logger.error("session listener failed: " + e.getMessage(), e);
			}
		}
	}

	/** What to make of a successful response; runs on the reader thread. */
	@FunctionalInterface
	private interface Reply<T> {

		T read(JsonNode response) throws AcpException;
	}

	/**
	 * Sends a request. The returned future completes after {@code onResult} or
	 * {@code onError} has run, so the events those emit reach the listeners first.
	 *
	 * @param onError told about a failure before the future fails; may be {@code null}
	 */
	private <T> CompletableFuture<T> call(Run target, String method, JsonNode params, Reply<T> onResult,
			Consumer<AcpException> onError) {
		CompletableFuture<T> result = new CompletableFuture<>();
		target.connection.request(method, params).whenComplete((response, answerFailure) -> {
			// Once stop() was called nothing the agent still manages to say counts: every
			// pending request fails as stopped, whichever of the kill and the answer was faster.
			Throwable failure = answerFailure == null && target.stopping
					? new ConnectionClosedException("stopped", null)
					: answerFailure;
			if (failure == null) {
				try {
					result.complete(onResult.read(response));
				} catch (AcpException | RuntimeException e) {
					result.completeExceptionally(toAcp(e, target, method + " failed"));
				}
				return;
			}
			AcpException error = toAcp(failure, target, method + " failed");
			try {
				if (onError != null) {
					onError.accept(error);
				}
			} finally {
				result.completeExceptionally(error);
			}
		});
		return result;
	}

	private static AcpException toAcp(Throwable failure, Run target, String fallback) {
		Throwable cause = failure;
		while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
			cause = cause.getCause();
		}
		if (cause instanceof AcpException known) {
			return known;
		}
		if (cause instanceof JsonRpcException rpc) {
			return new AcpException(rpc.getMessage(), rpc.code(), rpc.data(), rpc);
		}
		if (cause instanceof ConnectionClosedException) {
			return new AcpException(target == null ? "agent is not running" : target.whyClosed(), null, null, cause);
		}
		String message = cause.getMessage();
		return new AcpException(message == null || message.isBlank() ? fallback : message, null, null, cause);
	}

	private static <T> CompletableFuture<T> notRunning() {
		return failed(new AcpException("agent is not running"));
	}

	private static <T> CompletableFuture<T> failed(AcpException error) {
		return CompletableFuture.failedFuture(error);
	}

	private static ArrayNode array(List<JsonNode> elements) {
		ArrayNode array = JSON.arrayNode();
		if (elements != null) {
			array.addAll(elements);
		}
		return array;
	}

	/** One agent process with its connection and everything that dies with it. */
	private final class Run implements JsonRpcConnection.Handler {

		final AgentProcess process;
		final JsonRpcConnection connection;
		/** The reader has seen the end of the agent's output. */
		final CompletableFuture<Void> readerClosed = new CompletableFuture<>();
		/** The end of the process has been dealt with and reported. */
		final CompletableFuture<Void> ended = new CompletableFuture<>();
		volatile boolean stopping;

		final Map<String, SessionState> sessions = new HashMap<>();
		/** Sessions whose session/load is in flight: their updates are replayed history. */
		final Set<String> loading = new HashSet<>();
		String lastSessionId;
		/** Per session, what completes to withdraw each permission request the host is still asking about. */
		final Map<String, Set<CompletableFuture<Void>>> permissions = new HashMap<>();

		Run(AgentProcess process) {
			this.process = process;
			LineTap received = new LineTap(line -> trace(Logger.Direction.RECV, line));
			LineTap sent = new LineTap(line -> trace(Logger.Direction.SEND, line));
			this.connection = new JsonRpcConnection(received.wrap(process.stdout()), sent.wrap(process.stdin()), this,
					"kiro");
		}

		void open() {
			connection.start();
			// The exit is reported once the output has been read to its end, so that what the agent
			// wrote last is still delivered first. A grandchild holding the pipe open must not
			// keep the exit from being reported, hence the limit.
			process.onExit()
					.thenCompose(code -> readerClosed.copy().completeOnTimeout(null, DRAIN_SECONDS, TimeUnit.SECONDS)
							.thenApply(done -> code))
					.thenAccept(code -> ended(this, code));
		}

		String whyClosed() {
			if (stopping) {
				return "agent stopped";
			}
			if (process.isAlive()) {
				return "the connection to kiro-cli was closed";
			}
			Integer code = process.onExit().getNow(null);
			return code == null ? "kiro-cli exited" : "kiro-cli exited with code " + code;
		}

		private void trace(Logger.Direction direction, String line) {
			if (logger.traces()) {
				logger.trace(direction, line);
			}
		}

		@Override
		public void onNotification(String method, JsonNode params) {
			if (SESSION_UPDATE.equals(method)) {
				sessionUpdate(params);
			} else {
				otherNotification(method, params);
			}
		}

		private void sessionUpdate(JsonNode params) {
			JsonNode raw = params == null ? JSON.nullNode() : params;
			JsonNode update = raw.path("update");
			if (!raw.path("sessionId").isTextual() || !update.isObject()) {
				logger.warn("Malformed session/update notification ignored");
				emit(new SessionEvent.ExtensionNotification(SESSION_UPDATE, raw));
				return;
			}
			String sessionId = raw.path("sessionId").asText();
			String kind = update.path("sessionUpdate").asText("");
			if (!KNOWN_SESSION_UPDATES.contains(kind)) {
				emit(new SessionEvent.ExtensionNotification(SESSION_UPDATE, raw));
				return;
			}
			boolean loadInFlight;
			synchronized (lock) {
				loadInFlight = loading.contains(sessionId);
			}
			emit(new SessionEvent.Update(sessionId, update, loadInFlight || AcpPayloads.isReplay(update)));
			switch (kind) {
			case "current_mode_update": {
				JsonNode modeId = update.path("currentModeId");
				if (modeId.isTextual()) {
					updateSession(this, sessionId,
							session -> session.withModes(AcpPayloads.withCurrentMode(session.modes(), modeId.asText())));
				}
				break;
			}
			case "config_option_update": {
				List<JsonNode> configOptions = AcpPayloads.elements(update.path("configOptions"));
				updateSession(this, sessionId, session -> session.withConfigOptions(configOptions));
				break;
			}
			case "available_commands_update": {
				List<JsonNode> commands = AcpPayloads.elements(update.path("availableCommands"));
				updateSession(this, sessionId, session -> session.withAvailableCommands(commands));
				break;
			}
			default:
				break;
			}
		}

		private void otherNotification(String method, JsonNode params) {
			if (KiroAgents.KIRO_V2_COMMANDS_AVAILABLE.equals(method)) {
				AcpPayloads.V2Commands mapped = AcpPayloads.v2Commands(params);
				String sessionId;
				synchronized (lock) {
					// The notification may not say which session it is for; the newest one is meant.
					sessionId = mapped == null ? null : mapped.sessionId() != null ? mapped.sessionId() : lastSessionId;
					if (sessionId != null && !sessions.containsKey(sessionId)) {
						sessionId = null;
					}
				}
				if (sessionId != null) {
					ObjectNode update = JSON.objectNode();
					update.put("sessionUpdate", "available_commands_update");
					update.set("availableCommands", array(mapped.commands()));
					emit(new SessionEvent.Update(sessionId, update, false));
					updateSession(this, sessionId, session -> session.withAvailableCommands(mapped.commands()));
					return;
				}
			}
			emit(new SessionEvent.ExtensionNotification(method, params == null ? JSON.nullNode() : params));
		}

		@Override
		public void onRequest(String method, JsonNode rawParams, Responder responder) {
			JsonNode params = rawParams == null ? JSON.objectNode() : rawParams;
			switch (method) {
			case "session/request_permission":
				permission(params, responder);
				break;
			case "fs/read_text_file":
				answer(method, responder, () -> services.readTextFile(params));
				break;
			case "fs/write_text_file":
				answer(method, responder, () -> services.writeTextFile(params));
				break;
			case "terminal/create":
				answer(method, responder, () -> services.createTerminal(params));
				break;
			case "terminal/output":
				answer(method, responder, () -> services.terminalOutput(params));
				break;
			case "terminal/wait_for_exit":
				answer(method, responder, () -> services.waitForTerminalExit(params));
				break;
			case "terminal/kill":
				answer(method, responder, () -> services.killTerminal(params));
				break;
			case "terminal/release":
				answer(method, responder, () -> services.releaseTerminal(params));
				break;
			case KiroAgents.KIRO_OPEN_EXTERNAL_URL:
				openExternalUrl(params, responder);
				break;
			default:
				// The params are logged so that an unknown Kiro request can be recorded and implemented later.
				String text = params.toString();
				logger.warn("Unknown request from agent: " + method + " "
						+ (text.length() > UNKNOWN_REQUEST_LOG_LIMIT ? text.substring(0, UNKNOWN_REQUEST_LOG_LIMIT) + "…"
								: text));
				methodNotFound(method, responder);
				break;
			}
		}

		private void openExternalUrl(JsonNode params, Responder responder) {
			JsonNode url = params.path("url");
			CompletableFuture<Void> opened;
			try {
				// An unusable URL is not shown to the host; whether the host supports the method at
				// all is only known by asking it, so a host without it sees no call either way.
				opened = url.isTextual() && !url.asText().isEmpty() ? services.openExternalUrl(url.asText()) : null;
			} catch (RuntimeException e) {
				fail(KiroAgents.KIRO_OPEN_EXTERNAL_URL, responder, e);
				return;
			}
			if (opened == null) {
				if (url.isTextual() && !url.asText().isEmpty()) {
					methodNotFound(KiroAgents.KIRO_OPEN_EXTERNAL_URL, responder);
				} else {
					responder.error(JsonRpcException.INVALID_PARAMS, "Invalid params: expected { url: string }");
				}
				return;
			}
			opened.whenComplete((done, failure) -> {
				if (failure == null) {
					responder.result(JSON.objectNode());
				} else {
					fail(KiroAgents.KIRO_OPEN_EXTERNAL_URL, responder, failure);
				}
			});
		}

		private void answer(String method, Responder responder, Supplier<CompletableFuture<JsonNode>> service) {
			CompletableFuture<JsonNode> future;
			try {
				future = service.get();
			} catch (RuntimeException e) {
				fail(method, responder, e);
				return;
			}
			if (future == null) {
				fail(method, responder, new IllegalStateException("the host gave no answer"));
				return;
			}
			future.whenComplete((result, failure) -> {
				if (failure != null) {
					fail(method, responder, failure);
				} else {
					// ACP responses are objects; a host with nothing to say means the empty one.
					responder.result(result == null || result.isNull() ? JSON.objectNode() : result);
				}
			});
		}

		private void fail(String method, Responder responder, Throwable failure) {
			AcpException error = toAcp(failure, this, method + " failed");
			if (error.code() != null) {
				responder.error(error.code(), error.getMessage(), error.data());
				return;
			}
			logger.warn(method + " failed: " + error.getMessage());
			responder.error(JsonRpcException.INTERNAL_ERROR, "Internal error",
					JSON.objectNode().put("details", error.getMessage()));
		}

		private void methodNotFound(String method, Responder responder) {
			responder.error(JsonRpcException.METHOD_NOT_FOUND, "Method not found: " + method,
					JSON.objectNode().put("method", method));
		}

		private void permission(JsonNode params, Responder responder) {
			String sessionId = params.path("sessionId").asText("");
			CompletableFuture<Void> cancelled = new CompletableFuture<>();
			synchronized (lock) {
				permissions.computeIfAbsent(sessionId, id -> new HashSet<>()).add(cancelled);
			}
			// The first answer wins (see Responder): a cancelled turn is answered here and now,
			// whatever the host says later.
			cancelled.thenRun(() -> responder.result(cancelledOutcome()));
			if (stopping) {
				// stop() has already withdrawn everything it knew of.
				abortPermissions(sessionId);
			}
			CompletableFuture<JsonNode> host;
			try {
				host = services.requestPermission(params, cancelled.minimalCompletionStage());
			} catch (RuntimeException e) {
				host = CompletableFuture.failedFuture(e);
			}
			if (host == null) {
				host = CompletableFuture.failedFuture(new IllegalStateException("the host gave no answer"));
			}
			host.whenComplete((response, failure) -> {
				synchronized (lock) {
					Set<CompletableFuture<Void>> pending = permissions.get(sessionId);
					if (pending != null && pending.remove(cancelled) && pending.isEmpty()) {
						permissions.remove(sessionId);
					}
				}
				if (cancelled.isDone()) {
					return;
				}
				if (failure != null) {
					fail("session/request_permission", responder, failure);
				} else {
					responder.result(response == null || response.isNull() ? cancelledOutcome() : response);
				}
			});
		}

		private ObjectNode cancelledOutcome() {
			ObjectNode response = JSON.objectNode();
			response.putObject("outcome").put("outcome", "cancelled");
			return response;
		}

		void abortPermissions(String sessionId) {
			Set<CompletableFuture<Void>> pending;
			synchronized (lock) {
				pending = permissions.remove(sessionId);
			}
			if (pending != null) {
				pending.forEach(cancelled -> cancelled.complete(null));
			}
		}

		void abortAllPermissions() {
			List<CompletableFuture<Void>> pending = new ArrayList<>();
			synchronized (lock) {
				permissions.values().forEach(pending::addAll);
				permissions.clear();
			}
			pending.forEach(cancelled -> cancelled.complete(null));
		}

		@Override
		public void onProtocolError(String description, String line, Throwable cause) {
			StringBuilder message = new StringBuilder("Protocol error: ").append(description);
			if (cause != null && cause.getMessage() != null) {
				message.append(" (").append(cause.getMessage()).append(')');
			}
			if (line != null) {
				message.append(": ").append(
						line.length() > UNKNOWN_REQUEST_LOG_LIMIT ? line.substring(0, UNKNOWN_REQUEST_LOG_LIMIT) + "…" : line);
			}
			logger.warn(message.toString());
		}

		@Override
		public void onClosed(Throwable cause) {
			if (cause != null && !stopping) {
				logger.warn("Reading from kiro-cli failed: " + cause.getMessage());
			}
			readerClosed.complete(null);
			if (stopping || !process.isAlive()) {
				return;
			}
			// A process that has just closed its output is usually exiting; one that is not is
			// of no use without a connection.
			CompletableFuture.delayedExecutor(EXIT_SECONDS, TimeUnit.SECONDS).execute(() -> {
				if (!stopping && process.isAlive()) {
					logger.warn("ACP connection closed while kiro-cli is still running; terminating it");
					process.terminate();
				}
			});
		}
	}
}
