package io.github.liaoalia.kiroacp.core.session;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.liaoalia.kiroacp.core.acp.api.AgentInfo;
import io.github.liaoalia.kiroacp.core.acp.api.KiroAgent;
import io.github.liaoalia.kiroacp.core.host.ChatPage;
import io.github.liaoalia.kiroacp.core.host.Logger;
import io.github.liaoalia.kiroacp.core.host.Subscription;
import io.github.liaoalia.kiroacp.core.model.CommandView;
import io.github.liaoalia.kiroacp.core.model.ConnectionStatus;
import io.github.liaoalia.kiroacp.core.model.ConnectionView;
import io.github.liaoalia.kiroacp.core.model.ContextChip;
import io.github.liaoalia.kiroacp.core.model.ImageAttachment;
import io.github.liaoalia.kiroacp.core.model.DeliveryState;
import io.github.liaoalia.kiroacp.core.model.FromWebview;
import io.github.liaoalia.kiroacp.core.model.NoticeView;
import io.github.liaoalia.kiroacp.core.model.NoticeView.Severity;
import io.github.liaoalia.kiroacp.core.model.PermissionView;
import io.github.liaoalia.kiroacp.core.model.ToWebview;
import io.github.liaoalia.kiroacp.core.model.UiState;
import io.github.liaoalia.kiroacp.core.pure.transcript.TranscriptState;
import io.github.liaoalia.kiroacp.core.services.FsService;
import io.github.liaoalia.kiroacp.core.services.HostServices;
import io.github.liaoalia.kiroacp.core.services.PermissionManager;
import io.github.liaoalia.kiroacp.core.services.TerminalService;

/**
 * Orchestrates the chat: the agent process, every live session and the pages
 * that display them ({@code ChatController} of the reference's
 * chatController.ts, without the features that plug in through
 * {@link ChatHooks}).
 *
 * <p>One agent process hosts many sessions. Each attached page displays one
 * session at a time; the controller also tracks a <em>focused</em> session,
 * the one most recently used, which commands without a page act on. Sessions
 * are grouped: a page with a group lists and switches between the sessions
 * of that group only, and closing it ends them (reference D19, D20).
 *
 * <p>All state lives on the {@code UiScheduler}'s thread. The agent's
 * events, which arrive on its reader thread, and the changes of the services
 * are queued onto it in the order they happened. Methods that return a
 * future may be called from any thread; the future completes on the
 * controller thread. Everything else says where it may be called.
 */
public final class ChatController implements AutoCloseable {

	/** Group of the sessions created without a page of their own (the first, auto-created session). */
	public static final String DEFAULT_GROUP = "default";

	final ChatHost host;
	final Logger logger;
	final Async async;
	final ChatHooks hooks;
	final PermissionManager permissions;
	final TerminalService terminal;
	final Snapshots snapshots = new Snapshots(this);
	final SessionListing listing = new SessionListing(this);
	final HostServices services;
	final ToolKindIndex kinds = new ToolKindIndex();
	final Turns turns = new Turns(this);
	final SessionEvents events = new SessionEvents(this);
	final SessionRoster roster = new SessionRoster(this);
	final SessionRestore restore = new SessionRestore(this);
	final AgentLifecycle lifecycle;
	private final PageMessages messages = new PageMessages(this);
	final PermissionCards cards = new PermissionCards(this);
	private final FsService fs;
	private final Subscription permissionChanges;

	KiroAgent agent;
	AgentInfo agentInfo;
	ConnectionView connection = ConnectionView.of(ConnectionStatus.STOPPED);
	final Map<String, ChatSession> sessions = new LinkedHashMap<>();
	String focusedId;
	/** The page the user used last; commands without a page act on it. */
	PageBinding focusedPage;
	final Set<PageBinding> pages = new LinkedHashSet<>();
	/** Notices not tied to a session (the agent could not start); shown in every page. */
	List<NoticeView> globalNotices = List.of();
	volatile boolean closed;
	private int noticeSeq;

	private ChatController(ChatHost host, AgentFactory agents) {
		this.host = host;
		this.logger = host.logger();
		this.async = new Async(host.scheduler(), logger);
		this.hooks = new ChatHooks(logger);
		this.permissions = new PermissionManager(kinds, logger);
		this.fs = new FsService(host.workspace(), host.textFiles(), logger);
		this.terminal = new TerminalService(host.settings(), host.workspace(), host.terminalDisplay(), logger);
		this.services = new HostServices(permissions, fs, terminal, host.externalActions(), logger);
		this.lifecycle = new AgentLifecycle(this, agents);
		// On the thread of the change: the cards note what is pending at that moment and queue
		// a sync that shows no more than that, so a card never overtakes the tool call that
		// preceded its request on the wire (see PermissionCards).
		this.permissionChanges = permissions.onDidChange(cards::changed);
	}

	/** A controller for the real agent. Nothing starts until {@link #start()} or a page says {@code ready}. */
	public static ChatController create(ChatHost host) {
		return new ChatController(host, AgentFactory.DEFAULT);
	}

	/** @param agents where agents come from; tests name the fake agent here */
	public static ChatController create(ChatHost host, AgentFactory agents) {
		return new ChatController(host, agents);
	}

	// ---------------------------------------------------------------------
	// Public: what the IDE and the features use
	// ---------------------------------------------------------------------

	/** Where the features outside the core chat register. */
	public ChatHooks hooks() {
		return hooks;
	}

	/** What the IDE gave the controller: the features use the same workspace, settings, scheduler and log. */
	public ChatHost host() {
		return host;
	}

	/** The permission registry; a feature that resolves requests itself (diff review, plan approval) uses it. */
	public PermissionManager permissions() {
		return permissions;
	}

	/** The agent's terminals. */
	public TerminalService terminals() {
		return terminal;
	}

	/** The agent's file access; the diff feature installs its write interceptor here. */
	public FsService files() {
		return fs;
	}

	/**
	 * Attach a chat page. It gets nothing until it says {@code ready}
	 * (through {@link PageHandle#onMessage}), which is answered with a full
	 * {@code state} and, once the agent runs, a session.
	 */
	public PageHandle attach(ChatPage page, AttachOptions options) {
		PageBinding binding = new PageBinding(this, page, options != null ? options : AttachOptions.shared());
		async.run(() -> {
			if (!closed) {
				pages.add(binding);
			}
		});
		return binding;
	}

	/** Start the agent process (idempotent) and open a first session when none exists. */
	public CompletableFuture<Void> start() {
		return async.call(lifecycle::ensureStarted);
	}

	/** {@link #start()}, then give the page a session when it has none: for a page opened before it is {@code ready}. */
	public CompletableFuture<Void> ensureSession(PageHandle page) {
		return async.call(() -> roster.ensurePageSession(binding(page)));
	}

	/** Spawn a fresh agent process. The sessions belong to the old one and end with it. */
	public CompletableFuture<Void> restart() {
		return async.call(() -> lifecycle.doRestart(false));
	}

	/**
	 * {@link #restart()} for a sign-in (reference D25): every live session with
	 * a conversation is loaded back into the new process ({@code session/load})
	 * and into its group, each page showing its own session again; a page whose
	 * session cannot be loaded gets a fresh one and a warning that names the
	 * lost session. Empty sessions are not brought back.
	 */
	public CompletableFuture<Void> restartKeepingSessions() {
		return async.call(() -> lifecycle.doRestart(true));
	}

	/**
	 * Stop the agent process without forgetting the sessions: they stay
	 * listed with status {@code error} until {@link #restart()}, exactly as
	 * after a crash.
	 */
	public CompletableFuture<Void> shutdownAgent() {
		return async.call(() -> {
			KiroAgent current = agent;
			if (current == null) {
				return Async.done(null);
			}
			// Now, not when the stop is reported: until then the commands would run on without an agent.
			lifecycle.killTerminals("agent shutdown");
			return async.ui(current.stop()).handle((done, error) -> {
				if (error != null) {
					logger.warn("agent stop failed: " + Async.message(error));
				}
				// Until it was gone the agent could still ask for a command.
				lifecycle.killTerminals("agent shutdown, after the stop");
				return null;
			});
		});
	}

	/**
	 * Create a session, keeping the others, focus it and display it in
	 * {@code page} (default: the focused page).
	 *
	 * @return the new session's id, or {@code null} when none could be created
	 */
	public CompletableFuture<String> newSession(PageHandle page) {
		return async.call(() -> roster.doNewSession(binding(page)));
	}

	/**
	 * Display a live session in {@code page} (default: the focused page) and
	 * focus it. A grouped page only switches within its group, plus
	 * {@value #DEFAULT_GROUP}, which it adopts.
	 */
	public CompletableFuture<Void> selectSession(String sessionId, PageHandle page) {
		return async.call(() -> {
			roster.doSelectSession(sessionId, binding(page));
			return Async.done(null);
		});
	}

	/**
	 * Cancel, forget and drop a session. Pages displaying it switch to the
	 * most recent remaining session of their group; a new one is created
	 * when none is left.
	 */
	public CompletableFuture<Void> closeSession(String sessionId) {
		return async.call(() -> roster.doCloseSession(sessionId));
	}

	/**
	 * Replace a session by a new, empty one in the page that shows it (the
	 * reference's {@code /clear}, D22): from this call on nothing starts a
	 * turn on the session, its running turn is cancelled, a new session is
	 * created and displayed, and then the old one is closed. A prompt sent to
	 * the session meanwhile goes to the new one. When no session could be
	 * created the old one stays, usable.
	 *
	 * @param page the page the request came from; when it does not display the session, the
	 *             focused page or any other page that does gets the new one
	 * @return the new session's id, or {@code null} when the session was not replaced
	 */
	public CompletableFuture<String> replaceSession(String sessionId, PageHandle page) {
		return async.call(() -> roster.doReplaceSession(sessionId, binding(page)));
	}

	/**
	 * Restore a session the agent kept from an earlier run ({@code session/load},
	 * reference D21) into a fresh session of the page's group (default: the
	 * focused page's, else {@value #DEFAULT_GROUP}), display and focus it. The
	 * history the agent replays builds its transcript as live updates do. An
	 * id that is live already is only selected.
	 *
	 * @param title the agent's title of the session ({@code session/list}), or {@code null} when unknown
	 * @return the session's id, or {@code null} when it could not be restored: the page then shows
	 *         what it showed before and a notice says why
	 */
	public CompletableFuture<String> openPastSession(String sessionId, String title, PageHandle page) {
		return async.call(() -> roster.doOpenPastSession(sessionId, title, binding(page)));
	}

	/** Close every session of a group: its page was closed (reference D20). */
	public CompletableFuture<Void> closeGroup(String groupId) {
		return async.call(() -> {
			restore.groupClosed(groupId);
			return roster.closeNext(groupId);
		});
	}

	/**
	 * Send a prompt as if typed in {@code page}.
	 *
	 * @param sessionId the session, or {@code null} for the page's, else the focused one
	 * @return completes when the turn has ended
	 */
	public CompletableFuture<Void> prompt(String text, String sessionId, PageHandle page) {
		return async.call(() -> turns.prompt(text, List.of(), sessionId, binding(page), List.of()));
	}

	/**
	 * {@link #prompt(String, String, PageHandle)} with chips and images: what a
	 * feature sends on behalf of the user (the text of {@code /plan}). It
	 * passes the prompt interceptors like any other prompt.
	 */
	public CompletableFuture<Void> prompt(String text, List<ContextChip> chips, List<ImageAttachment> images, String sessionId,
			PageHandle page) {
		return async.call(() -> turns.prompt(text, chips, sessionId, binding(page), images));
	}

	/**
	 * The user chose a mode, a config option value or a model by other means
	 * than the page's controls (a slash command) and the agent accepted it:
	 * the listeners hear of it as of a choice made in the page. Controller thread.
	 *
	 * @param value the option's new value; {@code null} for a mode or a model, whose value is its id
	 */
	public void optionChosen(String sessionId, ChatHooks.Option option, String id, JsonNode value) {
		JsonNode chosen = value != null ? value : com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.textNode(id);
		hooks.each(listener -> listener.optionChosen(sessionId, option, id, chosen));
	}

	/** Stop the running turn of {@code sessionId}; {@code null} means the focused session. */
	public CompletableFuture<Void> cancel(String sessionId) {
		return async.call(() -> turns.cancel(sessionId));
	}

	/** Ends everything: pages detach, the agent stops, the services close. On the controller thread it takes effect at once. */
	public CompletableFuture<Void> shutdown() {
		return async.call(() -> {
			if (closed) {
				return Async.done(null);
			}
			closed = true;
			listing.stop();
			permissionChanges.close();
			sessions.values().forEach(ChatSession::stopCancelTimer);
			lifecycle.unsubscribe();
			KiroAgent old = agent;
			agent = null;
			pages.clear();
			focusedPage = null;
			// The commands the agent started are children of this process, not of the agent:
			// they are ended here and now, before the agent is even told to stop, because the
			// JVM may be gone before the agent is. From here on no command can be started.
			try {
				terminal.close();
			} catch (RuntimeException e) {
				logger.warn("shutdown: could not end the running terminals: " + Async.message(e));
			}
			// Not brought back to the controller thread: at shutdown it may be gone before the process is.
			CompletableFuture<Void> stopped = old != null ? old.stop().handle((done, error) -> null) : Async.done(null);
			return stopped.thenRun(() -> {
				services.close();
				// Again: closing twice does no harm, and this place is where the services end.
				terminal.close();
				fs.close();
				permissions.close();
			});
		});
	}

	@Override
	public void close() {
		shutdown();
	}

	// --- queries: controller thread ---

	/** The focused session (most recently used through a page or a command), or {@code null}. */
	public String focusedSessionId() {
		return focusedId;
	}

	/** Ids of every live session, most recent first. */
	public List<String> sessionIds() {
		return listing.sorted().stream().map(ChatSession::id).toList();
	}

	/** The group of a live session, or {@code null} when unknown. */
	public String sessionGroup(String sessionId) {
		ChatSession session = sessions.get(sessionId);
		return session != null ? session.groupId : null;
	}

	public boolean hasPages() {
		return !pages.isEmpty();
	}

	public ConnectionView connection() {
		return connection;
	}

	/** The current agent, or {@code null} while none was started. */
	public KiroAgent agent() {
		return agent;
	}

	/** What the agent said in {@code initialize}, or {@code null}. */
	public AgentInfo agentInfo() {
		return agentInfo;
	}

	/** Full {@code UiState} of a session, listing every session; {@code null} gives an empty page without a session. */
	public UiState snapshot(String sessionId) {
		return snapshots.build(sessionId, null);
	}

	/** The transcript of a live session, or {@code null}. */
	public TranscriptState transcript(String sessionId) {
		ChatSession session = sessions.get(sessionId);
		return session != null ? session.transcript : null;
	}

	public boolean turnActive(String sessionId) {
		ChatSession session = sessions.get(sessionId);
		return session != null && session.turnActive;
	}

	/** Whether the session is live and staying: not closed, and neither being closed nor being replaced. */
	public boolean sessionOpen(String sessionId) {
		ChatSession session = sessionId != null ? sessions.get(sessionId) : null;
		return session != null && !session.closing;
	}

	/** Changes whenever a turn of the session starts or ends: what a late answer compares to know it is late. */
	public int turnGeneration(String sessionId) {
		ChatSession session = sessions.get(sessionId);
		return session != null ? session.turnGeneration : -1;
	}

	// --- for features: controller thread ---

	/** Post to every attached page. */
	public void broadcast(ToWebview message) {
		for (PageBinding page : List.copyOf(pages)) {
			postPage(page, message);
		}
	}

	/** Post to the pages displaying {@code sessionId}. */
	public void post(String sessionId, ToWebview message) {
		postTo(sessionId, message);
	}

	/**
	 * A notice for one session's pages or, with {@code null}, for every page.
	 *
	 * @return the notice, for {@link #clearNotice}; {@code null} when the session is not live
	 */
	public NoticeView notice(String sessionId, Severity severity, String text) {
		if (sessionId == null) {
			return tell(null, severity, text);
		}
		ChatSession session = sessions.get(sessionId);
		return session != null ? tell(session, severity, text) : null;
	}

	/** Withdraw a session notice (a progress message that is over). */
	public void clearNotice(String sessionId, String noticeId) {
		ChatSession session = sessions.get(sessionId);
		if (session == null || session.notices.stream().noneMatch(n -> n.id().equals(noticeId))) {
			return;
		}
		session.notices = session.notices.stream().filter(n -> !n.id().equals(noticeId)).toList();
		postTo(sessionId, new ToWebview.ClearNotice(noticeId));
	}

	/**
	 * Apply an update the client makes itself (a local reply to a slash
	 * command) to a session's transcript and forward what it emits.
	 */
	public List<ToWebview> applyLocalUpdate(String sessionId, JsonNode update) {
		ChatSession session = sessions.get(sessionId);
		return session != null ? turns.applyLocalUpdate(session, update) : List.of();
	}

	/** The next chunk of the session starts a message of its own. */
	public void closeOpenMessage(String sessionId) {
		ChatSession session = sessions.get(sessionId);
		if (session != null) {
			session.transcript = session.transcript.withMessageClosed();
		}
	}

	/**
	 * Show a user message in the transcript (reference D13).
	 *
	 * @param extras   ACP content blocks shown after the text
	 * @param delivery its badge, or {@code null}
	 * @return the message's view id, or {@code null} when the session is not live
	 */
	public String echoUserMessage(String sessionId, String text, List<JsonNode> extras, DeliveryState delivery) {
		ChatSession session = sessions.get(sessionId);
		return session != null ? turns.echoUserMessage(session, text, extras != null ? extras : List.of(), delivery) : null;
	}

	/**
	 * Show a prompt that was not echoed yet (one typed during a turn) as the
	 * user's message, exactly as a prompt that starts a turn is shown: its
	 * text, then what the {@link ChatHooks.PromptComposer} adds (images,
	 * chips), whose chips are used up by it.
	 *
	 * @param delivery its badge, or {@code null}
	 * @return the message's view id, or {@code null} when the session is not live
	 */
	public String echoPrompt(ChatHooks.PromptRequest request, DeliveryState delivery) {
		ChatSession session = request.sessionId() != null ? sessions.get(request.sessionId()) : null;
		return session != null ? turns.echoPrompt(session, request, hooks.composer(), delivery) : null;
	}

	/** Set or clear the delivery badge of a user message. */
	public void setDelivery(String sessionId, String messageId, DeliveryState delivery) {
		ChatSession session = sessions.get(sessionId);
		if (session != null) {
			turns.setDelivery(session, messageId, delivery);
		}
	}

	/**
	 * Start a turn for a message that is already echoed (a queued prompt whose
	 * time has come). Nothing happens when the session is gone, closing or
	 * busy, or the agent is not ready.
	 *
	 * @return completes when the turn has ended; {@code false} when none was started
	 */
	public CompletableFuture<Boolean> startTurn(String sessionId, List<JsonNode> blocks) {
		ChatSession session = sessions.get(sessionId);
		KiroAgent current = agent;
		if (session == null || session.closing || session.turnActive || current == null
				|| current.status() != ConnectionStatus.READY) {
			return Async.done(false);
		}
		return turns.runTurn(current, session, blocks).thenApply(done -> true);
	}

	// ---------------------------------------------------------------------
	// Pages
	// ---------------------------------------------------------------------

	private static PageBinding binding(PageHandle page) {
		return page instanceof PageBinding binding ? binding : null;
	}

	void detachPage(PageBinding page) {
		pages.remove(page);
		if (focusedPage == page) {
			focusedPage = null;
		}
	}

	void postPage(PageBinding page, ToWebview message) {
		ToWebview out = message;
		if (message instanceof ToWebview.ToolCall tool) {
			ChatSession session = page.displayed != null ? sessions.get(page.displayed) : null;
			out = new ToWebview.ToolCall(snapshots.withTerminalOutput(tool.call(), session));
		}
		try {
			page.page.post(out);
		} catch (RuntimeException e) {
			logger.error("a chat page failed to take a message: " + Async.message(e), e);
		}
	}

	/** Post to the pages displaying {@code sessionId}: the incremental messages of a session. */
	void postTo(String sessionId, ToWebview message) {
		for (PageBinding page : List.copyOf(pages)) {
			if (sessionId.equals(page.displayed)) {
				postPage(page, message);
			}
		}
	}

	/** {@code session}, then the full {@code state} as the page sees it. */
	void postSessionState(PageBinding page, String sessionId) {
		postPage(page, new ToWebview.Session(sessionId));
		postPage(page, new ToWebview.State(snapshots.forPage(page)));
	}

	void setPageSession(PageBinding page, String sessionId) {
		ChatSession session = sessionId != null ? sessions.get(sessionId) : null;
		if (session != null) {
			page.prefer = null;
			// A displayed session belongs to the page's group: the page adopts sessions of `default`.
			if (page.groupId != null && !page.groupId.equals(session.groupId)) {
				session.groupId = page.groupId;
				listing.schedule();
			}
		}
		String id = session != null ? session.id() : null;
		page.displayed = id;
		postSessionState(page, id);
	}

	void focusPage(PageBinding page) {
		focusedPage = page;
		if (page.displayed != null) {
			focus(page.displayed);
		}
	}

	// ---------------------------------------------------------------------
	// Focus
	// ---------------------------------------------------------------------

	/** Make the session the focused one. */
	void focus(String sessionId) {
		ChatSession session = sessions.get(sessionId);
		if (session == null || sessionId.equals(focusedId)) {
			return;
		}
		focusedId = sessionId;
		host.actions().turnActiveChanged(session.turnActive);
		// Seam: the focus moved. Nothing in the bundles listens to this yet; the plan is a document
		// per session and does not follow the focus (DECISIONS D13), unlike the reference's (D11).
		hooks.each(listener -> listener.focusChanged(sessionId));
	}

	void clearFocus() {
		focusedId = null;
		host.actions().turnActiveChanged(false);
		hooks.each(listener -> listener.focusChanged(null));
	}

	// ---------------------------------------------------------------------
	// What the collaborators share
	// ---------------------------------------------------------------------

	void touch(ChatSession session) {
		session.updatedAt = host.clock().getAsLong();
	}

	/**
	 * The requests of a session that wait for an answer and whose card was
	 * announced ({@link PermissionCards}). A request that is registered but
	 * not announced yet is left out: the tool call it follows may still be
	 * queued, and its card comes with the sync queued behind that.
	 */
	List<PermissionView> pendingFor(String sessionId) {
		ChatSession session = sessions.get(sessionId);
		List<PermissionView> out = new ArrayList<>();
		if (session == null) {
			return out;
		}
		for (PermissionView request : permissions.pending()) {
			if (session.knownPermissionIds.contains(request.requestId())) {
				out.add(request);
			}
		}
		return out;
	}

	/** The agent's commands plus what the client adds, for the {@code /} popup. */
	List<CommandView> commandsOf(ChatSession session) {
		return hooks.commands(session.transcript, agentInfo);
	}

	// ---------------------------------------------------------------------
	// Messages from the pages
	// ---------------------------------------------------------------------

	/** A page message, on behalf of the page it came from. From any thread. */
	void handle(FromWebview message, PageBinding page) {
		async.run(() -> async.detached(messages.dispatch(message, page), "handling a page message"));
	}

	// ---------------------------------------------------------------------
	// Notices
	// ---------------------------------------------------------------------

	/** A notice for one session (its pages) or, with {@code null}, for every page. */
	NoticeView tell(ChatSession session, Severity severity, String text) {
		NoticeView notice = new NoticeView("n-" + (++noticeSeq), severity, text);
		if (session != null) {
			session.addNotice(notice);
			postTo(session.id(), new ToWebview.Notice(notice));
		} else {
			List<NoticeView> next = new ArrayList<>(globalNotices.subList(Math.max(0, globalNotices.size() - 9), globalNotices.size()));
			next.add(notice);
			globalNotices = List.copyOf(next);
			broadcast(new ToWebview.Notice(notice));
		}
		if (severity == Severity.ERROR) {
			logger.error(text);
		}
		return notice;
	}
}
