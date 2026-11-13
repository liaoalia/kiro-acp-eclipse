package io.github.liaoalia.kiroacp.core.session;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import io.github.liaoalia.kiroacp.core.acp.api.AgentInfo;
import io.github.liaoalia.kiroacp.core.acp.api.KiroAgent;
import io.github.liaoalia.kiroacp.core.acp.api.SessionState;
import io.github.liaoalia.kiroacp.core.host.Logger;
import io.github.liaoalia.kiroacp.core.host.Subscription;
import io.github.liaoalia.kiroacp.core.model.CommandView;
import io.github.liaoalia.kiroacp.core.model.ContextChip;
import io.github.liaoalia.kiroacp.core.model.FromWebview;
import io.github.liaoalia.kiroacp.core.model.ImageAttachment;
import io.github.liaoalia.kiroacp.core.model.PermissionView;
import io.github.liaoalia.kiroacp.core.model.PlanEntryView;
import io.github.liaoalia.kiroacp.core.model.SessionUsage;
import io.github.liaoalia.kiroacp.core.model.ToolCallView;
import io.github.liaoalia.kiroacp.core.pure.transcript.TranscriptState;

/**
 * Where the features that are not part of the core chat plug in: context
 * chips and images, diff review, the plan document, past sessions and
 * remembered options, steering and queueing, sign-in, slash commands, usage.
 *
 * <p>The controller handles the chat itself and asks here for everything
 * else. Each seam has the simplest correct behaviour while nothing is
 * registered: no chips, plain text prompts, no prompts during a turn, the
 * agent's own commands. Registration is safe from any thread; everything
 * registered is called on the controller thread.
 */
public final class ChatHooks {

	/** Handles one type of page message the controller does not handle itself. */
	@FunctionalInterface
	public interface MessageHandler<M extends FromWebview> {

		/** @param page the page the message came from; its {@link PageHandle#sessionId()} is the session it is about */
		void handle(M message, PageHandle page);
	}

	/** Told what happens to the sessions. Every method is optional. */
	public interface SessionListener {

		default void sessionOpened(String sessionId) {
		}

		/** Before the session is forgotten, while a page may still display it. Also for every session of a restart. */
		default void sessionClosing(String sessionId) {
		}

		/**
		 * The session is about to be replaced by a new one (reference D22, {@code /clear}):
		 * from here on nothing may start a turn on it. {@link #sessionClosing} follows once
		 * the new session exists; when none could be created the session stays and is usable again.
		 */
		default void sessionReplacing(String sessionId) {
		}

		/** The focused session changed; {@code null} when there is none. */
		default void focusChanged(String sessionId) {
		}

		/**
		 * A {@code session/update} of a live session, before the transcript sees
		 * it. Also the kinds the transcript does not show ({@code usage_update},
		 * {@code session_info_update}).
		 */
		default void update(String sessionId, JsonNode update, boolean replay) {
		}

		/** A tool card changed by a live update (not by replayed history), after it was posted. */
		default void toolCallChanged(String sessionId, ToolCallView call) {
		}

		/** The plan of the <em>focused</em> session changed. */
		default void planChanged(String sessionId, List<PlanEntryView> entries) {
		}

		/** A permission card was posted. */
		default void permissionRequested(String sessionId, PermissionView request) {
		}

		default void turnStarted(String sessionId) {
		}

		/**
		 * The turn is over, however it ended; the {@code turn} message is out.
		 *
		 * @param stopReason the agent's, {@code error} when the prompt failed or the agent went away,
		 *                   {@code null} when the agent gave none
		 */
		default void turnEnded(String sessionId, String stopReason) {
		}

		/**
		 * After a turn that ended with a stop reason (the agent's, or the Stop
		 * watchdog's {@code cancelled}), once its notices are out: the point at
		 * which the reference sends the next queued prompt and polls the usage.
		 */
		default void afterTurn(String sessionId, String stopReason) {
		}

		/** The user chose a mode, a config option value or a model and the agent accepted it. */
		default void optionChosen(String sessionId, Option option, String id, JsonNode value) {
		}
	}

	public enum Option {
		/** {@code id} is the mode id. */
		MODE,
		/** {@code id} is the config option's id, {@code value} its new value. */
		CONFIG,
		/** {@code id} is the model id. */
		MODEL
	}

	/**
	 * A prompt as the user sent it.
	 *
	 * @param sessionId {@code null} when there is no session to send it to (signed out, agent down)
	 * @param text      trimmed
	 * @param page      the page it was typed in, or {@code null}
	 */
	public record PromptRequest(String sessionId, String text, List<ContextChip> chips, List<ImageAttachment> images,
			PageHandle page) {

		public PromptRequest {
			chips = chips == null ? List.of() : List.copyOf(chips);
			images = images == null ? List.of() : List.copyOf(images);
		}
	}

	/** Sees every prompt before it is sent: slash commands, checks that refuse a message. */
	@FunctionalInterface
	public interface PromptInterceptor {

		/**
		 * @return {@code true} once the prompt was dealt with here (run locally, or refused with a
		 *         notice) and must not go to the agent
		 */
		CompletionStage<Boolean> intercept(PromptRequest request);
	}

	/** Turns what the user sent into what the agent gets and what the transcript shows. */
	public interface PromptComposer {

		/** The chips the composer shows ({@code UiState.chips}). */
		default List<ContextChip> chips() {
			return List.of();
		}

		/** @return ACP {@code ContentBlock[]} of the prompt; empty when there is nothing to send */
		CompletionStage<List<JsonNode>> blocks(PromptRequest request, AgentInfo agent);

		/** @return ACP content blocks shown in the user's message after its text: one per image and chip */
		default List<JsonNode> echo(PromptRequest request) {
			return List.of();
		}

		/** The prompt was echoed and is on its way: chips attached for it are used up. */
		default void sent(PromptRequest request) {
		}
	}

	/**
	 * Takes the prompts typed while a turn runs (reference D22: steer, else
	 * queue). Nothing was echoed yet: {@link ChatController#echoPrompt} shows
	 * the message, with its badge.
	 */
	@FunctionalInterface
	public interface MidTurnPrompts {

		/** @param blocks what the {@link PromptComposer} made of the request */
		CompletionStage<Void> prompt(PromptRequest request, List<JsonNode> blocks);
	}

	/** The commands the {@code /} popup offers: the agent's plus whatever the client adds. */
	@FunctionalInterface
	public interface CommandCatalogue {

		/** @param agent {@code null} while not connected */
		List<CommandView> commands(TranscriptState transcript, AgentInfo agent);
	}

	/** Usage of a session for {@code UiState.usage}; {@code null} while the agent reported none. */
	@FunctionalInterface
	public interface UsageSource {

		SessionUsage usage(String sessionId);
	}

	/** Shapes new sessions (reference D24: remembered mode, model and config options). */
	public interface NewSessions {

		/** @return {@code _meta} for {@code session/new}, or {@code null}. A request the agent rejects is retried without it. */
		default JsonNode meta() {
			return null;
		}

		/** The session exists and nothing shows it yet: apply what it should start with. */
		default CompletionStage<Void> opened(KiroAgent agent, SessionState session) {
			return CompletableFuture.completedFuture(null);
		}
	}

	private static final PromptComposer TEXT_ONLY = (request, agent) -> {
		List<JsonNode> blocks = new ArrayList<>();
		if (!request.text().isEmpty()) {
			blocks.add(JsonNodeFactory.instance.objectNode().put("type", "text").put("text", request.text()));
		}
		return CompletableFuture.completedFuture(blocks);
	};

	private final Map<Class<? extends FromWebview>, MessageHandler<?>> handlers = new ConcurrentHashMap<>();
	private final List<SessionListener> listeners = new CopyOnWriteArrayList<>();
	private final List<PromptInterceptor> interceptors = new CopyOnWriteArrayList<>();
	private volatile PromptComposer composer = TEXT_ONLY;
	private volatile MidTurnPrompts midTurn;
	private volatile CommandCatalogue commands;
	private volatile UsageSource usage;
	private volatile NewSessions newSessions;
	private final Logger logger;

	ChatHooks(Logger logger) {
		this.logger = logger;
	}

	/**
	 * Register the handler of a page message type. Types the controller
	 * handles itself never reach a handler; one handler per type, the later
	 * registration wins.
	 *
	 * @return removes the handler
	 */
	public <M extends FromWebview> Subscription onMessage(Class<M> type, MessageHandler<? super M> handler) {
		handlers.put(type, handler);
		return () -> handlers.remove(type, handler);
	}

	public Subscription addListener(SessionListener listener) {
		listeners.add(listener);
		return () -> listeners.remove(listener);
	}

	/** Interceptors are asked in the order they were added; the first to say {@code true} ends it. */
	public Subscription addPromptInterceptor(PromptInterceptor interceptor) {
		interceptors.add(interceptor);
		return () -> interceptors.remove(interceptor);
	}

	/** @param composer {@code null} for the default: the text as one block, no chips, no images */
	public void setPromptComposer(PromptComposer composer) {
		this.composer = composer != null ? composer : TEXT_ONLY;
	}

	/** While none is set, a prompt during a turn is refused with a notice and the page is told not to offer it. */
	public void setMidTurnPrompts(MidTurnPrompts midTurn) {
		this.midTurn = midTurn;
	}

	public void setCommandCatalogue(CommandCatalogue commands) {
		this.commands = commands;
	}

	public void setUsageSource(UsageSource usage) {
		this.usage = usage;
	}

	public void setNewSessions(NewSessions newSessions) {
		this.newSessions = newSessions;
	}

	// ---------------------------------------------------------------------
	// For the controller
	// ---------------------------------------------------------------------

	/** @return whether a feature registered a handler for that type of page message */
	boolean handles(Class<? extends FromWebview> type) {
		return handlers.containsKey(type);
	}

	/** @return whether a handler took the message */
	@SuppressWarnings("unchecked")
	boolean dispatch(FromWebview message, PageHandle page) {
		MessageHandler<FromWebview> handler = (MessageHandler<FromWebview>) handlers.get(message.getClass());
		if (handler == null) {
			return false;
		}
		try {
			handler.handle(message, page);
		} catch (RuntimeException e) {
			logger.error("handler of " + message.getClass().getSimpleName() + " failed: " + Async.message(e), e);
		}
		return true;
	}

	void each(Consumer<SessionListener> event) {
		for (SessionListener listener : listeners) {
			try {
				event.accept(listener);
			} catch (RuntimeException e) {
				logger.error("session listener failed: " + Async.message(e), e);
			}
		}
	}

	List<PromptInterceptor> interceptors() {
		return List.copyOf(interceptors);
	}

	PromptComposer composer() {
		return composer;
	}

	MidTurnPrompts midTurn() {
		return midTurn;
	}

	NewSessions newSessions() {
		return newSessions;
	}

	List<CommandView> commands(TranscriptState transcript, AgentInfo agent) {
		CommandCatalogue catalogue = commands;
		return catalogue != null ? catalogue.commands(transcript, agent) : transcript.commands();
	}

	SessionUsage usage(String sessionId) {
		UsageSource source = usage;
		return source != null && sessionId != null ? source.usage(sessionId) : null;
	}
}
