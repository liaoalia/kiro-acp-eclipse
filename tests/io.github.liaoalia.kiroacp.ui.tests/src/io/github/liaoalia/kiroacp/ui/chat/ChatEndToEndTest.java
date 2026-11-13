package io.github.liaoalia.kiroacp.ui.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.liaoalia.kiroacp.core.acp.api.AgentProcessOptions;
import io.github.liaoalia.kiroacp.core.acp.api.ClientServices;
import io.github.liaoalia.kiroacp.core.acp.api.KiroAgent;
import io.github.liaoalia.kiroacp.core.acp.impl.KiroAgents;
import io.github.liaoalia.kiroacp.core.host.Logger;
import io.github.liaoalia.kiroacp.core.session.ChatController;
import io.github.liaoalia.kiroacp.core.session.history.PastSessions;
import io.github.liaoalia.kiroacp.core.session.diff.DiffEditors;
import io.github.liaoalia.kiroacp.core.session.diff.DiffReview;
import io.github.liaoalia.kiroacp.core.session.steering.Steering;
import io.github.liaoalia.kiroacp.ui.services.Prompts;

/**
 * The chat end to end: the real page in a real browser widget, linked as a
 * chat view links it to a real controller on the Eclipse services, and the
 * reference's fake agent as a real child process. Everything is done through
 * the page's DOM (typing, clicking) and read back from it, or from the disk.
 *
 * <p>What is not real: there is no workbench, so no view, no Console view and
 * no editor; the agent is {@code tests/fixtures/fake-agent/agent.mjs} instead
 * of {@code kiro-cli}; and questions to the user are refused by a recording
 * {@link Prompts} instead of being shown in a dialog.
 *
 * <p>No shell is ever opened: nothing appears on the desktop and no input is
 * sent to it. Each test creates one browser widget. Needs {@code node} on the
 * PATH and {@code npm ci} run in the fake agent's folder.
 */
class ChatEndToEndTest {

	private static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(60);
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final String PROJECT = "kiro-e2e";

	/** One request of the agent to the host and how it was answered. */
	private record Served(String method, JsonNode request, CompletableFuture<?> answer) {
	}

	/** Refuses every question at once and shows nothing. */
	private static final class RefusingPrompts implements Prompts {
		final List<Question> asked = new CopyOnWriteArrayList<>();
		final List<String> told = new CopyOnWriteArrayList<>();

		@Override
		public CompletionStage<Boolean> ask(Question question) {
			asked.add(question);
			return CompletableFuture.completedFuture(Boolean.FALSE);
		}

		@Override
		public Notice tell(String title, String message, String done) {
			told.add(title + ": " + message);
			return new Notice() {
				@Override
				public CompletionStage<Void> acknowledged() {
					return new CompletableFuture<>();
				}

				@Override
				public void dismiss() {
				}
			};
		}
	}

	@TempDir
	Path temp;

	private final IWorkspace resources = ResourcesPlugin.getWorkspace();
	private final RefusingPrompts prompts = new RefusingPrompts();
	private final List<Served> served = new CopyOnWriteArrayList<>();
	private final List<AgentProcessOptions> launches = new CopyOnWriteArrayList<>();
	private final List<String> protocol = new CopyOnWriteArrayList<>();
	private final List<String> copied = new ArrayList<>();
	private final List<String> opened = new ArrayList<>();
	private final String viewId = "e2e-" + System.nanoTime();

	private Display display;
	private boolean ownDisplay;
	private Shell shell;
	/** The one project of the workspace, in the temp folder; the agent's working directory. */
	private Path root;
	private IProject project;
	private KiroChat.Services services;
	private ChatController controller;
	private ChatPageLink link;

	@BeforeEach
	void createWorkspaceAndShell() throws Exception {
		for (IProject each : resources.getRoot().getProjects()) {
			each.delete(true, true, null);
		}
		// The temp folder may be spelled with a short (8.3) name; Eclipse reports locations with the long one.
		root = temp.toRealPath().resolve("checkout");
		project = resources.getRoot().getProject(PROJECT);
		IProjectDescription description = resources.newProjectDescription(PROJECT);
		description.setLocationURI(root.toUri());
		project.create(description, null);
		project.open(null);

		display = Display.getCurrent();
		if (display == null) {
			display = new Display();
			ownDisplay = true;
		}
		shell = new Shell(display);
		shell.setLayout(new FillLayout());
		shell.setSize(480, 640);
	}

	@AfterEach
	void close() throws CoreException {
		try {
			if (link != null) {
				// The browser's storage outlives the test run.
				evaluate("try { window.localStorage.removeItem('kiroAcp.webviewState." + viewId + "'); } catch (e) {} return true;");
				link.close();
			}
			if (controller != null) {
				// The agent's working directory is the temp folder: it must be gone before the folder can be.
				CompletableFuture<Void> down = controller.shutdown();
				await(down::isDone, "the controller to shut down");
			}
			if (services != null) {
				services.workspace().dispose();
			}
		} finally {
			for (Shell each : display.getShells()) {
				each.dispose();
			}
			if (ownDisplay && !display.isDisposed()) {
				display.dispose();
			}
			project.delete(false, true, null);
		}
	}

	// --- a chat turn ---

	@Test
	void aPromptTypedInThePageIsAnsweredInThePage() throws IOException {
		start("chat");

		type("What is the answer?");

		awaitPage("return document.querySelectorAll('.items .msg-user').length === 1;", "the prompt to be echoed");
		assertEquals("What is the answer?", text(".items .msg-user"));
		// Three chunks from the agent, one message in the page.
		await(() -> "The answer is 42.".equals(agentText()), "the streamed reply to be complete");
		awaitIdle();
		assertEquals(1.0, evaluate("return document.querySelectorAll('.items .msg-agent').length;"));
		assertEquals("", evaluate("return document.querySelector('.composer-box textarea').value;"), "the input box is empty again");
		assertEquals(0.0, evaluate("return document.querySelectorAll('.items .tool').length;"), "a chat turn has no tool cards");
		// The session is named after its first prompt, and listed as no longer running.
		await(() -> "What is the answer?".equals(text(".header .brand-text")), "the session to take its title");
		awaitPage("return !document.querySelector('.session-panel .sp-status-running');", "the session list to show the turn as over");
		assertEquals(1, launches.size(), "one agent process");
		assertEquals(root, launches.get(0).cwd().toRealPath(), "the agent runs in the project");
		assertEquals(List.of(), served.stream().map(Served::method).toList(), "a chat turn asks nothing of the host");
	}

	// --- tool cards, file access, a command, a permission allowed ---

	@Test
	void theTourReadsAFileRunsACommandAndWritesAFileOnceAllowed() throws Exception {
		Files.write(root.resolve("README.md"), "# Tour\nsecond line\n".getBytes(StandardCharsets.UTF_8));
		start("tour");

		type("Show me around");

		// The turn stops at the question whether hello.txt may be created.
		awaitPage("return document.querySelectorAll('.items .perm').length === 1;", "the permission card");
		awaitPage("return !!document.querySelector('.status-pill.status-needs_input');", "the header to say that an answer is needed");
		assertEquals("Hello! I am the fake Kiro agent. Let me look around.", agentText());
		assertEquals(Boolean.TRUE, evaluate("return !!document.querySelector('.items .msg-thought');"), "the agent's thought");

		// fs/read_text_file: the card in the page, the file's text in the host's answer.
		assertEquals("Read README.md", text(tool("call-read") + " .tool-title"));
		assertEquals(Boolean.TRUE, evaluate("return document.querySelector(" + js(tool("call-read")) + ").classList.contains('tool-completed');"));
		assertEquals(1, served("readTextFile").size(), served.toString());
		Served read = served("readTextFile").get(0);
		assertEquals(root.resolve("README.md"), Path.of(read.request().path("path").asText()).toRealPath());
		assertEquals("# Tour\nsecond line\n", ((JsonNode) read.answer().getNow(null)).path("content").asText());

		// terminal/*: the command ran in the project and its output is in its card, once that is unfolded.
		assertEquals("Run echo hello", text(tool("call-exec") + " .tool-title"));
		assertEquals(Boolean.TRUE, evaluate("return document.querySelector(" + js(tool("call-exec") + " .tool-body") + ").hidden;"));
		click(tool("call-exec") + " .tool-head");
		awaitPage("return !document.querySelector(" + js(tool("call-exec") + " .tool-body") + ").hidden;", "the command's card to unfold");
		assertEquals("hello", text(tool("call-exec") + " .term-output"));
		assertEquals("exit 0", text(tool("call-exec") + " .exit-code.ok"));
		assertEquals(root, Path.of(served("createTerminal").get(0).request().path("cwd").asText()).toRealPath());

		// The agent wanted a web page opened: the user was asked, said no, and nothing was opened.
		assertEquals(List.of("https://example.invalid/oauth"), prompts.asked.stream().map(Prompts.Question::detail).toList());

		// The permission card: what is asked, and nothing written before the answer.
		assertEquals("Create hello.txt", text(".items .perm .perm-title"));
		assertEquals("fs_write", text(".items .perm .perm-cap"));
		assertEquals(Boolean.TRUE, evaluate("return !!document.querySelector(" + js(tool("call-edit") + ".tool-pending .perm") + ");"),
				"the card belongs to the edit's tool card");
		assertFalse(Files.exists(root.resolve("hello.txt")), "nothing is written before the answer");
		assertEquals(List.of(), served("writeTextFile"));

		click(".items .perm .perm-allow_once");

		await(() -> agentText().endsWith("All done."), "the turn to go on to its end");
		awaitIdle();
		assertEquals("hello from kiro\n", read(root.resolve("hello.txt")));
		awaitPage("return document.querySelectorAll('.items .perm').length === 0;", "the card to go");
		awaitPage("return document.querySelectorAll('.items .tool.tool-completed').length === 3;", "the read, the command and the edit to be done");
		assertEquals(3.0, evaluate("return document.querySelectorAll('.items .tool').length;"));
		assertEquals("Plan: 1 of 3 done", text(".planbar .plan-text"));
		assertEquals(1, served("writeTextFile").size(), served.toString());
		assertEquals(List.of(), prompts.told);
	}

	// --- a permission rejected ---

	@Test
	void aRejectedPermissionWritesNothingAndTheTurnGoesOn() throws Exception {
		Files.write(root.resolve("config.json"), "{}".getBytes(StandardCharsets.UTF_8));
		start("permission-reject");

		type("Turn debugging on");

		awaitPage("return document.querySelectorAll('.items .perm').length === 1;", "the permission card");
		assertEquals("Overwrite config.json", text(".items .perm .perm-title"));
		assertEquals("Allow|Deny", String.valueOf(evaluate(
				"return Array.from(document.querySelectorAll('.items .perm .perm-actions button')).map(function (b) { return b.innerText.trim(); }).join('|');")));

		click(".items .perm .perm-reject_once");

		await(() -> "Understood.".equals(agentText()), "the agent to take the refusal");
		awaitIdle();
		assertEquals("{}", read(root.resolve("config.json")), "the file is as it was");
		assertEquals(List.of(), served("writeTextFile"), "the agent did not even try");
		awaitPage("return document.querySelectorAll('.items .perm').length === 0;", "the card to go");
		// The fake agent reports a denied call as failed.
		awaitPage("return document.querySelector(" + js(tool("call-edit")) + ").classList.contains('tool-failed');", "the edit's card to show it failed");
	}

	// --- a proposal reviewed in its editor ---

	@Test
	void theProposalOpensForItsCardAndAcceptingItInTheEditorWritesWhatTheUserMadeOfIt() throws Exception {
		Files.write(root.resolve("config.json"), "{}".getBytes(StandardCharsets.UTF_8));
		start("permission-reject");
		// The compare editor needs a workbench; this one is a record of what it was asked to show.
		List<DiffEditors.Proposal> shown = new ArrayList<>();
		List<DiffEditors.Review> reviews = new ArrayList<>();
		List<String> closed = new ArrayList<>();
		String edited = "{ \"debug\": false }\n";
		DiffReview.install(controller, services.host(), new DiffEditors() {
			@Override
			public void open(Proposal proposal, Review review) {
				shown.add(proposal);
				reviews.add(review);
			}

			@Override
			public String proposedText(String id) {
				return closed.contains(id) || shown.isEmpty() ? null : edited;
			}

			@Override
			public void setPending(String id, boolean pending) {
			}

			@Override
			public void close(String id) {
				closed.add(id);
			}
		});

		type("Turn debugging on");

		awaitPage("return document.querySelectorAll('.items .perm').length === 1;", "the permission card");
		await(() -> shown.size() == 1, "the proposal to open for its request");
		assertEquals("config.json (Kiro proposal)", shown.get(0).title());
		assertEquals(root.resolve("config.json"), Path.of(shown.get(0).path()).toRealPath());
		assertEquals("{}", shown.get(0).original());
		assertEquals("{ \"debug\": true }\n", shown.get(0).proposed());
		assertTrue(shown.get(0).pending(), "the request waits: the editor offers Accept and Reject");

		// The card's link shows the same proposal again.
		click(".items .perm .perm-diff button");
		await(() -> shown.size() == 2, "the link to show the proposal");
		assertEquals(shown.get(0).id(), shown.get(1).id());
		assertEquals(edited, shown.get(1).proposed(), "with what the user typed meanwhile");

		// Accept in the editor: this is the UI thread, as in the workbench.
		reviews.get(1).accept();

		await(() -> "Understood.".equals(agentText()), "the turn to go on");
		awaitIdle();
		assertEquals(List.of(shown.get(0).id()), closed, "the editor closes with the answer");
		assertEquals("{ \"debug\": true }\n", served("writeTextFile").get(0).request().path("content").asText(), "what the agent wrote");
		assertEquals(edited, read(root.resolve("config.json")), "what the file got");
		awaitPage("return document.querySelectorAll('.items .perm').length === 0;", "the card to go");
		awaitPage("return document.querySelector(" + js(tool("call-edit")) + ").classList.contains('tool-completed');",
				"the edit's card to show it completed");
	}

	// --- Stop ---

	@Test
	void theStopButtonEndsARunningTurn() {
		// 40 chunks, 50 ms apart.
		start("cancel");
		String stop = ".composer-box [data-action=\"cancel\"]:not([hidden])";
		assertEquals(0.0, evaluate("return document.querySelectorAll(" + js(stop) + ").length;"), "no Stop while nothing runs");

		type("Count for me");

		// The reply is in the page while it is still being written.
		await(() -> agentText().contains("tick 3 "), "the reply to be streaming");
		assertFalse(agentText().contains("tick 39"), "the turn is still running");
		assertEquals(Boolean.TRUE, evaluate("return !!document.querySelector('.status-pill.status-working');"));

		click(stop);

		awaitIdle();
		String stopped = agentText();
		assertFalse(stopped.contains("tick 39"), "the turn was cut short; " + describe());
		assertEquals(0.0, evaluate("return document.querySelectorAll(" + js(stop) + ").length;"), "Stop is Send again");
		// The agent was told and ended the turn itself; the page did not merely stop listening.
		assertEquals(1, protocol.stream().filter(line -> line.startsWith("SEND") && line.contains("\"session/cancel\"")).count(), describe());
		assertEquals(1, protocol.stream().filter(line -> line.startsWith("RECV") && line.contains("\"stopReason\":\"cancelled\"")).count(),
				describe());
		pump(500);
		assertEquals(stopped, agentText(), "nothing more arrives");
		// And the session takes the next prompt.
		type("Again");
		await(() -> agentText().length() > stopped.length()
				&& Double.valueOf(2).equals(evaluate("return document.querySelectorAll('.items .msg-user').length;")),
				"a second turn after the stopped one");
		click(stop);
		awaitIdle();
	}

	// --- New session ---

	@Test
	void theNewButtonStartsAnEmptySessionAndTheFirstOneCanBeReturnedTo() {
		start("chat");
		String first = link.page().sessionId();
		type("First question");
		await(() -> "The answer is 42.".equals(agentText()), "the first session's reply");
		awaitIdle();

		click(".header [data-action=\"new-session\"]");

		await(() -> link.page().sessionId() != null && !first.equals(link.page().sessionId()), "the page to get another session");
		String second = link.page().sessionId();
		awaitPage("return document.querySelector('.hdr-session-select').value === " + js(second) + ";", "the page to display the new session");
		awaitPage("return document.querySelectorAll('.items > *').length === 0 && !document.querySelector('.transcript .empty').hidden;",
				"an empty transcript with the greeting of a new session");
		awaitPage("return document.querySelectorAll('.session-panel .sp-row').length === 2;", "both sessions to be listed");
		assertEquals(1, launches.size(), "the sessions share one agent process");

		type("Second question");
		await(() -> "The answer is 42.".equals(agentText()), "the second session's reply");
		awaitIdle();
		assertEquals("Second question", text(".items .msg-user"));

		// Back to the first one, through the page's session list.
		click(".session-panel .sp-row[data-session-id=" + js(first) + "]");
		await(() -> first.equals(link.page().sessionId()), "the page to display the first session again");
		awaitPage("return document.querySelectorAll('.items .msg-user').length === 1"
				+ " && document.querySelector('.items .msg-user').innerText.trim() === 'First question';", "the first session's transcript");
		assertEquals("The answer is 42.", agentText());
	}

	// --- Past sessions ---

	@Test
	void theHistoryButtonRestoresAPastSessionOfTheAgentIntoThePage() {
		start("chat");
		String first = link.page().sessionId();
		// The dialog's place: the user picks the first of what is offered.
		List<List<String>> offered = new ArrayList<>();
		PastSessions.install(controller, entries -> {
			offered.add(entries.stream().map(entry -> entry.sessionId() + ": " + entry.label()).toList());
			return CompletableFuture.completedFuture(entries.get(0).sessionId());
		});

		click(".header .hdr-history");

		await(() -> "past-1".equals(link.page().sessionId()), "the page to display the restored session");
		awaitPage("return document.querySelectorAll('.items .msg-user').length === 1"
				+ " && document.querySelectorAll('.items .msg-agent').length === 2"
				+ " && document.querySelectorAll('.items .tool').length === 1;", "the replayed conversation");
		assertEquals(List.of(List.of("past-1: Investigate flaky CI", "past-2: Add retry to fetch")), offered);
		// The history the agent replayed, user messages included, in the order it happened.
		assertEquals("Why is CI flaky?", text(".items .msg-user"));
		assertEquals("Let me look at .github/workflows/ci.yml.\nThe test job has no retry and the network step times out.", agentText());
		assertEquals("Read .github/workflows/ci.yml", text(tool("call-past-1-read") + " .tool-title"));
		assertEquals(Boolean.TRUE,
				evaluate("return document.querySelector(" + js(tool("call-past-1-read")) + ").classList.contains('tool-completed');"));
		assertEquals("msg-user,msg-agent,tool,msg-agent", evaluate("""
				return Array.from(document.querySelectorAll('.items .msg-user, .items .msg-agent, .items .tool')).map(function (e) {
					return ['msg-user', 'msg-agent', 'tool'].filter(function (c) { return e.classList.contains(c); })[0];
				}).join(',');"""));
		// It is the agent's title that names it, and the session shown before is still there.
		await(() -> "Investigate flaky CI".equals(text(".header .brand-text")), "the restored session's title");
		awaitPage("return document.querySelectorAll('.session-panel .sp-row').length === 2 && !!document.querySelector("
				+ js(".session-panel .sp-row[data-session-id=" + js(first) + "]") + ");", "both sessions to be listed");
		awaitPage("return document.body.innerText.indexOf('Restoring session') < 0;", "the progress notice to go");
		awaitIdle();

		// A restored session is an ordinary one.
		type("And now?");
		await(() -> agentText().endsWith("The answer is 42."), "the reply in the restored session");
		awaitIdle();
		assertEquals(2.0, evaluate("return document.querySelectorAll('.items .msg-user').length;"));
		assertEquals("past-1", link.page().sessionId());
		assertEquals(1, launches.size(), "one agent process");
	}

	// --- Prompts during a turn ---

	@Test
	void aPromptTypedWhileTheAgentAnswersWaitsWithABadgeAndIsSentWhenTheTurnIsOver() {
		start("chat");
		Steering steering = Steering.install(controller);
		// As for an agent that cannot steer: what is typed during a turn waits for its end.
		steering.setSteerUnsupported(link.page().sessionId(), true);
		// The page learns from its state that prompts are taken during a turn; a page opened after the feature has it from the start.
		link.page().setSession(link.page().sessionId());
		String stop = ".composer-box [data-action=\"cancel\"]:not([hidden])";

		// 20 chunks, 100 ms apart.
		type("/scenario slow");
		await(() -> agentText().contains("slow 1 "), "the reply to be streaming");
		assertEquals(1.0, evaluate("return document.querySelectorAll(" + js(stop) + ").length;"), "Stop has a button of its own");

		type("And what is the answer?");

		awaitPage("return document.querySelectorAll('.items .msg-user .delivery.delivery-queued').length === 1;",
				"the prompt to be shown as queued; " + describe());
		assertFalse(agentText().contains("slow 19"), "typed and shown while the turn was still running");
		assertEquals(2.0, evaluate("return document.querySelectorAll('.items .msg-user').length;"));
		assertEquals("", evaluate("return document.querySelector('.composer-box textarea').value;"), "the input box is empty again");

		// The turn ends, the queued prompt is sent by itself and answered by the launch scenario.
		await(() -> agentText().contains("slow 19") && agentText().endsWith("The answer is 42."), "the queued prompt's reply");
		awaitIdle();
		assertEquals(0.0, evaluate("return document.querySelectorAll('.items .delivery').length;"), "no badge is left");
		assertEquals(2.0, evaluate("return document.querySelectorAll('.items .msg-user').length;"), "the queued prompt was shown once");
		assertEquals(2, protocol.stream().filter(line -> line.startsWith("SEND") && line.contains("\"session/prompt\"")).count(), describe());
		assertEquals(0, protocol.stream().filter(line -> line.contains("_session/steer")).count(), "the agent was not asked to steer");
	}

	// --- helpers ---

	private static String tool(String toolCallId) {
		return ".items .tool:has(> .tool-head[data-tool=\"" + toolCallId + "\"])";
	}

	/** What the agent said, message by message, as the page shows it. */
	private String agentText() {
		return String.valueOf(evaluate(
				"return Array.from(document.querySelectorAll('.items .msg-agent')).map(function (m) { return m.innerText.trim(); }).join('\\n');"));
	}

	/** Waits until the page shows no turn running and nothing waiting for an answer. */
	private void awaitIdle() {
		awaitPage("return !!document.querySelector('.status-pill.status-ready:not(.status-working):not(.status-needs_input)')"
				+ " && !!document.querySelector('.composer-box .send-btn[data-action=\"send\"]');", "the turn to end");
	}

	/** Lets the display run for that long. */
	private void pump(long millis) {
		long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
		await(() -> System.nanoTime() > until, "time to pass");
	}

	/** Links a page to a controller whose agent is the fake one playing that recording, and waits for its session. */
	private void start(String scenario) {
		assertFalse(PlatformUI.isWorkbenchRunning(), "these tests are written for a runtime without a workbench");
		Path script = fakeAgentScript();
		services = KiroChat.services(display, prompts);
		assertEquals(List.of(root), services.workspace().roots());
		controller = ChatController.create(services.host(), (options, client) -> {
			launches.add(options);
			return KiroAgents.create(new AgentProcessOptions("node", List.of(script.toString(), "acp", "--scenario", scenario),
					options.cwd(), options.env(), tracing(options.logger()), options.clientVersion()), recording(client));
		});
		// Never the real clipboard, never the real system browser.
		link = new ChatPageLink(shell, viewId, controller, services.logger(),
				WebChat.Seams.defaults().withClipboard(copied::add).withOpener(opened::add));
		shell.layout();
		awaitPage("return !!document.querySelector('.status-pill.status-ready') && !!document.querySelector('.composer-box textarea');",
				"the page to show a connected agent");
		await(() -> link.page().sessionId() != null, "the page to get a session");
	}

	/** {@code tests/fixtures/fake-agent/agent.mjs}; the test bundle's pom says where the fixtures are. */
	private static Path fakeAgentScript() {
		String dir = System.getProperty("kiroacp.fixtures");
		if (dir == null || dir.isBlank()) {
			throw new IllegalStateException("System property kiroacp.fixtures is not set (the test bundle's pom sets it)");
		}
		Path script = Path.of(dir).toAbsolutePath().normalize().resolve("fake-agent").resolve("agent.mjs");
		assertTrue(Files.isRegularFile(script), "Fake agent not found: " + script);
		assertTrue(Files.isDirectory(script.resolveSibling("node_modules")),
				"Run `npm --prefix tests/fixtures/fake-agent ci` first: no node_modules next to " + script);
		return script;
	}

	/** The host's services, with every request of the agent written down on its way through. */
	private ClientServices recording(ClientServices real) {
		return (ClientServices) Proxy.newProxyInstance(ClientServices.class.getClassLoader(), new Class<?>[] { ClientServices.class },
				(proxy, method, args) -> {
					Object answer;
					try {
						answer = method.invoke(real, args);
					} catch (InvocationTargetException e) {
						throw e.getCause();
					}
					if (args != null && args.length > 0 && args[0] instanceof JsonNode request && answer instanceof CompletableFuture<?> future) {
						served.add(new Served(method.getName(), request, future));
					}
					return answer;
				});
	}

	/** The agent's logger, with the protocol kept for the message of a failure. */
	private Logger tracing(Logger real) {
		return new Logger() {
			@Override
			public void info(String message) {
				real.info(message);
			}

			@Override
			public void warn(String message) {
				protocol.add("warn " + message);
				real.warn(message);
			}

			@Override
			public void error(String message, Throwable cause) {
				protocol.add("error " + message);
				real.error(message, cause);
			}

			@Override
			public boolean traces() {
				return true;
			}

			@Override
			public void trace(Direction direction, String payload) {
				protocol.add(direction + " " + payload);
				if (real.traces()) {
					real.trace(direction, payload);
				}
			}
		};
	}

	private List<Served> served(String method) {
		return served.stream().filter(each -> method.equals(each.method())).toList();
	}

	/** Types into the input box and clicks Send, as far as a script can. */
	private void type(String text) {
		assertEquals("sent", evaluate("""
				var input = document.querySelector('.composer-box textarea');
				var send = document.querySelector('.composer-box [data-action="send"]');
				if (!input || !send) return 'no input box or no Send button';
				input.value = %s;
				input.dispatchEvent(new Event('input', { bubbles: true }));
				if (send.disabled) return 'Send is disabled';
				send.click();
				return 'sent';""".formatted(js(text))));
	}

	/** Clicks the one element the selector names. */
	private void click(String selector) {
		assertEquals("clicked", evaluate("""
				var found = document.querySelectorAll(%s);
				if (found.length !== 1) return found.length + ' elements';
				if (found[0].disabled) return 'disabled';
				found[0].click();
				return 'clicked';""".formatted(js(selector))), selector);
	}

	/** The text of the one element the selector names. */
	private String text(String selector) {
		return String.valueOf(evaluate("""
				var found = document.querySelectorAll(%s);
				return found.length === 1 ? found[0].innerText.trim() : '(' + found.length + ' elements)';""".formatted(js(selector))));
	}

	private static String js(String text) {
		try {
			return MAPPER.writeValueAsString(text);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException(e);
		}
	}

	private Object evaluate(String script) {
		return link.chat().browser().evaluate(script);
	}

	/** Waits until the script, run in the page, returns {@code true}. */
	private void awaitPage(String script, String what) {
		await(() -> Boolean.TRUE.equals(evaluate(script)), what);
	}

	/**
	 * Waits for the condition while the display does its work.
	 *
	 * <p>The display is emptied before each look at the condition. SWT runs what
	 * was handed to {@code asyncExec} (every message from the page and from the
	 * agent) only when no message of the window system is waiting, and each look
	 * at the page leaves some of those behind: looking after every single event,
	 * as a plain event loop would, keeps the chat's own work waiting for seconds.
	 */
	private void await(BooleanSupplier condition, String what) {
		long deadline = System.nanoTime() + TIMEOUT_NANOS;
		while (true) {
			long slice = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200);
			while (display.readAndDispatch() && System.nanoTime() < slice) {
				// Whatever is waiting, the chat's work included.
			}
			if (condition.getAsBoolean()) {
				return;
			}
			if (System.nanoTime() > deadline) {
				fail("Timed out waiting for " + what + "; " + describe());
			}
			// Wake up again even when the browser has nothing to report.
			display.timerExec(20, () -> {
			});
			display.sleep();
		}
	}

	/** What a failure message needs to say about where things stood. */
	private String describe() {
		String page;
		try {
			page = link == null || link.chat().browser() == null ? "(no page)"
					: String.valueOf(evaluate("return document.body ? document.body.innerText : '(no body)';"));
		} catch (RuntimeException e) {
			page = "(page not readable: " + e + ")";
		}
		return "the page reads: " + page.replace('\n', '|') + "; the agent asked the host for: "
				+ served.stream().map(Served::method).toList() + "; questions to the user: " + prompts.asked
				+ "; the last of the protocol: " + protocol.subList(Math.max(0, protocol.size() - 30), protocol.size());
	}

	private static String read(Path file) throws IOException {
		return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
	}
}
