package io.github.liaoalia.kiroacp.ui.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
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
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.liaoalia.kiroacp.core.acp.api.AgentProcessOptions;
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
				options.clientVersion()), client));
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

	// --- what the page got ---

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
