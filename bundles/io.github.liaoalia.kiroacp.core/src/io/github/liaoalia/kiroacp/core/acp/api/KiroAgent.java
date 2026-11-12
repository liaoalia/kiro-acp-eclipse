package io.github.liaoalia.kiroacp.core.acp.api;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.liaoalia.kiroacp.core.host.Subscription;
import io.github.liaoalia.kiroacp.core.model.ConnectionStatus;

/**
 * One {@code kiro-cli acp} process, which hosts any number of sessions.
 *
 * <p>Methods may be called from any thread and never block on the agent: they
 * return futures, which fail with {@link AcpException}. Listeners must return
 * quickly. Whatever the agent sends reaches session listeners on the
 * connection's reader thread, in wire order, and every update the agent wrote
 * before the response of a request has been delivered before that request's
 * future completes. {@code TurnStarted} is delivered on the thread that called
 * {@link #prompt}, before the prompt is written. Connection changes come from
 * whichever thread caused them, one at a time and in order.
 */
public interface KiroAgent {

	ConnectionStatus status();

	/** @return what the agent said in {@code initialize}, or {@code null} before it answered */
	AgentInfo agent();

	/** Spawns the process and runs {@code initialize}. */
	CompletableFuture<AgentInfo> start();

	/** Kills the process; pending requests fail. Idempotent. */
	CompletableFuture<Void> stop();

	Subscription onConnectionChange(Consumer<ConnectionStateChange> listener);

	Subscription onSessionEvent(Consumer<SessionEvent> listener);

	/** @return the last known state of a live session, or {@code null} */
	SessionState session(String sessionId);

	/**
	 * @param mcpServers ACP {@code McpServer[]}; may be empty
	 * @param meta	   {@code _meta} for the request, or {@code null}
	 */
	CompletableFuture<SessionState> newSession(Path cwd, List<JsonNode> mcpServers, JsonNode meta);

	/**
	 * {@code session/list}. Fails when the agent did not advertise it.
	 *
	 * @param cwd	only sessions of this directory, or {@code null} for all
	 * @param cursor from the previous page, or {@code null}
	 */
	CompletableFuture<SessionListPage> listSessions(Path cwd, String cursor);

	/**
	 * {@code session/load}. History is replayed through {@link #onSessionEvent}
	 * as updates flagged {@code replay} before the future completes.
	 */
	CompletableFuture<SessionState> loadSession(String sessionId, Path cwd, List<JsonNode> mcpServers);

	/**
	 * Sends a prompt; updates stream through {@link #onSessionEvent}.
	 *
	 * @param content ACP {@code ContentBlock[]}
	 * @return the {@code PromptResponse} (with its {@code stopReason}) once the turn ends
	 */
	CompletableFuture<JsonNode> prompt(String sessionId, List<JsonNode> content);

	/** Sends {@code session/cancel}; pending permission requests of the session resolve {@code cancelled}. */
	CompletableFuture<Void> cancel(String sessionId);

	CompletableFuture<Void> setMode(String sessionId, String modeId);

	/**
	 * @param value a text node for a select option, a boolean node for a boolean one
	 * @return the session's config options after the change
	 */
	CompletableFuture<List<JsonNode>> setConfigOption(String sessionId, String configId, JsonNode value);

	/** CLI v2 only ({@code session/set_model}). Fails when the agent did not advertise models. */
	CompletableFuture<Void> setModel(String sessionId, String modelId);

	/** Runs an advertised auth method. Terminal methods are delegated back to the host. */
	CompletableFuture<Void> authenticate(String methodId);

	/** Calls a Kiro extension method advertised in {@link AgentInfo#kiroExtensionMethods()}. */
	CompletableFuture<JsonNode> callExtension(String method, JsonNode params);
}
