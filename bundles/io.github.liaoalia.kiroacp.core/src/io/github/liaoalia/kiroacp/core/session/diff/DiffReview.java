package io.github.liaoalia.kiroacp.core.session.diff;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import io.github.liaoalia.kiroacp.core.host.Logger;
import io.github.liaoalia.kiroacp.core.host.Settings;
import io.github.liaoalia.kiroacp.core.host.Settings.AutoOpenDiffs;
import io.github.liaoalia.kiroacp.core.host.Subscription;
import io.github.liaoalia.kiroacp.core.host.UiScheduler;
import io.github.liaoalia.kiroacp.core.model.FromWebview;
import io.github.liaoalia.kiroacp.core.model.PermissionView;
import io.github.liaoalia.kiroacp.core.model.ToolCallView;
import io.github.liaoalia.kiroacp.core.model.ToolContentView;
import io.github.liaoalia.kiroacp.core.model.ToolStatus;
import io.github.liaoalia.kiroacp.core.pure.diff.DiffState;
import io.github.liaoalia.kiroacp.core.pure.diff.DiffState.DiffSpec;
import io.github.liaoalia.kiroacp.core.pure.diff.DiffState.Substitution;
import io.github.liaoalia.kiroacp.core.pure.permission.PolicyDecision;
import io.github.liaoalia.kiroacp.core.pure.text.TextRange;
import io.github.liaoalia.kiroacp.core.pure.transcript.TranscriptState;
import io.github.liaoalia.kiroacp.core.services.DiskTextFiles;
import io.github.liaoalia.kiroacp.core.services.PendingPermission;
import io.github.liaoalia.kiroacp.core.services.PermissionManager;
import io.github.liaoalia.kiroacp.core.services.PermissionResolution;
import io.github.liaoalia.kiroacp.core.services.TextFiles;
import io.github.liaoalia.kiroacp.core.session.ChatController;
import io.github.liaoalia.kiroacp.core.session.ChatHooks;
import io.github.liaoalia.kiroacp.core.session.ChatHost;

/**
 * Review of proposed file changes in side-by-side editors (reference D7,
 * {@code features/diff.ts} with the parts of {@code chatController.ts} that
 * drive it).
 *
 * <p>A proposal opens when its permission request is shown (or, with
 * {@code autoOpenDiffs = always}, as soon as its tool call carries a diff) and
 * through the link in the chat. Accept and Reject, in the editor or on the
 * chat card, answer the pending permission of the tool call, and the editors
 * of the tool call close.
 *
 * <p>When the user edited the proposal before accepting, the edited text is
 * what gets written: accepting records {@code {path, agentText, userText}},
 * and when the agent then writes exactly {@code agentText} to that path
 * through {@code fs/write_text_file}, {@code userText} is written instead
 * ({@link #interceptWrite}). An agent that wrote the file itself is corrected
 * once the tool call completes, if the file then holds the agent's text. This
 * is best effort: an agent that writes something else is left alone.
 *
 * <p>Tool call ids are only unique within a session, so everything here is
 * kept per session and tool call.
 *
 * <p>Threads: the editors are only touched on the controller thread. A
 * permission may be answered and a file written on other threads, so the
 * bookkeeping is guarded by a lock; the substitution of an accepted proposal
 * is recorded before the agent gets its answer.
 */
public final class DiffReview implements AutoCloseable {

	/** Where the tool calls of a session are looked up; controller thread. */
	@FunctionalInterface
	public interface ToolCalls {

		/** @return the tool call as the transcript has it, or {@code null} */
		ToolCallView find(String sessionId, String toolCallId);
	}

	private static final char SEPARATOR = '\u0000';

	/** One proposal that has, or had, an editor. */
	private final class Entry implements DiffEditors.Review {
		final String id;
		final String call;
		final String sessionId;
		final String toolCallId;
		final int index;
		final String path;
		/** Guarded by the lock. */
		String original;
		/** The proposal as last saved; guarded by the lock. */
		String proposed;

		Entry(String id, String call, String sessionId, String toolCallId, int index, String path, String original,
				String proposed) {
			this.id = id;
			this.call = call;
			this.sessionId = sessionId;
			this.toolCallId = toolCallId;
			this.index = index;
			this.path = path;
			this.original = original;
			this.proposed = proposed;
		}

		@Override
		public void saved(String text) {
			synchronized (lock) {
				if (entries.get(id) == this && text != null) {
					proposed = text;
				}
			}
		}

		@Override
		public void accept() {
			if (current()) {
				DiffReview.this.accept(sessionId, toolCallId);
			}
		}

		@Override
		public void reject() {
			if (current()) {
				DiffReview.this.reject(sessionId, toolCallId);
			}
		}

		/** An editor that outlived its proposal answers nothing: the tool call id may be in use again. */
		private boolean current() {
			synchronized (lock) {
				return entries.get(id) == this;
			}
		}
	}

	private final PermissionManager permissions;
	private final TextFiles textFiles;
	private final UiScheduler scheduler;
	private final Settings settings;
	private final Logger logger;
	private final DiffEditors editors;
	private final ToolCalls toolCalls;
	/** The file system as it is, without the editors' unsaved text. */
	private final DiskTextFiles disk = new DiskTextFiles();
	private final ThreadPoolExecutor worker;
	private final List<Subscription> subscriptions = new ArrayList<>();

	private final Object lock = new Object();
	/** Guarded by the lock. Keyed by {@link #call}. */
	private final DiffState state = new DiffState(TextRange::normalizeFsPath);
	/** By editor id, oldest first. Guarded by the lock. */
	private final Map<String, Entry> entries = new LinkedHashMap<>();
	/**
	 * The opens of a tool call that still read the file. Finishing the tool
	 * call forgets them, and an open that finds itself forgotten shows
	 * nothing: a permission answered meanwhile never leaves an editor behind,
	 * and a later tool call with the same id starts afresh. Guarded by the
	 * lock.
	 */
	private final Map<String, Set<Object>> opening = new HashMap<>();
	/** Diffs opened by themselves already, by tool call: each opens once. Controller thread. */
	private final Map<String, Set<Integer>> autoOpened = new HashMap<>();
	/** Every open gets an id of its own, so an editor that is still closing is never taken for a new proposal. */
	private int generation;
	private volatile boolean closed;

	private final ChatHooks.SessionListener listener = new ChatHooks.SessionListener() {
		@Override
		public void toolCallChanged(String sessionId, ToolCallView call) {
			DiffReview.this.toolCallChanged(sessionId, call);
		}

		@Override
		public void permissionRequested(String sessionId, PermissionView request) {
			DiffReview.this.permissionRequested(sessionId, request);
		}

		@Override
		public void sessionClosing(String sessionId) {
			DiffReview.this.sessionClosing(sessionId);
		}
	};

	/**
	 * @param textFiles what an accepted, edited proposal is written through when the agent wrote
	 *                  the file itself
	 * @param scheduler the controller thread
	 * @param logger    may be {@code null}
	 */
	public DiffReview(PermissionManager permissions, TextFiles textFiles, UiScheduler scheduler, Settings settings,
			Logger logger, DiffEditors editors, ToolCalls toolCalls) {
		this.permissions = permissions;
		this.textFiles = textFiles;
		this.scheduler = scheduler;
		this.settings = settings;
		this.logger = logger != null ? logger : Logger.NONE;
		this.editors = editors;
		this.toolCalls = toolCalls;
		this.worker = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), task -> {
			Thread thread = new Thread(task, "kiro-diff");
			thread.setDaemon(true);
			return thread;
		});
		worker.allowCoreThreadTimeOut(true);
		// On the thread of the answer and before the agent hears of it: what the user
		// accepted is on record when the agent writes.
		subscriptions.add(permissions.onDidResolve(this::resolved));
	}

	/**
	 * Diff review for a chat controller: proposals open by themselves as the
	 * {@code autoOpenDiffs} setting says and through the page's
	 * {@code openDiff}, and the agent's writes go through
	 * {@link #interceptWrite}.
	 *
	 * @param host the host the controller was created with
	 */
	public static DiffReview install(ChatController controller, ChatHost host, DiffEditors editors) {
		DiffReview review = new DiffReview(controller.permissions(), host.textFiles(), host.scheduler(), host.settings(),
				host.logger(), editors, (sessionId, toolCallId) -> {
					TranscriptState transcript = controller.transcript(sessionId);
					return transcript != null ? transcript.toolCalls().get(toolCallId) : null;
				});
		controller.files().setWriteInterceptor(review::interceptWrite);
		review.subscriptions.add(() -> controller.files().setWriteInterceptor(null));
		review.subscriptions.add(controller.hooks().addListener(review.listener));
		review.subscriptions.add(controller.hooks().onMessage(FromWebview.OpenDiff.class,
				(message, page) -> review.openFromPage(page.sessionId(), message.toolCallId(), message.index())));
		return review;
	}

	// ---------------------------------------------------------------------
	// What the chat reports. Controller thread.
	// ---------------------------------------------------------------------

	/** A tool card changed by a live update. */
	public void toolCallChanged(String sessionId, ToolCallView call) {
		List<ToolContentView.Diff> diffs = diffsOf(call);
		if (diffs.isEmpty()) {
			return;
		}
		if (ended(call.status())) {
			// Forget the "already opened" marks so a later tool call re-using the id opens again.
			autoOpened.remove(call(sessionId, call.toolCallId()));
			toolCallEnded(sessionId, call.toolCallId(), call.status(), diffs);
			return;
		}
		// Without a permission request the edit goes through anyway; only `always` opens an editor for it.
		if (settings.autoOpenDiffs() == AutoOpenDiffs.ALWAYS) {
			openOnce(sessionId, call.toolCallId(), diffs, null);
		}
	}

	/** A permission card was posted. */
	public void permissionRequested(String sessionId, PermissionView request) {
		PendingPermission pending = permissions.getEntry(request.requestId());
		if (pending == null || pending.diffs().isEmpty()) {
			return;
		}
		// Editors that were open before anything was asked now have something to answer.
		for (Entry entry : entriesFor(call(sessionId, request.toolCallId()))) {
			guarded("offering Accept and Reject", () -> editors.setPending(entry.id, true));
		}
		if (settings.autoOpenDiffs() != AutoOpenDiffs.NEVER) {
			openOnce(sessionId, request.toolCallId(), pending.diffs(), request.requestId());
		}
	}

	/**
	 * The page's {@code openDiff}: the link of a diff in a tool card.
	 *
	 * @param contentIndex index of the diff within the tool call's content
	 */
	public void openFromPage(String sessionId, String toolCallId, int contentIndex) {
		ToolCallView call = sessionId != null ? toolCalls.find(sessionId, toolCallId) : null;
		if (call == null || call.content() == null || contentIndex < 0 || contentIndex >= call.content().size()
				|| !(call.content().get(contentIndex) instanceof ToolContentView.Diff diff)) {
			return;
		}
		// The proposals of a tool call are numbered among its diffs, as its permission request
		// lists them, so the link opens the editor that is already there.
		int index = 0;
		for (ToolContentView item : call.content().subList(0, contentIndex)) {
			if (item instanceof ToolContentView.Diff) {
				index++;
			}
		}
		open(sessionId, toolCallId, index, diff);
	}

	/** The session ends: its proposals have nobody to answer to. */
	public void sessionClosing(String sessionId) {
		String prefix = sessionId + SEPARATOR;
		autoOpened.keySet().removeIf(call -> call.startsWith(prefix));
		Set<String> calls = new HashSet<>();
		synchronized (lock) {
			for (String call : state.toolCallIds()) {
				if (call.startsWith(prefix)) {
					calls.add(call);
				}
			}
			for (Entry entry : entries.values()) {
				if (entry.sessionId.equals(sessionId)) {
					calls.add(entry.call);
				}
			}
			for (DiffState.Pending pending : state.substitutions()) {
				if (pending.toolCallId().startsWith(prefix)) {
					state.dropSubstitutions(pending.toolCallId());
				}
			}
		}
		for (String call : calls) {
			finish(call, false);
		}
	}

	// ---------------------------------------------------------------------
	// Public API
	// ---------------------------------------------------------------------

	/**
	 * Open (or refresh) the editor for one diff of a tool call. The original
	 * side is {@code oldText}, else the file on disk, else empty. Unsaved
	 * edits of an editor that is already open are kept. Controller thread.
	 *
	 * @param index the diff's position among the diffs of the tool call
	 */
	public void open(String sessionId, String toolCallId, int index, ToolContentView.Diff diff) {
		open(sessionId, toolCallId, index, diff, null);
	}

	private void open(String sessionId, String toolCallId, int index, ToolContentView.Diff diff, String requestId) {
		if (closed || sessionId == null || diff == null || diff.path() == null) {
			return;
		}
		String call = call(sessionId, toolCallId);
		String newText = diff.newText() != null ? diff.newText() : "";
		Object token = new Object();
		DiffSpec before;
		synchronized (lock) {
			// Registered before the file is read, so that an answer racing this open finds the
			// diff and makes the open stop.
			before = state.diffAt(call, index);
			state.register(call, index, new DiffSpec(diff.path(), diff.oldText(), newText));
			opening.computeIfAbsent(call, key -> new HashSet<>()).add(token);
		}
		boolean agentChanged = before == null || !before.newText().equals(newText);
		if (diff.oldText() != null) {
			show(sessionId, toolCallId, index, diff.path(), diff.oldText(), newText, agentChanged, token, requestId);
			return;
		}
		try {
			worker.execute(() -> {
				String onDisk = readDisk(diff.path());
				scheduler.execute(() -> show(sessionId, toolCallId, index, diff.path(), onDisk != null ? onDisk : "", newText,
						agentChanged, token, requestId));
			});
		} catch (RejectedExecutionException e) {
			// Closed meanwhile.
		}
	}

	/** Text of the proposed side, including unsaved edits in an open editor; {@code null} when unknown. Controller thread. */
	public String currentProposedText(String sessionId, String toolCallId, int index) {
		Entry entry = findEntry(call(sessionId, toolCallId), index);
		if (entry == null) {
			return null;
		}
		String live = editorText(entry);
		synchronized (lock) {
			return live != null ? live : entry.proposed;
		}
	}

	/** Tool call ids of a session that currently have diffs open, oldest first. */
	public List<String> openToolCallIds(String sessionId) {
		String prefix = sessionId + SEPARATOR;
		synchronized (lock) {
			return state.toolCallIds().stream().filter(call -> call.startsWith(prefix))
					.map(call -> call.substring(prefix.length())).toList();
		}
	}

	/** Pending substitutions of a tool call (diagnostics). */
	public List<Substitution> pendingSubstitutions(String sessionId, String toolCallId) {
		synchronized (lock) {
			return state.pendingFor(call(sessionId, toolCallId));
		}
	}

	/**
	 * Accept the proposal(s) of a tool call: record substitutions for edited
	 * files, answer the pending permission with allow, close the editors.
	 * Controller thread.
	 */
	public void accept(String sessionId, String toolCallId) {
		answer(sessionId, toolCallId, true);
	}

	/** Reject the proposal(s): answer the permission with reject, close the editors. Controller thread. */
	public void reject(String sessionId, String toolCallId) {
		answer(sessionId, toolCallId, false);
	}

	/**
	 * The {@code FsService}'s write interceptor: when the agent writes exactly
	 * the text the user edited away from, the user's text is written instead.
	 * Any thread.
	 */
	public String interceptWrite(String absPath, String content) {
		String substituted;
		synchronized (lock) {
			substituted = state.takeSubstitution(absPath, content);
		}
		if (substituted != null) {
			logger.info("diff: substituted user edits for " + absPath);
			return substituted;
		}
		return content;
	}

	/** Stops reviewing: what is open closes, nothing opens any more. Controller thread. */
	@Override
	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		for (Subscription subscription : subscriptions) {
			subscription.close();
		}
		List<Entry> open;
		synchronized (lock) {
			open = List.copyOf(entries.values());
			entries.clear();
			opening.clear();
			state.clear();
		}
		autoOpened.clear();
		closeEditors(open);
		worker.shutdown();
	}

	// ---------------------------------------------------------------------
	// Internals
	// ---------------------------------------------------------------------

	private static String call(String sessionId, String toolCallId) {
		return sessionId + SEPARATOR + toolCallId;
	}

	private static boolean ended(ToolStatus status) {
		return status == ToolStatus.COMPLETED || status == ToolStatus.FAILED || status == ToolStatus.CANCELLED;
	}

	private static List<ToolContentView.Diff> diffsOf(ToolCallView call) {
		List<ToolContentView.Diff> diffs = new ArrayList<>();
		if (call.content() != null) {
			for (ToolContentView item : call.content()) {
				if (item instanceof ToolContentView.Diff diff) {
					diffs.add(diff);
				}
			}
		}
		return diffs;
	}

	/** Opens the diffs of a tool call that did not open by themselves before. */
	private void openOnce(String sessionId, String toolCallId, List<ToolContentView.Diff> diffs, String requestId) {
		ToolCallView known = toolCalls.find(sessionId, toolCallId);
		if (known != null && ended(known.status())) {
			return;
		}
		Set<Integer> done = autoOpened.computeIfAbsent(call(sessionId, toolCallId), key -> new HashSet<>());
		for (int index = 0; index < diffs.size(); index++) {
			if (done.add(index)) {
				open(sessionId, toolCallId, index, diffs.get(index), requestId);
			}
		}
	}

	/**
	 * The second half of {@link #open}, once the original text is known.
	 * Controller thread.
	 *
	 * @param requestId the permission request the editor opens for, or {@code null}: answered
	 *                  meanwhile, there is nothing to show
	 */
	private void show(String sessionId, String toolCallId, int index, String path, String original, String newText,
			boolean agentChanged, Object token, String requestId) {
		String call = call(sessionId, toolCallId);
		Entry existing;
		String savedBefore;
		synchronized (lock) {
			Set<Object> tokens = opening.get(call);
			if (closed || tokens == null || !tokens.contains(token)) {
				logger.info("diff: " + toolCallId + " finished while opening; not showing the editor");
				return;
			}
			existing = findEntryLocked(call, index);
			savedBefore = existing != null ? existing.proposed : null;
		}
		// Asked without the lock: the editor may save, and reports that through the entry.
		String live = existing != null ? editorText(existing) : null;
		boolean pending = pendingFor(sessionId, toolCallId) != null;
		Entry entry;
		DiffEditors.Proposal proposal;
		synchronized (lock) {
			// Whoever finished the tool call since took the token away. A request that was
			// answered before this open registered could not: it is asked for here, under the
			// lock its answer needs to get any further.
			Set<Object> tokens = opening.get(call);
			boolean wanted = tokens != null && tokens.remove(token);
			if (tokens != null && tokens.isEmpty()) {
				opening.remove(call);
			}
			if (wanted && requestId != null && permissions.getEntry(requestId) == null) {
				wanted = false;
				if (entriesForLocked(call).isEmpty() && !opening.containsKey(call)) {
					state.remove(call);
				}
			}
			if (!wanted) {
				logger.info("diff: " + toolCallId + " finished while opening; not showing the editor");
				return;
			}
			if (existing != null && entries.get(existing.id) == existing) {
				entry = existing;
				entry.original = original;
				boolean unsaved = live != null && !live.equals(savedBefore);
				if (unsaved) {
					// Showing the editor again must not cost the user's edits: they count as saved.
					entry.proposed = live;
				} else if (agentChanged) {
					entry.proposed = newText;
				}
			} else {
				String id = "diff-" + (++generation);
				entry = new Entry(id, call, sessionId, toolCallId, index, path, original, newText);
				entries.put(id, entry);
			}
			proposal = new DiffEditors.Proposal(entry.id, path, fileName(path) + " (Kiro proposal)", entry.original,
					entry.proposed, pending);
		}
		guarded("opening the editor of " + path, () -> editors.open(proposal, entry));
	}

	private void answer(String sessionId, String toolCallId, boolean accepted) {
		finish(call(sessionId, toolCallId), accepted);
		PendingPermission pending = pendingFor(sessionId, toolCallId);
		if (pending != null) {
			permissions.decideRequest(pending.requestId(), accepted ? PolicyDecision.ALLOW : PolicyDecision.REJECT);
		}
	}

	/** A permission request was answered, by whoever and wherever. */
	private void resolved(PermissionResolution resolution) {
		finish(call(resolution.sessionId(), resolution.toolCallId()),
				resolution.outcome() == PermissionResolution.Outcome.ALLOW);
	}

	private void toolCallEnded(String sessionId, String toolCallId, ToolStatus status, List<ToolContentView.Diff> diffs) {
		String call = call(sessionId, toolCallId);
		List<Substitution> left;
		boolean open;
		synchronized (lock) {
			left = state.pendingFor(call);
			state.dropSubstitutions(call);
			open = state.hasDiffs(call) || !entriesForLocked(call).isEmpty();
		}
		if (status == ToolStatus.COMPLETED) {
			if (!left.isEmpty()) {
				reconcile(left, diffs);
			}
			// An editor that was opened without a question has nothing left to show.
			if (open && pendingFor(sessionId, toolCallId) == null) {
				finish(call, false);
			}
		} else if (open) {
			finish(call, false);
		}
	}

	/**
	 * The tool call completed and the agent never wrote through the client:
	 * where the file now holds the agent's text (its proposal, or the final
	 * diff's), the user's text is written over it. Anything else is left
	 * alone.
	 */
	private void reconcile(List<Substitution> substitutions, List<ToolContentView.Diff> diffs) {
		Map<String, String> finalTexts = new HashMap<>();
		for (ToolContentView.Diff diff : diffs) {
			if (diff.path() != null && diff.newText() != null) {
				finalTexts.putIfAbsent(TextRange.normalizeFsPath(diff.path()), diff.newText());
			}
		}
		try {
			worker.execute(() -> {
				for (Substitution sub : substitutions) {
					String onDisk = readDisk(sub.path());
					if (onDisk == null) {
						continue;
					}
					boolean agents = onDisk.equals(sub.agentText()) || onDisk.equals(finalTexts.get(sub.path()));
					if (agents && !onDisk.equals(sub.userText())) {
						try {
							textFiles.write(Path.of(sub.path()), sub.userText());
							logger.info("diff: applied user edits to " + sub.path() + " after completion");
						} catch (IOException | RuntimeException e) {
							logger.warn("diff: could not apply user edits to " + sub.path() + ": " + e);
						}
					}
				}
			});
		} catch (RejectedExecutionException e) {
			// Closed meanwhile.
		}
	}

	/**
	 * Record substitutions (when accepted), forget the diffs of the tool call
	 * and close its editors. Idempotent. Any thread; off the controller
	 * thread the editors cannot be asked for unsaved edits, so what was saved
	 * counts, and they close a moment later.
	 */
	private void finish(String call, boolean accepted) {
		boolean onControllerThread = scheduler.isControllerThread();
		Map<String, String> live = new HashMap<>();
		if (accepted && onControllerThread) {
			for (Entry entry : entriesFor(call)) {
				String text = editorText(entry);
				if (text != null) {
					live.put(entry.id, text);
				}
			}
		}
		List<Entry> closing;
		synchronized (lock) {
			closing = entriesForLocked(call);
			if (closing.isEmpty() && !state.hasDiffs(call) && !opening.containsKey(call)) {
				return;
			}
			opening.remove(call);
			if (accepted) {
				for (Entry entry : closing) {
					DiffSpec spec = state.diffAt(call, entry.index);
					String userText = live.getOrDefault(entry.id, entry.proposed);
					if (spec != null && userText != null && !userText.equals(spec.newText())) {
						state.addSubstitution(call, new Substitution(spec.path(), spec.newText(), userText));
						logger.info("diff: recorded user edits for " + spec.path());
					}
				}
			}
			for (Entry entry : closing) {
				entries.remove(entry.id);
			}
			state.remove(call);
		}
		if (closing.isEmpty()) {
			return;
		}
		if (onControllerThread) {
			closeEditors(closing);
		} else {
			if (accepted) {
				logger.info("diff: accepted off the controller thread; unsaved edits of an open editor are not included");
			}
			scheduler.execute(() -> closeEditors(closing));
		}
	}

	private void closeEditors(List<Entry> closing) {
		for (Entry entry : closing) {
			guarded("closing the editor of " + entry.path, () -> editors.close(entry.id));
		}
	}

	/** @return the editor's text, or {@code null} when there is no editor or it cannot say */
	private String editorText(Entry entry) {
		try {
			return editors.proposedText(entry.id);
		} catch (RuntimeException e) {
			logger.warn("diff: reading the editor of " + entry.path + " failed: " + e);
			return null;
		}
	}

	private void guarded(String what, Runnable task) {
		try {
			task.run();
		} catch (RuntimeException e) {
			logger.warn("diff: " + what + " failed: " + e);
		}
	}

	/** The oldest pending request of the tool call in its session, or {@code null}. */
	private PendingPermission pendingFor(String sessionId, String toolCallId) {
		for (String requestId : permissions.pendingIds()) {
			PendingPermission pending = permissions.getEntry(requestId);
			if (pending != null && pending.sessionId().equals(sessionId) && pending.toolCallId().equals(toolCallId)) {
				return pending;
			}
		}
		return null;
	}

	private Entry findEntry(String call, int index) {
		synchronized (lock) {
			return findEntryLocked(call, index);
		}
	}

	private Entry findEntryLocked(String call, int index) {
		for (Entry entry : entries.values()) {
			if (entry.call.equals(call) && entry.index == index) {
				return entry;
			}
		}
		return null;
	}

	private List<Entry> entriesFor(String call) {
		synchronized (lock) {
			return entriesForLocked(call);
		}
	}

	private List<Entry> entriesForLocked(String call) {
		return entries.values().stream().filter(entry -> entry.call.equals(call))
				.sorted(Comparator.comparingInt((Entry entry) -> entry.index)).toList();
	}

	private String readDisk(String path) {
		try {
			return disk.read(Path.of(path));
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	private static String fileName(String path) {
		int cut = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
		String name = path.substring(cut + 1);
		return name.isEmpty() ? "file" : name;
	}
}
