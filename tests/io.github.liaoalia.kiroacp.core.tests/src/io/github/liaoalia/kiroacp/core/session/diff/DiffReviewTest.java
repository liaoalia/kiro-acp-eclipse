package io.github.liaoalia.kiroacp.core.session.diff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.liaoalia.kiroacp.core.host.Logger;
import io.github.liaoalia.kiroacp.core.host.Settings;
import io.github.liaoalia.kiroacp.core.host.Subscription;
import io.github.liaoalia.kiroacp.core.host.UiScheduler;
import io.github.liaoalia.kiroacp.core.model.ToolCallView;
import io.github.liaoalia.kiroacp.core.model.ToolContentView;
import io.github.liaoalia.kiroacp.core.model.ToolKind;
import io.github.liaoalia.kiroacp.core.model.ToolStatus;
import io.github.liaoalia.kiroacp.core.pure.diff.DiffState.Substitution;
import io.github.liaoalia.kiroacp.core.pure.permission.PolicyDecision;
import io.github.liaoalia.kiroacp.core.pure.text.TextRange;
import io.github.liaoalia.kiroacp.core.services.DiskTextFiles;
import io.github.liaoalia.kiroacp.core.services.PermissionManager;
import io.github.liaoalia.kiroacp.core.session.diff.FakeDiffEditors.Editor;

/**
 * The decisions of the diff review that the fake agent's scenarios do not
 * reach: an agent that writes the file itself, proposals that change while
 * they are open, answers that race an editor that is still opening, answers
 * from other threads.
 */
class DiffReviewTest {

	private static final String SESSION = "sess-1";
	private static final long SECONDS = 30;

	@TempDir
	Path dir;

	/** The controller thread. */
	private static final class ControllerThread implements UiScheduler {
		private volatile Thread thread;
		private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
			Thread created = new Thread(task, "test-controller");
			created.setDaemon(true);
			thread = created;
			return created;
		});
		final List<Throwable> failures = new CopyOnWriteArrayList<>();

		@Override
		public void execute(Runnable task) {
			executor.execute(() -> {
				try {
					task.run();
				} catch (Throwable e) {
					failures.add(e);
				}
			});
		}

		@Override
		public Subscription schedule(long delayMillis, Runnable task) {
			execute(task);
			return () -> {
			};
		}

		@Override
		public boolean isControllerThread() {
			return Thread.currentThread() == thread;
		}

		<T> T call(Callable<T> task) throws Exception {
			return executor.submit(task).get(SECONDS, TimeUnit.SECONDS);
		}

		void run(Runnable task) throws Exception {
			call(() -> {
				task.run();
				return null;
			});
		}
	}

	private static final class Preferences implements Settings {
		volatile AutoOpenDiffs autoOpenDiffs = AutoOpenDiffs.WHEN_PERMISSION_REQUIRED;

		@Override
		public String cliPath() {
			return "kiro-cli";
		}

		@Override
		public List<String> cliArgs() {
			return List.of();
		}

		@Override
		public TerminalMode terminalMode() {
			return TerminalMode.HIDDEN;
		}

		@Override
		public int terminalOutputByteLimit() {
			return 1024;
		}

		@Override
		public boolean logProtocol() {
			return false;
		}

		@Override
		public AutoOpenDiffs autoOpenDiffs() {
			return autoOpenDiffs;
		}

		@Override
		public boolean attachActiveFile() {
			return false;
		}

		@Override
		public boolean showSettingsByDefault() {
			return false;
		}

		@Override
		public boolean sessionPanelCollapsed() {
			return false;
		}

		@Override
		public boolean rememberSessionOptions() {
			return false;
		}

		@Override
		public Subscription onChange(Runnable listener) {
			return () -> {
			};
		}
	}

	private final ControllerThread controller = new ControllerThread();
	private final Preferences settings = new Preferences();
	private final PermissionManager permissions = new PermissionManager();
	private final List<String> log = new CopyOnWriteArrayList<>();
	private final Logger logger = new Logger() {
		@Override
		public void info(String message) {
			log.add("info: " + message);
		}

		@Override
		public void warn(String message) {
			log.add("warn: " + message);
		}

		@Override
		public void error(String message, Throwable cause) {
			log.add("error: " + message);
		}
	};
	private final FakeDiffEditors editors = new FakeDiffEditors(controller::isControllerThread, () -> {
	});
	/** The tool calls the transcript knows, by id. */
	private final Map<String, ToolCallView> known = new ConcurrentHashMap<>();
	/** Runs when the review looks a tool call up: where a test lets something happen in between. */
	private volatile Runnable onLookup = () -> {
	};
	private DiffReview review;
	private Path file;

	@BeforeEach
	void create() {
		file = dir.resolve("a.txt");
		review = new DiffReview(permissions, new DiskTextFiles(), controller, settings, logger, editors, (sessionId, toolCallId) -> {
			onLookup.run();
			return SESSION.equals(sessionId) ? known.get(toolCallId) : null;
		});
	}

	@AfterEach
	void close() throws Exception {
		controller.run(review::close);
		assertEquals(0, editors.count(), "closing the review closes its editors");
		assertEquals(List.of(), List.copyOf(editors.problems), "the editors are only touched on the controller thread");
		assertEquals(List.of(), List.copyOf(controller.failures));
		controller.executor.shutdown();
	}

	// ---------------------------------------------------------------------
	// The edited text is what gets written
	// ---------------------------------------------------------------------

	@Test
	void theAgentsWriteOfExactlyItsProposalIsReplacedOnce() throws Exception {
		acceptEdited("tc", "agent\n", "user\n");
		assertEquals(List.of(new Substitution(TextRange.normalizeFsPath(file.toString()), "agent\n", "user\n")),
				review.pendingSubstitutions(SESSION, "tc"));

		assertEquals("something else\n", review.interceptWrite(file.toString(), "something else\n"), "text must match");
		assertEquals("agent\n", review.interceptWrite(dir.resolve("b.txt").toString(), "agent\n"), "path must match");
		assertEquals("user\n", review.interceptWrite(file.toString().replace('\\', '/'), "agent\n"));
		assertEquals("agent\n", review.interceptWrite(file.toString(), "agent\n"), "used up");
		assertEquals(List.of(), review.pendingSubstitutions(SESSION, "tc"));
	}

	@Test
	void anUneditedProposalRecordsNothing() throws Exception {
		open("tc", 0, diff("old\n", "agent\n"));
		Editor editor = awaitEditor();
		controller.run(editor::accept);
		assertEquals(0, editors.count());
		assertEquals(List.of(), review.pendingSubstitutions(SESSION, "tc"));
		assertEquals("agent\n", review.interceptWrite(file.toString(), "agent\n"));
	}

	@Test
	void anAgentThatWroteTheFileItselfIsCorrectedWhenTheToolCallCompletes() throws Exception {
		acceptEdited("tc", "agent\n", "user\n");
		write("agent\n");

		ended("tc", ToolStatus.COMPLETED, diff("old\n", "agent\n"));

		await(() -> "user\n".equals(read()), "the user's text in the file");
		assertEquals(List.of(), review.pendingSubstitutions(SESSION, "tc"));
		assertTrue(logged("diff: applied user edits to"), log.toString());
	}

	@Test
	void theFinalDiffOfTheToolCallCountsAsTheAgentsTextToo() throws Exception {
		acceptEdited("tc", "agent\n", "user\n");
		write("agent, second thoughts\n");

		ended("tc", ToolStatus.COMPLETED, diff("old\n", "agent, second thoughts\n"));

		await(() -> "user\n".equals(read()), "the user's text in the file");
	}

	@Test
	void anAgentThatWroteSomethingElseIsLeftAlone() throws Exception {
		acceptEdited("tc", "agent\n", "user\n");
		write("something else entirely\n");

		ended("tc", ToolStatus.COMPLETED, diff("old\n", "agent\n"));

		awaitWorker();
		assertEquals("something else entirely\n", read());
		assertEquals(List.of(), review.pendingSubstitutions(SESSION, "tc"), "and the substitution is gone");
		assertEquals("agent\n", review.interceptWrite(file.toString(), "agent\n"));
	}

	@Test
	void aFileThatWasNeverWrittenIsNotCreated() throws Exception {
		acceptEdited("tc", "agent\n", "user\n");

		ended("tc", ToolStatus.COMPLETED, diff(null, "agent\n"));

		awaitWorker();
		assertFalse(Files.exists(file));
	}

	@Test
	void aFailedToolCallDropsItsSubstitutionAndClosesItsEditors() throws Exception {
		acceptEdited("tc", "agent\n", "user\n");
		write("agent\n");
		// Looked at again after accepting, through the link in the chat.
		open("tc", 0, diff("old\n", "agent\n"));
		awaitEditor();

		ended("tc", ToolStatus.FAILED, diff("old\n", "agent\n"));

		assertEquals(0, editors.count());
		assertEquals(List.of(), review.pendingSubstitutions(SESSION, "tc"));
		assertEquals(List.of(), review.openToolCallIds(SESSION));
		awaitWorker();
		assertEquals("agent\n", read(), "nothing is corrected for a tool call that failed");
	}

	// ---------------------------------------------------------------------
	// Proposals that are shown again
	// ---------------------------------------------------------------------

	@Test
	void aChangedProposalReplacesTheTextUnlessTheUserEditedIt() throws Exception {
		open("tc", 0, diff("old\n", "v1\n"));
		Editor editor = awaitEditor();
		assertEquals("v1\n", editor.text());

		open("tc", 0, diff("older\n", "v2\n"));
		assertEquals(1, editors.count());
		assertEquals(editor.id(), editors.only().id(), "the same editor");
		assertEquals("v2\n", editor.text());
		assertEquals("older\n", editor.proposal().original());

		editor.type("mine\n");
		open("tc", 0, diff("oldest\n", "v3\n"));
		assertEquals("mine\n", editor.text(), "unsaved edits are not overwritten");
		assertEquals("oldest\n", editor.proposal().original());
		assertEquals(3, editors.opens());

		// What is accepted replaces the agent's latest text.
		controller.run(editor::accept);
		assertEquals("mine\n", review.interceptWrite(file.toString(), "v3\n"));
	}

	@Test
	void savedEditsComeBackWithTheSameProposalAndGiveWayToANewOne() throws Exception {
		open("tc", 0, diff("old\n", "v1\n"));
		Editor first = awaitEditor();
		first.type("kept\n");
		controller.run(first::save);
		first.closeByUser();

		open("tc", 0, diff("old\n", "v1\n"));
		Editor second = awaitEditor();
		assertEquals(first.id(), second.id(), "the same proposal");
		assertEquals("kept\n", second.text(), "what the user saved is still the proposal");
		second.closeByUser();

		open("tc", 0, diff("old\n", "v2\n"));
		assertEquals("v2\n", awaitEditor().text(), "the agent proposed something new");
	}

	@Test
	void theOriginalOfANewFileIsWhatIsOnDisk() throws Exception {
		write("on disk\n");
		open("tc", 0, diff(null, "new\n"));
		Editor editor = awaitEditor();
		assertEquals("on disk\n", editor.proposal().original());
		assertEquals("a.txt (Kiro proposal)", editor.proposal().title());
		assertFalse(editor.pending(), "nothing waits for an answer");

		open("other", 0, new ToolContentView.Diff(dir.resolve("missing.txt").toString(), null, "new\n"));
		await(() -> editors.count() == 2, "the second editor");
		assertEquals("", editors.editors().get(1).proposal().original());
	}

	// ---------------------------------------------------------------------
	// Permission requests
	// ---------------------------------------------------------------------

	@Test
	void aRequestOpensItsDiffsAndTheEditorAnswersIt() throws Exception {
		known.put("tc", call("tc", ToolStatus.PENDING, diff("old\n", "agent\n")));
		CompletableFuture<JsonNode> answer = request("tc", diff("old\n", "agent\n"));
		String requestId = permissions.pendingIds().get(0);

		controller.run(() -> review.permissionRequested(SESSION, permissions.get(requestId)));
		Editor editor = awaitEditor();
		assertTrue(editor.pending());
		// Told twice (a page that reloads): still one editor.
		controller.run(() -> review.permissionRequested(SESSION, permissions.get(requestId)));
		assertEquals(1, editors.opens());

		editor.type("user\n");
		controller.run(editor::accept);

		assertEquals("accept", answer.get(SECONDS, TimeUnit.SECONDS).at("/outcome/optionId").asText());
		assertEquals(0, editors.count());
		assertEquals("user\n", review.interceptWrite(file.toString(), "agent\n"));
	}

	@Test
	void neverOpensNothingAndAlwaysOpensWithoutARequest() throws Exception {
		settings.autoOpenDiffs = Settings.AutoOpenDiffs.NEVER;
		known.put("tc", call("tc", ToolStatus.PENDING, diff("old\n", "agent\n")));
		CompletableFuture<JsonNode> answer = request("tc", diff("old\n", "agent\n"));
		String requestId = permissions.pendingIds().get(0);
		controller.run(() -> review.toolCallChanged(SESSION, known.get("tc")));
		controller.run(() -> review.permissionRequested(SESSION, permissions.get(requestId)));
		assertEquals(List.of(), List.copyOf(editors.events));
		permissions.decideRequest(requestId, PolicyDecision.REJECT);
		assertEquals("reject", answer.get(SECONDS, TimeUnit.SECONDS).at("/outcome/optionId").asText());

		// always: the tool call alone opens the editor; there is nothing to answer yet.
		settings.autoOpenDiffs = Settings.AutoOpenDiffs.ALWAYS;
		known.put("auto", call("auto", ToolStatus.IN_PROGRESS, diff("old\n", "agent\n")));
		controller.run(() -> review.toolCallChanged(SESSION, known.get("auto")));
		Editor editor = awaitEditor();
		assertFalse(editor.pending());
		controller.run(() -> review.toolCallChanged(SESSION, known.get("auto")));
		assertEquals(1, editors.opens(), "each diff opens by itself once");

		// The request that follows makes it answerable.
		request("auto", diff("old\n", "agent\n"));
		String second = permissions.pendingIds().get(0);
		controller.run(() -> review.permissionRequested(SESSION, permissions.get(second)));
		assertTrue(editor.pending());
		assertEquals(1, editors.opens());

		// A tool call that completes without anybody answering takes its editor along.
		permissions.cancelAll();
		await(() -> editors.count() == 0, "the editor to close with the request");
		known.put("free", call("free", ToolStatus.IN_PROGRESS, diff("old\n", "agent\n")));
		controller.run(() -> review.toolCallChanged(SESSION, known.get("free")));
		awaitEditor();
		ended("free", ToolStatus.COMPLETED, diff("old\n", "agent\n"));
		assertEquals(0, editors.count());
		assertEquals(List.of(), review.openToolCallIds(SESSION));

		// A tool call that has ended opens nothing by itself.
		controller.run(() -> review.toolCallChanged(SESSION, known.get("free")));
		assertEquals(0, editors.count());
	}

	@Test
	void aProposalAnsweredWhileItsFileIsReadNeverShows() throws Exception {
		controller.run(() -> {
			review.open(SESSION, "tc", 0, diff(null, "new\n"));
			review.reject(SESSION, "tc");
		});
		await(() -> logged("finished while opening"), "the open to give up");
		assertEquals(List.of(), List.copyOf(editors.events));
		assertEquals(List.of(), review.openToolCallIds(SESSION));

		// The same tool call id, used again, starts afresh.
		open("tc", 0, diff(null, "new\n"));
		assertEquals("new\n", awaitEditor().text());
	}

	@Test
	void aRequestAnsweredJustBeforeItsEditorOpensShowsNothing() throws Exception {
		known.put("tc", call("tc", ToolStatus.PENDING, diff("old\n", "agent\n")));
		request("tc", diff("old\n", "agent\n"));
		String requestId = permissions.pendingIds().get(0);
		// Answered between the moment the review decided to open and the moment it registers the diff.
		onLookup = () -> permissions.decideRequest(requestId, PolicyDecision.REJECT);

		controller.run(() -> review.permissionRequested(SESSION, permissions.get(requestId)));

		assertEquals(List.of(), List.copyOf(editors.events));
		assertEquals(List.of(), review.openToolCallIds(SESSION));
	}

	@Test
	void anAnswerFromAnotherThreadCountsWhatWasSavedAndClosesTheEditorOnTheControllerThread() throws Exception {
		known.put("tc", call("tc", ToolStatus.PENDING, diff("old\n", "agent\n")));
		CompletableFuture<JsonNode> answer = request("tc", diff("old\n", "agent\n"));
		String requestId = permissions.pendingIds().get(0);
		controller.run(() -> review.permissionRequested(SESSION, permissions.get(requestId)));
		Editor editor = awaitEditor();
		editor.type("saved\n");
		controller.run(editor::save);
		editor.type("not saved\n");

		// This thread is not the controller's.
		permissions.decideRequest(requestId, PolicyDecision.ALLOW);

		assertTrue(answer.isDone());
		assertEquals(List.of(new Substitution(TextRange.normalizeFsPath(file.toString()), "agent\n", "saved\n")),
				review.pendingSubstitutions(SESSION, "tc"), "on record when the agent has its answer");
		await(() -> editors.count() == 0, "the editor to close");
	}

	@Test
	void anEditorThatOutlivedItsProposalAnswersNothing() throws Exception {
		open("tc", 0, diff("old\n", "agent\n"));
		Editor stale = awaitEditor();
		controller.run(stale::reject);
		assertEquals(0, editors.count());

		// The id is used again, with a request this time; the old editor's buttons must not answer it.
		known.put("tc", call("tc", ToolStatus.PENDING, diff("old\n", "agent\n")));
		CompletableFuture<JsonNode> answer = request("tc", diff("old\n", "agent\n"));
		controller.run(() -> review.permissionRequested(SESSION, permissions.get(permissions.pendingIds().get(0))));
		Editor current = awaitEditor();
		assertNotEquals(stale.id(), current.id());
		controller.run(stale::accept);
		controller.run(() -> stale.type("stale\n"));
		controller.run(stale::save);
		assertFalse(answer.isDone());
		assertEquals(1, editors.count());
		assertEquals("agent\n", controller.call(() -> review.currentProposedText(SESSION, "tc", 0)));

		controller.run(current::reject);
		assertEquals("reject", answer.get(SECONDS, TimeUnit.SECONDS).at("/outcome/optionId").asText());
	}

	@Test
	void aSessionThatEndsTakesItsProposalsAndSubstitutionsAlong() throws Exception {
		acceptEdited("tc", "agent\n", "user\n");
		open("open", 0, diff("old\n", "agent\n"));
		awaitEditor();
		controller.run(() -> review.open("sess-2", "open", 0, diff("old\n", "theirs\n")));
		assertEquals(2, editors.count());

		controller.run(() -> review.sessionClosing(SESSION));

		assertEquals("theirs\n", editors.only().text(), "the other session keeps its editor");
		assertEquals(List.of(), review.openToolCallIds(SESSION));
		assertEquals(List.of("open"), review.openToolCallIds("sess-2"));
		assertEquals(List.of(), review.pendingSubstitutions(SESSION, "tc"));
		assertNull(controller.call(() -> review.currentProposedText(SESSION, "open", 0)));
	}

	// ---------------------------------------------------------------------

	private ToolContentView.Diff diff(String oldText, String newText) {
		return new ToolContentView.Diff(file.toString(), oldText, newText);
	}

	private static ToolCallView call(String toolCallId, ToolStatus status, ToolContentView... content) {
		return new ToolCallView(toolCallId, "Edit", ToolKind.EDIT, status, List.of(content), List.of(), null, null, null, null);
	}

	private void open(String toolCallId, int index, ToolContentView.Diff diff) throws Exception {
		controller.run(() -> review.open(SESSION, toolCallId, index, diff));
	}

	/** The tool call reaches a final status, as the transcript reports it. */
	private void ended(String toolCallId, ToolStatus status, ToolContentView.Diff... diffs) throws Exception {
		ToolCallView call = call(toolCallId, status, diffs);
		known.put(toolCallId, call);
		controller.run(() -> review.toolCallChanged(SESSION, call));
	}

	/** Opens a proposal of {@link #file}, edits it and accepts it in the editor. */
	private void acceptEdited(String toolCallId, String agentText, String userText) throws Exception {
		open(toolCallId, 0, diff("old\n", agentText));
		Editor editor = awaitEditor();
		editor.type(userText);
		controller.run(editor::accept);
		assertEquals(0, editors.count(), "accepting closes the editor");
	}

	private CompletableFuture<JsonNode> request(String toolCallId, ToolContentView.Diff diff) {
		ObjectNode request = JsonNodeFactory.instance.objectNode();
		request.put("sessionId", SESSION);
		ObjectNode toolCall = request.putObject("toolCall");
		toolCall.put("toolCallId", toolCallId).put("title", "Edit a.txt").put("kind", "edit");
		ObjectNode item = toolCall.putArray("content").addObject();
		item.put("type", "diff").put("path", diff.path()).put("oldText", diff.oldText()).put("newText", diff.newText());
		ArrayNode options = request.putArray("options");
		options.addObject().put("optionId", "accept").put("name", "Allow").put("kind", "allow_once");
		options.addObject().put("optionId", "reject").put("name", "Deny").put("kind", "reject_once");
		return permissions.request(request, null);
	}

	private Editor awaitEditor() throws Exception {
		await(() -> editors.count() == 1, "one editor; " + editors.events);
		return editors.only();
	}

	/** Returns once the review's worker has done what it was given before this call. */
	private void awaitWorker() throws Exception {
		// The worker takes one task at a time: an open that reads a file comes back after everything before it.
		int before = editors.count();
		Path probe = dir.resolve("probe-" + System.nanoTime() + ".txt");
		controller.run(() -> review.open("probe-session", "probe", 0, new ToolContentView.Diff(probe.toString(), null, "")));
		await(() -> editors.count() == before + 1, "the worker to come round");
		controller.run(() -> review.sessionClosing("probe-session"));
	}

	private void write(String text) throws Exception {
		Files.write(file, text.getBytes(StandardCharsets.UTF_8));
	}

	private String read() {
		try {
			return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
		} catch (java.io.IOException e) {
			return null;
		}
	}

	private boolean logged(String text) {
		return log.stream().anyMatch(line -> line.contains(text));
	}

	private static void await(BooleanSupplier condition, String label) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SECONDS);
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > deadline) {
				fail("Timed out waiting for " + label);
			}
			Thread.sleep(5);
		}
	}
}
