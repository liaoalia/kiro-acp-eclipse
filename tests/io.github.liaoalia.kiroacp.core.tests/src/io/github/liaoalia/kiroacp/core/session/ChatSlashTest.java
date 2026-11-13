package io.github.liaoalia.kiroacp.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.liaoalia.kiroacp.core.model.ConnectionStatus;
import io.github.liaoalia.kiroacp.core.model.FromWebview;
import io.github.liaoalia.kiroacp.core.model.ImageAttachment;
import io.github.liaoalia.kiroacp.core.model.MessageRole;
import io.github.liaoalia.kiroacp.core.model.ToWebview;
import io.github.liaoalia.kiroacp.core.services.DiskTextFiles;
import io.github.liaoalia.kiroacp.core.session.ChatHooks.Option;
import io.github.liaoalia.kiroacp.core.session.Harness.Client;
import io.github.liaoalia.kiroacp.core.model.DeliveryState;
import io.github.liaoalia.kiroacp.core.session.auth.SignIn;
import io.github.liaoalia.kiroacp.core.session.commands.SlashCommands;
import io.github.liaoalia.kiroacp.core.session.steering.Steering;
import io.github.liaoalia.kiroacp.core.session.context.ChatContext;
import io.github.liaoalia.kiroacp.core.session.history.OptionMemory;
import io.github.liaoalia.kiroacp.core.session.history.RememberedOptions;

/**
 * Slash commands against the reference's fake agent: its e2e tests F9
 * (routing on the client), the {@code /capabilities} part of F13.1
 * (remembered options) and F16.5 and F16.10 (images together with a
 * command), plus what the reference's controller does around them.
 *
 * <p>The fake agent advertises the extension methods
 * {@code _kiro/session/context} and {@code _kiro/account/getUsage}, the modes
 * {@code vibe} and {@code plan}, the options {@code model} and
 * {@code effortLevel}, and the commands {@code /plan} and {@code /compact}.
 */
class ChatSlashTest {

	/** A 1x1 PNG. */
	private static final String PNG_1PX = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";
	/** What the page sends as the thumbnail. */
	private static final String PREVIEW = "data:image/webp;base64,UklGRhoAAABXRUJQVlA4TA0AAAAvAAAAEAcQERGIiP4HAA==";
	private static final int PNG_BYTES = Base64.getDecoder().decode(PNG_1PX).length;

	@TempDir
	Path dir;

	/** The choices the controller's listeners heard of. */
	private static List<String> choices(Harness h) {
		List<String> chosen = new CopyOnWriteArrayList<>();
		h.controller.hooks().addListener(new ChatHooks.SessionListener() {
			@Override
			public void optionChosen(String sessionId, Option option, String id, JsonNode value) {
				chosen.add(option + " " + id + "=" + value.asText());
				h.signal.changed();
			}
		});
		return chosen;
	}

	/**
	 * A view whose session the agent has announced its commands for: once as an update, once
	 * with the session state that follows it. With the client's commands in every list, the
	 * agent's own are what tells.
	 */
	private static Client open(Harness h) throws Exception {
		Client view = h.attach("view", AttachOptions.ownGroup("view"));
		view.send(new FromWebview.Ready());
		h.await(() -> view.sessionId() != null && view.page.ofType(ToWebview.Commands.class).stream()
				.filter(message -> message.commands().stream().anyMatch(command -> command.name().equals("compact"))).count() >= 2,
				"a session with the agent's commands");
		h.scheduler.flush();
		return view;
	}

	/** The sessions whose turn ended, as the listeners hear of it. */
	private static List<String> ended(Harness h) {
		List<String> ended = new CopyOnWriteArrayList<>();
		h.controller.hooks().addListener(new ChatHooks.SessionListener() {
			@Override
			public void turnEnded(String sessionId, String stopReason) {
				ended.add(sessionId + " " + stopReason);
				h.signal.changed();
			}
		});
		return ended;
	}

	private static ImageAttachment pasted(String id, String data) {
		return new ImageAttachment(id, "Pasted image " + id + ".png", "image/png", data, PNG_BYTES, null, PREVIEW);
	}

	private static ChatContext context(Harness h) {
		return ChatContext.install(h.controller,
				new ChatContext.Host(h.host.workspace(), h.settings, h.scheduler, new DiskTextFiles(), h.logger, null, null, null));
	}

	private static List<JsonNode> messages(Client view, String role) throws Exception {
		List<JsonNode> out = new ArrayList<>();
		for (JsonNode item : view.snapshot().get("items")) {
			if ("message".equals(item.path("type").asText()) && role.equals(item.path("message").path("role").asText())) {
				out.add(item.get("message"));
			}
		}
		return out;
	}

	private static String textOf(JsonNode message) {
		StringBuilder text = new StringBuilder();
		for (JsonNode segment : message.get("segments")) {
			if ("text".equals(segment.path("type").asText())) {
				text.append(segment.get("text").asText());
			}
		}
		return text.toString();
	}

	private static String lastText(Client view, String role) throws Exception {
		List<JsonNode> all = messages(view, role);
		return all.isEmpty() ? "" : textOf(all.get(all.size() - 1));
	}

	private static List<String> notices(Client view, int mark) {
		return view.page.ofType(ToWebview.Notice.class, mark).stream().map(notice -> notice.notice().text()).toList();
	}

	/** Sends a command that is answered here and returns the reply once it is in the transcript. */
	private static String local(Harness h, Client view, String command) throws Exception {
		int mark = view.mark();
		view.prompt(command);
		view.page.await(ToWebview.Chunk.class, mark, chunk -> chunk.role() == MessageRole.AGENT, "the reply to " + command);
		h.scheduler.flush();
		assertFalse(view.page.ofType(ToWebview.Turn.class, mark).stream().anyMatch(ToWebview.Turn::active), "no agent turn was started");
		return lastText(view, "agent");
	}

	private static String model(Client view) throws Exception {
		return view.snapshot().at("/configOptions/0/currentValue").asText();
	}

	private static String mode(Client view) throws Exception {
		return view.snapshot().at("/modes/currentModeId").asText();
	}

	private static List<String> commandNames(JsonNode commands) {
		List<String> names = new ArrayList<>();
		commands.forEach(command -> names.add(command.get("name").asText()));
		return names;
	}

	// ---------------------------------------------------------------------
	// F9
	// ---------------------------------------------------------------------

	@Test
	void clearReplacesTheSessionWithAFreshOneInTheSamePage() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			SlashCommands.install(h.controller, null, null);
			Client view = open(h);
			String before = view.sessionId();
			assertEquals("end_turn", view.turn("/scenario chat").stopReason());
			assertFalse(view.snapshot().get("items").isEmpty());

			int mark = view.mark();
			view.prompt("/clear");
			view.page.await(ToWebview.Session.class, mark, s -> s.sessionId() != null && !s.sessionId().equals(before),
					"a fresh session after /clear");
			h.await(() -> h.logger.has("session " + before + " closed"), "the cleared session to be closed");
			h.scheduler.flush();

			JsonNode state = view.snapshot();
			assertNotEquals(before, state.get("sessionId").asText());
			assertEquals(0, state.get("items").size(), "a fresh, empty session");
			for (JsonNode session : state.get("sessions")) {
				assertNotEquals(before, session.get("id").asText(), "the cleared session is gone from the session list");
			}
			assertEquals(List.of(view.sessionId()), h.ask(h.controller::sessionIds), "one clean session is left");
			assertFalse(view.page.ofType(ToWebview.Turn.class, mark).stream().anyMatch(ToWebview.Turn::active), "nothing was sent to the agent");
		}
	}

	@Test
	void usageCallsTheAdvertisedExtensionMethodAndShowsTheResultLocally() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			SlashCommands.install(h.controller, null, null);
			Client view = open(h);

			String reply = local(h, view, "/usage");

			assertEquals("```json\n{\n  \"ok\": true,\n  \"usage\": {\n    \"used\": 12,\n    \"limit\": 100\n  }\n}\n```", reply,
					"the reply is formatted JSON");
			assertEquals("/usage", lastText(view, "user"), "the command is shown as the user's message");
			assertFalse(view.snapshot().get("turnActive").asBoolean(), "no agent turn was started");

			// The agent's next message is one of its own, not a continuation of the local reply.
			assertEquals("end_turn", view.turn("/scenario chat").stopReason());
			assertEquals("The answer is 42.", lastText(view, "agent"));
			assertEquals(2, messages(view, "agent").size());
		}
	}

	@Test
	void modelSetsTheConfigOptionAndHelpListsTheBuiltIns() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			SlashCommands.install(h.controller, null, null);
			List<String> chosen = choices(h);
			Client view = open(h);
			assertEquals("model", view.snapshot().at("/configOptions/0/category").asText(), "the fake agent advertises a model option");
			assertEquals("claude-sonnet-4", model(view));

			int mark = view.mark();
			view.prompt("/model claude-opus-4");
			view.page.await(ToWebview.ConfigOptions.class, mark,
					c -> c.options().stream().anyMatch(o -> o.id().equals("model") && o.currentValue().asText().equals("claude-opus-4")),
					"the model config option to be updated");
			view.page.await(ToWebview.Chunk.class, mark, chunk -> chunk.role() == MessageRole.AGENT, "the reply to /model");
			h.scheduler.flush();
			assertEquals("claude-opus-4", model(view));
			assertEquals("`model` set to `claude-opus-4`.", lastText(view, "agent"));
			assertEquals(List.of("CONFIG model=claude-opus-4"), chosen, "the choice is remembered as one made in the page is");

			String help = local(h, view, "/help");
			assertTrue(help.startsWith("Available commands:\n- `/clear` — Start over: a fresh session replaces this one\n"), help);
			assertTrue(help.contains("- `/usage` — Show account usage"), "help lists /usage because the fake agent advertises it: " + help);
			assertTrue(help.contains("- `/plan` *what to plan* — Switch to planner mode"), help);
			assertFalse(help.contains("Not available"), "the agent has a /compact of its own, a model and an effort level: " + help);

			// The agent's commands first, then the built-in ones it did not name itself.
			assertEquals(List.of("plan", "compact", "clear", "new", "help", "capabilities", "mode", "model", "effort", "usage", "context",
					"cancel", "restart", "login"), commandNames(view.snapshot().get("commands")), "built-in commands merged into the / popup");
			assertEquals("Create a plan", view.snapshot().at("/commands/0/description").asText(), "the agent's own /plan is the one listed");
		}
	}

	@Test
	void thePopupOfAFreshSessionListsTheBuiltInsAsSoonAsTheAgentAnnouncedItsCommands() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			SlashCommands.install(h.controller, null, null);
			Client view = open(h);
			ToWebview.Commands last = view.page.last(ToWebview.Commands.class);
			assertEquals(List.of("plan", "compact", "clear", "new"), last.commands().stream().limit(4).map(c -> c.name()).toList(),
					"the commands message carries the merged list");
			assertTrue(last.commands().stream().anyMatch(c -> c.name().equals("mode") && "mode id".equals(c.hint())));
		}
	}

	// ---------------------------------------------------------------------
	// Modes, options and what is remembered (F13.1)
	// ---------------------------------------------------------------------

	@Test
	void modeAndEffortSetWhatTheyNameAndListTheValuesWhenTheArgumentIsMissingOrWrong() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			SlashCommands.install(h.controller, null, null);
			List<String> chosen = choices(h);
			Client view = open(h);

			int mark = view.mark();
			assertEquals("Mode set to `plan`.", local(h, view, "/mode Plan"));
			view.page.await(ToWebview.Modes.class, mark, m -> m.modes().currentModeId().equals("plan"), "the mode to change");
			h.scheduler.flush();
			assertEquals("`effortLevel` set to `high`.", local(h, view, "/effort high"));
			assertEquals(List.of("MODE plan=plan", "CONFIG effortLevel=high"), chosen);

			String listing = local(h, view, "/mode");
			assertTrue(listing.startsWith("Modes (current: plan): vibe"), listing);
			assertTrue(listing.contains("\nmodel (current: claude-sonnet-4): "), listing);
			String effort = local(h, view, "/effort extreme");
			assertTrue(effort.startsWith("effortLevel (current: high): "), effort);
			assertFalse(effort.contains("\n"), "only the option that was asked about: " + effort);
			assertEquals(2, chosen.size(), "a listing chooses nothing");
		}
	}

	@Test
	void capabilitiesListsWhatTheAgentAdvertisedAndWhatIsRememberedForNewSessions() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			String[] stored = new String[1];
			RememberedOptions options = RememberedOptions.install(h.controller, () -> true, new OptionMemory() {
				@Override
				public String read() {
					return stored[0];
				}

				@Override
				public void write(String text) {
					stored[0] = text;
				}
			}, h.logger);
			SlashCommands.install(h.controller, options::describe, null);
			Client view = open(h);

			String nothing = local(h, view, "/capabilities");
			assertTrue(nothing.endsWith("\n\nRemembered for new sessions: none"), nothing);
			assertTrue(nothing.startsWith("**Agent:** "), nothing);
			assertTrue(nothing.contains("\n\n**Agent capabilities:**\n- loadSession: true\n- promptCapabilities: "), nothing);
			assertTrue(nothing.contains("- promptCapabilities: image=true, embeddedContext=true\n"), nothing);
			assertTrue(nothing.contains("\n\n**Extension methods (2):**\n```json\n[\n  \"_kiro/session/context\",\n  \"_kiro/account/getUsage\"\n]\n```\n"),
					nothing);
			assertTrue(nothing.contains("\n\n**Modes** (current: `vibe`): `vibe`"), nothing);
			assertTrue(nothing.contains("\n\n**Config options:**\n- `model` [model]: current `claude-sonnet-4` — allowed: `claude-sonnet-4`, `claude-opus-4`, `nova-pro`\n"),
					nothing);
			assertTrue(nothing.contains("\n\n**Agent commands:** `/plan`, `/compact`\n"), nothing);

			// F13.1: a model and a mode chosen in session A, here by command, are remembered ...
			assertEquals("`model` set to `claude-opus-4`.", local(h, view, "/model claude-opus-4"));
			assertEquals("Mode set to `plan`.", local(h, view, "/mode plan"));
			assertEquals("claude-opus-4", options.remembered().configOptions().get("model").asText(), "the model is remembered");
			assertEquals("plan", options.remembered().modeId(), "the mode is remembered");

			// ... given to session B ...
			String a = view.sessionId();
			int mark = view.mark();
			view.prompt("/new");
			view.page.await(ToWebview.Session.class, mark, s -> s.sessionId() != null && !s.sessionId().equals(a), "session B");
			h.scheduler.flush();
			assertEquals("claude-opus-4", model(view), "session B starts with the remembered model");
			assertEquals("plan", mode(view), "session B starts in the remembered mode");
			assertEquals(2, h.ask(h.controller::sessionIds).size(), "/new keeps the session it was typed in");

			// ... and named by /capabilities.
			String reply = local(h, view, "/caps");
			String line = List.of(reply.split("\n")).stream().filter(l -> l.startsWith("Remembered for new sessions:")).findFirst()
					.orElseThrow(() -> new AssertionError("/capabilities ends with the remembered options: " + reply));
			assertEquals("Remembered for new sessions: mode `plan`, `model` = `claude-opus-4`", line);
			assertTrue(reply.endsWith(line), "it is the last line");
		}
	}

	@Test
	void planSwitchesToThePlannerModeWithoutThatBeingRememberedAndSendsItsText() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			SlashCommands.install(h.controller, null, null);
			List<String> chosen = choices(h);
			Client view = open(h);
			assertEquals("vibe", mode(view));

			assertEquals("Planner mode `plan` is active. Describe what to plan.", local(h, view, "/plan"));
			assertEquals("plan", mode(view), "planner mode");

			int mark = view.mark();
			view.prompt("/plan /scenario chat what next?");
			assertEquals("end_turn", view.awaitTurnEnd(mark).stopReason());
			assertEquals("/scenario chat what next?", lastText(view, "user"), "the text went out without the command");
			assertEquals("The answer is 42.", lastText(view, "agent"));
			assertEquals(List.of(), chosen, "the mode /plan implies is not a choice to remember");
		}
	}

	// ---------------------------------------------------------------------
	// What goes to the agent
	// ---------------------------------------------------------------------

	@Test
	void commandsTheAgentAdvertisesItselfAndUnknownOnesGoOutAsPrompts() throws Exception {
		// The agent plays `chat` for a prompt that names no scenario.
		try (Harness h = Harness.launch(dir, "--scenario", "chat")) {
			SlashCommands.install(h.controller, null, null);
			Client view = open(h);

			// No compact extension method, but a /compact of the agent's own.
			assertEquals("end_turn", view.turn("/compact").stopReason());
			assertEquals("/compact", lastText(view, "user"));
			assertEquals("end_turn", view.turn("/frobnicate now").stopReason());
			assertEquals("/frobnicate now", lastText(view, "user"));
			assertEquals(2, messages(view, "agent").size(), "the agent answered both");
		}
	}

	// ---------------------------------------------------------------------
	// Images together with commands (F16.5, F16.10) and the order of the checks
	// ---------------------------------------------------------------------

	@Test
	void aCommandThatRunsHereDoesNotSendTheImagesAndSaysSo() throws Exception {
		try (Harness h = Harness.launch(dir); ChatContext context = context(h)) {
			SlashCommands.install(h.controller, null, null);
			Client view = open(h);

			int mark = view.mark();
			view.send(new FromWebview.Prompt("/help", List.of(), List.of(pasted("h", PNG_1PX))));
			view.page.await(ToWebview.Notice.class, mark, n -> true, "a notice about the image");
			h.scheduler.flush();

			assertTrue(lastText(view, "agent").contains("/clear"), "help was shown");
			assertEquals(List.of("The image was not sent: /help runs in the plugin."), notices(view, mark));
			assertFalse(view.page.ofType(ToWebview.Turn.class, mark).stream().anyMatch(ToWebview.Turn::active));

			mark = view.mark();
			view.send(new FromWebview.Prompt("/mode", List.of(), List.of(pasted("a", PNG_1PX), pasted("b", PNG_1PX))));
			view.page.await(ToWebview.Notice.class, mark, n -> true, "a notice about the images");
			assertEquals(List.of("The 2 images were not sent: /mode runs in the plugin."), notices(view, mark));
		}
	}

	@Test
	void planWithTextSwitchesToThePlannerModeAndSendsTheImagesWithTheText() throws Exception {
		try (Harness h = Harness.launch(dir); ChatContext context = context(h)) {
			SlashCommands.install(h.controller, null, null);
			Client view = open(h);

			int mark = view.mark();
			view.send(new FromWebview.Prompt("/plan /scenario images plan this", List.of(), List.of(pasted("p", PNG_1PX))));
			assertEquals("end_turn", view.awaitTurnEnd(mark).stopReason());

			assertEquals("plan", mode(view), "planner mode");
			assertEquals("Received 1 image(s): image/png " + PNG_BYTES + " B; blocks: text,image.", lastText(view, "agent"),
					"the plan text went out with the image");
			assertEquals(List.of(), notices(view, mark), "no 'not sent' notice for /plan");
		}
	}

	@Test
	void theImagesAreCheckedBeforeTheCommandIsLookedAt() throws Exception {
		try (Harness h = Harness.launch(dir); ChatContext context = context(h)) {
			// The order the composition root installs them in: the image check first.
			SlashCommands.install(h.controller, null, null);
			Client view = open(h);
			String junk = Base64.getEncoder().encodeToString("not an image at all".getBytes());

			int mark = view.mark();
			view.send(new FromWebview.Prompt("/help", List.of(), List.of(pasted("junk", junk))));
			view.page.await(ToWebview.Notice.class, mark, n -> true, "the refusal");
			h.scheduler.flush();

			assertEquals(List.of("Pasted image junk.png was not attached: it is not a PNG, JPEG, GIF or WebP image. The message was not sent."),
					notices(view, mark));
			assertEquals(List.of(), messages(view, "agent"), "the command did not run: a message with a refused image is not sent at all");
			assertEquals(List.of(), messages(view, "user"));

			mark = view.mark();
			view.send(new FromWebview.Prompt("/clear", List.of(), List.of(pasted("junk", junk))));
			view.page.await(ToWebview.Notice.class, mark, n -> true, "the refusal");
			h.scheduler.flush();
			assertEquals(List.of(), view.page.ofType(ToWebview.Session.class, mark), "the session was not replaced");
		}
	}

	// ---------------------------------------------------------------------
	// Sessions: /clear and /new through the controller's own paths
	// ---------------------------------------------------------------------

	@Test
	void clearDuringATurnCancelsItAndAMessageTypedMeanwhileGoesToTheNewSession() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			SlashCommands.install(h.controller, null, null);
			List<String> closing = new CopyOnWriteArrayList<>();
			h.controller.hooks().addListener(new ChatHooks.SessionListener() {
				@Override
				public void sessionClosing(String sessionId) {
					closing.add(sessionId);
				}
			});
			Client view = open(h);
			String before = view.sessionId();
			int mark = view.mark();
			view.prompt("/scenario slow");
			h.await(() -> !view.agentChunks(mark).isEmpty(), "the turn to stream");

			// Both are queued before the replacement exists: the second finds the session closing.
			view.prompt("/clear");
			view.prompt("/scenario chat for the new session");
			ToWebview.Session fresh = view.page.await(ToWebview.Session.class, mark,
					s -> s.sessionId() != null && !s.sessionId().equals(before), "the new session");
			// Whatever ends after the page turned to the new session is the new session's.
			assertEquals("end_turn", view.awaitTurnEnd(view.page.messages().indexOf(fresh)).stopReason());
			assertEquals("The answer is 42.", lastText(view, "agent"));

			assertNotEquals(before, view.sessionId());
			assertEquals("/scenario chat for the new session", lastText(view, "user"), "the message went to the replacement");
			assertEquals(1, messages(view, "user").size(), "the new session starts clean");
			assertEquals(List.of(before), closing, "the old session was closed the controller's way, once");
			assertEquals(List.of(view.sessionId()), h.ask(h.controller::sessionIds));
			assertTrue(h.logger.has("session " + before + " closed"));
			assertEquals(0, h.scheduler.pending(Turns.CANCEL_GRACE_MS), "the Stop watchdog of the cancelled turn went with the session");
			assertEquals(List.of(), notices(view, mark), "nothing to complain about");
		}
	}

	@Test
	void newOpensASessionBesideTheOneItWasTypedInAndLeavesItsTurnAlone() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			SlashCommands.install(h.controller, null, null);
			List<String> ended = ended(h);
			Client view = open(h);
			String first = view.sessionId();
			int mark = view.mark();
			view.prompt("/scenario slow");
			h.await(() -> !view.agentChunks(mark).isEmpty(), "the turn to stream");

			view.prompt("/new");
			view.page.await(ToWebview.Session.class, mark, s -> s.sessionId() != null && !s.sessionId().equals(first), "the new session");
			h.scheduler.flush();

			assertEquals(2, h.ask(h.controller::sessionIds).size());
			assertTrue(h.ask(() -> h.controller.turnActive(first)), "the turn of the first session runs on");
			assertEquals(0, view.snapshot().get("items").size(), "the page shows the new, empty session");
			h.await(() -> !ended.isEmpty(), "the first session's turn to end by itself");
			assertEquals(List.of(first + " end_turn"), ended);
		}
	}

	// ---------------------------------------------------------------------
	// While a turn runs
	// ---------------------------------------------------------------------

	@Test
	void commandsRunAtOnceWhileATurnRunsAndCancelStopsIt() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			SlashCommands.install(h.controller, null, null);
			Client view = open(h);
			int mark = view.mark();
			view.prompt("/scenario slow");
			h.await(() -> !view.agentChunks(mark).isEmpty(), "the turn to stream");

			// Answered here at once: nothing is steered, queued or refused.
			int asked = view.mark();
			view.prompt("/usage");
			view.page.await(ToWebview.Chunk.class, asked,
					chunk -> chunk.role() == MessageRole.AGENT && chunk.segment().toString().contains("```json"), "the reply to /usage");
			assertTrue(view.snapshot().get("turnActive").asBoolean(), "the turn runs on");
			assertEquals(List.of(), notices(view, mark));

			// `/plan <text>`: the mode is switched at once; its text is a prompt typed during a turn,
			// which nothing takes while no steering or queueing is installed.
			view.prompt("/plan and then this");
			view.page.await(ToWebview.Notice.class, asked, n -> true, "the text of /plan to be refused");
			assertEquals(List.of("Wait for the current turn to finish or cancel it first."), notices(view, asked));
			assertEquals("plan", mode(view));

			view.prompt("/cancel");
			assertEquals("cancelled", view.awaitTurnEnd(mark).stopReason());
		}
	}

	@Test
	void theTextOfPlanTypedDuringATurnGoesToWhateverTakesPromptsThen() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			SlashCommands.install(h.controller, null, null);
			List<String> taken = new CopyOnWriteArrayList<>();
			h.controller.hooks().setMidTurnPrompts((request, blocks) -> {
				taken.add(request.text() + " -> " + blocks.size() + " block(s)");
				h.signal.changed();
				return java.util.concurrent.CompletableFuture.completedFuture(null);
			});
			Client view = open(h);
			int mark = view.mark();
			view.prompt("/scenario slow");
			h.await(() -> !view.agentChunks(mark).isEmpty(), "the turn to stream");

			view.prompt("/plan steer this way");
			h.await(() -> !taken.isEmpty(), "the text of /plan to reach the mid-turn seam");
			view.prompt("/help");
			view.page.await(ToWebview.Chunk.class, mark,
					chunk -> chunk.role() == MessageRole.AGENT && chunk.segment().toString().contains("Available commands"), "the help");

			assertEquals(List.of("steer this way -> 1 block(s)"), taken, "the command itself never reaches the seam, its text does");
			assertEquals("end_turn", view.awaitTurnEnd(mark).stopReason());
		}
	}

	// ---------------------------------------------------------------------
	// With steering and queueing installed (reference e2e F12.4, F12.6, F12.7)
	// ---------------------------------------------------------------------

	@Test
	void withSteeringInstalledCancelTypedDuringATurnRunsAtOnceAndIsNeitherSteeredNorQueued() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			Steering.install(h.controller);
			SlashCommands.install(h.controller, null, null);
			Client view = open(h);
			int mark = view.mark();
			view.prompt("/scenario slow");
			h.await(() -> view.agentChunks(mark).size() >= 2, "two streamed chunks of the slow turn");

			view.prompt("/cancel");

			assertEquals("cancelled", view.awaitTurnEnd(mark).stopReason(), "stop reason");
			assertFalse(view.snapshot().get("turnActive").asBoolean(), "turnActive after /cancel");
			assertEquals(List.of(), notices(view, mark), "no refusal, nothing about steering or a queue");
			assertEquals(List.of("/scenario slow"), messages(view, "user").stream().map(ChatSlashTest::textOf).toList(),
					"/cancel is not echoed as a prompt");
			assertEquals(List.of(), view.page.ofType(ToWebview.MessageDelivery.class, mark), "it was neither steered nor queued");
			assertTrue(view.agentChunks(mark).size() < 20, "the turn was cut short");
			assertFalse(h.logger.has(Steering.STEER_METHOD), "the agent was not asked to steer: " + h.logger.lines);
		}
	}

	@Test
	void clearDuringATurnDiscardsTheQueueAndNeverStartsATurnOnTheClearedSession() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			Steering steering = Steering.install(h.controller);
			SlashCommands.install(h.controller, null, null);
			List<String> ended = ended(h);
			Client view = open(h);
			String before = view.sessionId();
			// As the reference's test hook: prompts during a turn queue without asking the agent.
			h.ask(() -> {
				steering.setSteerUnsupported(before, true);
				return null;
			});
			int mark = view.mark();
			view.prompt("/scenario slow");
			h.await(() -> view.agentChunks(mark).size() >= 2, "two streamed chunks of the slow turn");
			view.prompt("/scenario chat later");
			ToWebview.MessageDelivery queued = view.page.await(ToWebview.MessageDelivery.class, mark,
					badge -> badge.delivery() == DeliveryState.QUEUED, "the prompt to be queued");

			view.prompt("/clear");
			view.page.await(ToWebview.Session.class, mark, s -> s.sessionId() != null && !s.sessionId().equals(before),
					"a fresh session after /clear");
			h.await(() -> h.logger.has("session " + before + " closed"), "the cleared session to be closed");
			h.scheduler.flush();

			JsonNode state = view.snapshot();
			assertNotEquals(before, state.get("sessionId").asText());
			assertEquals(0, state.get("items").size(), "a fresh, empty session");
			assertEquals(List.of(view.sessionId()), h.ask(h.controller::sessionIds), "the cleared session is gone");
			assertEquals(List.of("1 queued message discarded."), notices(view, mark), "a notice says the queued message was discarded");
			assertTrue(view.page.ofType(ToWebview.MessageDelivery.class, mark).stream()
					.anyMatch(badge -> badge.messageId().equals(queued.messageId()) && badge.delivery() == null), "the queued badge was cleared");
			// Only the slow turn ever started: the queued prompt never became a turn on the cleared session.
			assertEquals(1, view.page.ofType(ToWebview.Turn.class, mark).stream().filter(ToWebview.Turn::active).count(),
					"no turn started for the discarded prompt");
			assertTrue(ended.stream().allMatch(end -> end.startsWith(before + " ")), "no turn of another session ended: " + ended);
			assertFalse(view.agentChunks(mark).stream().anyMatch(chunk -> chunk.segment().toString().contains("42")),
					"the discarded prompt was never answered");
			assertFalse(state.get("turnActive").asBoolean(), "the new session is idle");
		}
	}

	@Test
	void aPromptTypedWhileClearIsReplacingTheSessionGoesToTheNewSession() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			Steering.install(h.controller);
			SlashCommands.install(h.controller, null, null);
			Client view = open(h);
			String before = view.sessionId();
			int mark = view.mark();

			// Both are on their way before the replacement exists.
			view.prompt("/clear");
			view.prompt("/scenario chat");

			assertEquals("end_turn", view.awaitTurnEnd(mark).stopReason());
			JsonNode state = view.snapshot();
			assertNotEquals(before, state.get("sessionId").asText(), "the view shows the replacement session");
			assertEquals(List.of(view.sessionId()), h.ask(h.controller::sessionIds), "the cleared session is gone");
			assertEquals(List.of("/scenario chat"), messages(view, "user").stream().map(ChatSlashTest::textOf).toList(),
					"the new session holds just the typed question");
			assertEquals("The answer is 42.", lastText(view, "agent"), "it was answered in the new session");
			assertFalse(state.get("turnActive").asBoolean(), "idle afterwards");
			assertEquals(List.of(), notices(view, mark));
		}
	}

	// ---------------------------------------------------------------------
	// Without a session
	// ---------------------------------------------------------------------

	@Test
	void loginAndRestartRunWithoutASessionAndAnythingElseIsToldThereIsNone() throws Exception {
		try (Harness h = Harness.launch(dir, "--auth-required")) {
			SlashCommands.install(h.controller, null, SignIn.install(h.controller));
			Client view = h.attach("view", AttachOptions.ownGroup("view"));
			view.send(new FromWebview.Ready());
			view.page.await(ToWebview.Connection.class, 0, c -> c.connection().status() == ConnectionStatus.AUTH_REQUIRED, "the sign-in state");
			assertNull(view.sessionId());

			int mark = view.mark();
			view.prompt("/help");
			view.page.await(ToWebview.Notice.class, mark, n -> true, "a notice");
			assertEquals(List.of("No active session. Sign in or restart the agent, then try again."), notices(view, mark));

			int launched = h.launches.size();
			view.prompt("/restart");
			h.await(() -> h.launches.size() == launched + 1, "the agent to be started again by /restart");
			view.page.await(ToWebview.Connection.class, mark, c -> c.connection().status() == ConnectionStatus.AUTH_REQUIRED,
					"the sign-in state again");
			h.scheduler.flush();

			// The fake host's sign-in "terminal" exits with 0 at once; the agent is then started again.
			view.prompt("/login");
			h.await(() -> h.launches.size() == launched + 2, "the agent to be started again after signing in");
			assertEquals(1, notices(view, mark).size(), "neither command was answered with the notice");
		}
	}
}
