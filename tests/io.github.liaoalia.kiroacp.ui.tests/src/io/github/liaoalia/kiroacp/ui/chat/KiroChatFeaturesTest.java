package io.github.liaoalia.kiroacp.ui.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.filebuffers.ITextFileBufferManager;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.liaoalia.kiroacp.core.acp.api.AgentProcessOptions;
import io.github.liaoalia.kiroacp.core.acp.api.ClientServices;
import io.github.liaoalia.kiroacp.core.model.ToolCallView;
import io.github.liaoalia.kiroacp.core.model.ToolKind;
import io.github.liaoalia.kiroacp.core.model.ToolStatus;
import io.github.liaoalia.kiroacp.core.acp.impl.KiroAgents;
import io.github.liaoalia.kiroacp.core.model.FromWebview;
import io.github.liaoalia.kiroacp.core.model.ImageAttachment;
import io.github.liaoalia.kiroacp.core.model.MessageRole;
import io.github.liaoalia.kiroacp.core.model.MessageSegment;
import io.github.liaoalia.kiroacp.core.model.ToWebview;
import io.github.liaoalia.kiroacp.core.session.AttachOptions;
import io.github.liaoalia.kiroacp.core.session.ChatController;
import io.github.liaoalia.kiroacp.core.session.PageHandle;
import io.github.liaoalia.kiroacp.ui.plan.KiroPlan;
import io.github.liaoalia.kiroacp.ui.services.Prompts;

/**
 * The features as the plug-in installs them ({@link KiroChat#install}), on
 * the Eclipse services and the reference's fake agent, without a workbench
 * and without a browser: what depends on the order they are installed in,
 * and that they are all there.
 *
 * <p>Nothing appears on the desktop. Needs {@code node} on the PATH and
 * {@code npm ci} run in the fake agent's folder.
 */
class KiroChatFeaturesTest {

	private static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(60);
	private static final String PROJECT = "kiro-features";
	/** A 1x1 PNG. */
	private static final String PNG_1PX = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";

	/** Refuses every question at once and shows nothing. */
	private static final class RefusingPrompts implements Prompts {

		@Override
		public CompletionStage<Boolean> ask(Question question) {
			return CompletableFuture.completedFuture(Boolean.FALSE);
		}

		@Override
		public Notice tell(String title, String message, String done) {
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
	private final List<ToWebview> posted = new CopyOnWriteArrayList<>();
	private Display display;
	private boolean ownDisplay;
	private IProject project;
	private KiroChat.Services services;
	private ChatController controller;
	private PageHandle page;

	@BeforeEach
	void createWorkspaceAndChat() throws Exception {
		assertFalse(PlatformUI.isWorkbenchRunning(), "these tests are written for a runtime without a workbench");
		for (IProject each : resources.getRoot().getProjects()) {
			each.delete(true, true, null);
		}
		// The temp folder may be spelled with a short (8.3) name; Eclipse reports locations with the long one.
		Path root = temp.toRealPath().resolve("checkout");
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
		Path script = fakeAgentScript();
		services = KiroChat.services(display, new RefusingPrompts());
		controller = ChatController.create(services.host(), (options, client) -> KiroAgents.create(new AgentProcessOptions("node",
				List.of(script.toString(), "acp", "--scenario", "chat"), options.cwd(), options.env(), options.logger(),
				options.clientVersion()), recordingReads(client)));
		KiroChat.install(controller, services, display);
		page = controller.attach(posted::add, AttachOptions.ownGroup("features-test"));
		page.onMessage(new FromWebview.Ready());
		// A session whose commands the agent has announced: it does that a few milliseconds after session/new.
		await(() -> page.sessionId() != null && last(ToWebview.Commands.class) != null
				&& last(ToWebview.Commands.class).commands().stream().anyMatch(command -> command.name().equals("compact")),
				"a session with the agent's commands");
	}

	@AfterEach
	void close() throws CoreException {
		try {
			if (controller != null) {
				// The agent's working directory is the temp folder: it must be gone before the folder can be.
				CompletableFuture<Void> down = controller.shutdown();
				await(down::isDone, "the controller to shut down");
			}
			if (services != null) {
				services.workspace().dispose();
			}
		} finally {
			if (ownDisplay && !display.isDisposed()) {
				display.dispose();
			}
			project.delete(false, true, null);
		}
	}

	@Test
	void theImagesOfAMessageAreCheckedBeforeItIsTakenForASlashCommand() {
		String junk = Base64.getEncoder().encodeToString("not an image at all".getBytes());
		int mark = posted.size();

		page.onMessage(new FromWebview.Prompt("/help", List.of(),
				List.of(new ImageAttachment("junk", "Pasted image junk.png", "image/png", junk, 19, null, null))));
		await(() -> !notices(mark).isEmpty(), "the refusal of the image");

		assertEquals(List.of("Pasted image junk.png was not attached: it is not a PNG, JPEG, GIF or WebP image. The message was not sent."),
				notices(mark));
		assertEquals(List.of(), agentTexts(mark), "the command did not run");

		// With an image that passes, the command runs here and says the image stayed behind (reference F16.5).
		int again = posted.size();
		page.onMessage(new FromWebview.Prompt("/help", List.of(),
				List.of(new ImageAttachment("ok", "Pasted image ok.png", "image/png", PNG_1PX, 70, null, null))));
		await(() -> !notices(again).isEmpty(), "the notice about the image");

		assertEquals(List.of("The image was not sent: /help runs in the plugin."), notices(again));
		assertTrue(String.join("", agentTexts(again)).startsWith("Available commands:\n- `/clear`"), agentTexts(again).toString());
	}

	@Test
	void slashCommandsUsageAndThePlanDocumentAreInstalled() {
		// The / popup lists the agent's commands and then the client's.
		List<String> commands = last(ToWebview.Commands.class).commands().stream().map(command -> command.name()).toList();
		assertEquals(List.of("plan", "compact", "clear", "new"), commands.subList(0, 4), commands.toString());

		// /capabilities ends with what is remembered for new sessions.
		int mark = posted.size();
		page.onMessage(new FromWebview.Prompt("/capabilities", List.of(), null));
		await(() -> !agentTexts(mark).isEmpty(), "the reply to /capabilities");
		String reply = String.join("", agentTexts(mark));
		assertTrue(reply.lines().anyMatch(line -> line.startsWith("Remembered for new sessions: ")), reply);

		// After a turn without a usage report the percentage is asked for and posted.
		int turn = posted.size();
		page.onMessage(new FromWebview.Prompt("/scenario chat", List.of(), null));
		await(() -> posted.subList(turn, posted.size()).stream()
				.anyMatch(message -> message instanceof ToWebview.Usage usage && usage.usage().contextPercent() != null), "the usage");
		assertEquals(7.0, controller.snapshot(page.sessionId()).usage().contextPercent());

		// The plan document: without a workbench there is no editor area, and the chat says so.
		assertNotNull(KiroPlan.of(controller));
		int asked = posted.size();
		page.onMessage(new FromWebview.OpenPlan());
		await(() -> !notices(asked).isEmpty(), "the answer to openPlan");
		assertEquals(List.of("Could not open the plan: there is no workbench window to show the plan in"), notices(asked));
	}

	/**
	 * Plan Task 17: with the features as the plug-in installs them, no type of
	 * message the page may send is left without a handler. What
	 * {@link ChatController#handles} says is tied to what the controller does
	 * with a message by {@code PageProtocolCoverageTest} of the core tests,
	 * which sends every sample; here nothing is sent, because the handlers of
	 * the real installation open dialogs and terminal windows.
	 */
	@Test
	void everyMessageThePageMaySendHasAHandlerInTheInstalledPlugIn() {
		List<String> dropped = new ArrayList<>();
		Class<?>[] types = FromWebview.class.getPermittedSubclasses();
		for (Class<?> type : types) {
			if (!controller.handles(type.asSubclass(FromWebview.class))) {
				dropped.add(type.getSimpleName());
			}
		}
		assertEquals(List.of(), dropped, "page messages that would be logged and dropped");
		assertEquals(23, types.length, "the types of FromWebview");
	}

	/**
	 * Reference F6.1, first half: the agent's {@code fs/read_text_file} gets
	 * the unsaved text of a file that is open, and reading saves nothing. The
	 * file is "open" as an editor holds it open, through the file buffers.
	 */
	@Test
	void theAgentsReadToolSeesTheUnsavedEditsOfAnOpenFileAndLeavesThemUnsaved() throws Exception {
		IFile readme = project.getFile("README.md");
		readme.create(new ByteArrayInputStream("# Tour\n".getBytes(StandardCharsets.UTF_8)), true, null);
		Path onDisk = Path.of(readme.getLocation().toOSString());
		ITextFileBufferManager buffers = FileBuffers.getTextFileBufferManager();
		buffers.connect(readme.getFullPath(), LocationKind.IFILE, null);
		try {
			ITextFileBuffer buffer = buffers.getTextFileBuffer(readme.getFullPath(), LocationKind.IFILE);
			buffer.getDocument().set("unsaved line added by the test\n# Tour\n");
			assertTrue(buffer.isDirty(), "the file has unsaved edits before the prompt");

			int mark = posted.size();
			page.onMessage(new FromWebview.Prompt("/scenario tour", List.of(), null));
			await(() -> since(mark, ToWebview.Permission.class).size() == 1, "the permission card of the tour's edit");
			ToWebview.Permission asked = since(mark, ToWebview.Permission.class).get(0);
			page.onMessage(new FromWebview.PermissionResponse(asked.request().requestId(), "accept"));
			await(() -> since(mark, ToWebview.Turn.class).stream().anyMatch(turn -> !turn.active()), "the tour to end");

			assertEquals(1, reads.size(), reads.toString());
			assertEquals(onDisk.toRealPath(), Path.of(reads.get(0).request().path("path").asText()).toRealPath());
			assertEquals("unsaved line added by the test\n# Tour\n", reads.get(0).answer().get(10, TimeUnit.SECONDS).path("content").asText());
			assertTrue(buffer.isDirty(), "the read did not save the file");
			assertEquals("# Tour\n", Files.readString(onDisk), "nor change it on disk");

			ToolCallView read = since(mark, ToWebview.ToolCall.class).stream().map(ToWebview.ToolCall::call)
					.filter(call -> call.toolCallId().equals("call-read")).reduce((first, second) -> second).orElseThrow();
			assertEquals(ToolKind.READ, read.kind());
			assertEquals(ToolStatus.COMPLETED, read.status());
			assertTrue(read.locations().stream().anyMatch(location -> location.path().endsWith("README.md")), read.toString());
			// The fake agent says in the chat what it expected and did not get.
			assertEquals(List.of(), agentTexts(mark).stream().filter(text -> text.contains("[fake-agent]")).toList());
			assertEquals("end_turn", since(mark, ToWebview.Turn.class).stream().filter(turn -> !turn.active()).findFirst().orElseThrow()
					.stopReason());
		} finally {
			buffers.disconnect(readme.getFullPath(), LocationKind.IFILE, null);
		}
	}

	// --- what the page got ---

	private <T extends ToWebview> List<T> since(int mark, Class<T> type) {
		List<ToWebview> all = List.copyOf(posted);
		return all.subList(Math.min(mark, all.size()), all.size()).stream().filter(type::isInstance).map(type::cast).toList();
	}

	private <T extends ToWebview> T last(Class<T> type) {
		List<ToWebview> all = List.copyOf(posted);
		for (int i = all.size() - 1; i >= 0; i--) {
			if (type.isInstance(all.get(i))) {
				return type.cast(all.get(i));
			}
		}
		return null;
	}

	private List<String> notices(int mark) {
		return List.copyOf(posted).subList(mark, posted.size()).stream().filter(ToWebview.Notice.class::isInstance)
				.map(message -> ((ToWebview.Notice) message).notice().text()).toList();
	}

	private List<String> agentTexts(int mark) {
		return List.copyOf(posted).subList(mark, posted.size()).stream().filter(ToWebview.Chunk.class::isInstance)
				.map(ToWebview.Chunk.class::cast).filter(chunk -> chunk.role() == MessageRole.AGENT)
				.map(chunk -> chunk.segment() instanceof MessageSegment.Text text ? text.text() : "").toList();
	}

	// --- plumbing ---

	/** A {@code fs/read_text_file} of the agent and what the host answered. */
	private record Read(JsonNode request, CompletableFuture<JsonNode> answer) {
	}

	private final List<Read> reads = new CopyOnWriteArrayList<>();

	/** The host's services, with the agent's reads written down on their way through. */
	private ClientServices recordingReads(ClientServices real) {
		return (ClientServices) Proxy.newProxyInstance(ClientServices.class.getClassLoader(), new Class<?>[] { ClientServices.class },
				(proxy, method, args) -> {
					Object answer;
					try {
						answer = method.invoke(real, args);
					} catch (InvocationTargetException e) {
						throw e.getCause();
					}
					if (method.getName().equals("readTextFile") && answer instanceof CompletableFuture<?> future) {
						@SuppressWarnings("unchecked")
						CompletableFuture<JsonNode> content = (CompletableFuture<JsonNode>) future;
						reads.add(new Read((JsonNode) args[0], content));
					}
					return answer;
				});
	}

	/** {@code tests/fixtures/fake-agent/agent.mjs}; the test bundle's pom says where the fixtures are. */
	private static Path fakeAgentScript() {
		String dir = System.getProperty("kiroacp.fixtures");
		if (dir == null || dir.isBlank()) {
			throw new IllegalStateException("System property kiroacp.fixtures is not set (the test bundle's pom sets it)");
		}
		Path script = Path.of(dir).toAbsolutePath().normalize().resolve("fake-agent").resolve("agent.mjs");
		assertTrue(Files.isRegularFile(script), "Fake agent not found: " + script);
		return script;
	}

	/** Lets the display run until the condition holds: the controller works on its thread. */
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
				fail("Timed out waiting for " + what + "; the page got " + posted);
			}
			display.timerExec(20, () -> {
			});
			display.sleep();
		}
	}
}
