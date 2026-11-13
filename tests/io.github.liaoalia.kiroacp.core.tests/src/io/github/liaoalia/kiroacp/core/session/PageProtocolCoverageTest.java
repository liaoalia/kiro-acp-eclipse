package io.github.liaoalia.kiroacp.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.databind.JsonNode;

import io.github.liaoalia.kiroacp.core.acp.FakeAgent;
import io.github.liaoalia.kiroacp.core.model.FromWebview;
import io.github.liaoalia.kiroacp.core.model.ToWebview;
import io.github.liaoalia.kiroacp.core.model.UiJson;
import io.github.liaoalia.kiroacp.core.services.DiskTextFiles;
import io.github.liaoalia.kiroacp.core.session.Harness.Client;
import io.github.liaoalia.kiroacp.core.session.auth.SignIn;
import io.github.liaoalia.kiroacp.core.session.commands.SlashCommands;
import io.github.liaoalia.kiroacp.core.session.commands.UsageTracker;
import io.github.liaoalia.kiroacp.core.session.context.ChatContext;
import io.github.liaoalia.kiroacp.core.session.diff.DiffReview;
import io.github.liaoalia.kiroacp.core.session.diff.FakeDiffEditors;
import io.github.liaoalia.kiroacp.core.session.history.OptionMemory;
import io.github.liaoalia.kiroacp.core.session.history.PastSessions;
import io.github.liaoalia.kiroacp.core.session.history.RememberedOptions;
import io.github.liaoalia.kiroacp.core.session.plan.FakePlanDocuments;
import io.github.liaoalia.kiroacp.core.session.plan.PlanDocument;
import io.github.liaoalia.kiroacp.core.session.steering.Steering;

/**
 * The page protocol as a whole (plan Task 17): every type of message the page
 * may send is acted on by somebody, and every type the page understands is
 * built somewhere in the plug-in. The types are enumerated from the sealed
 * interfaces and from {@code tests/fixtures/protocol-samples.json}, so one
 * that is added later without a handler, or without anything that sends it,
 * fails here.
 *
 * <p>What the page sends is checked by behaviour: every sample goes through a
 * real controller on the fake agent, and a message nobody takes is the one the
 * controller logs as "page message without a handler". What the host sends is
 * checked in the sources: the record of each type must be constructed in the
 * code of one of the two bundles.
 *
 * <p>The features are installed here as {@code KiroChat.install} of the ui
 * bundle installs them, on fakes; that the real installation leaves no type
 * without a handler is {@code KiroChatFeaturesTest} of the ui tests, through
 * {@link ChatController#handles}, which the first test here ties to what the
 * controller really does.
 */
class PageProtocolCoverageTest {

	private static final String UNHANDLED = "page message without a handler: ";
	private static final String FORGOTTEN = "page message the controller should handle and does not";

	/** The types that restart the agent: sent last, each followed by a wait for the new process. */
	private static final Set<String> RESTARTING = Set.of("login", "restart");

	@TempDir
	Path dir;

	@Test
	void theSealedInterfacesTheirJsonNamesAndTheSamplesNameTheSameTypes() throws Exception {
		JsonNode samples = samples();
		for (Class<?> union : List.of(FromWebview.class, ToWebview.class)) {
			Set<String> registered = new TreeSet<>();
			Set<Class<?>> classes = new java.util.HashSet<>();
			for (JsonSubTypes.Type type : union.getAnnotation(JsonSubTypes.class).value()) {
				registered.add(type.name());
				classes.add(type.value());
			}
			assertEquals(Set.of(union.getPermittedSubclasses()), classes,
					"every record of " + union.getSimpleName() + " has a name in its @JsonSubTypes");
			Set<String> sampled = new TreeSet<>();
			samples.path(union == FromWebview.class ? "fromWebview" : "toWebview").fieldNames().forEachRemaining(sampled::add);
			assertEquals(sampled, registered, "the samples and the models of " + union.getSimpleName());
		}
		assertEquals(23, FromWebview.class.getPermittedSubclasses().length, "a new type: see that it is handled and count it here");
		assertEquals(22, ToWebview.class.getPermittedSubclasses().length, "a new type: see that it is sent and count it here");
	}

	@Test
	void withoutFeaturesTheControllerActsOnItsOwnMessagesAndLogsEveryOther() throws Exception {
		try (Harness h = Harness.launch(dir, "--scenario", "chat")) {
			Client view = h.openView("view");
			Map<String, Class<? extends FromWebview>> types = sendEverySample(h, view);
			assertTrue(h.launches.size() >= 2, "restart was acted on: " + h.launches.size());
			assertTrue(view.page.ofType(ToWebview.Turn.class).stream().anyMatch(turn -> "end_turn".equals(turn.stopReason())),
					"a prompt was answered");

			Set<String> unhandled = unhandled(h);
			Set<String> expected = new TreeSet<>();
			types.forEach((name, type) -> {
				if (!PageMessages.OWN.contains(type)) {
					expected.add(name);
				}
				assertEquals(PageMessages.OWN.contains(type), h.controller.handles(type), name);
			});
			assertEquals(expected, unhandled, "exactly the types that are not the controller's own are dropped: " + h.logger.lines);
			assertEquals(Set.of("browseSessions", "dropFiles", "login", "openDiff", "openPlan", "pickImages", "queryFiles", "removeChip"),
					unhandled, "the messages that belong to features");
			assertFalse(h.logger.has(FORGOTTEN), h.logger.lines.toString());
		}
	}

	@Test
	void withTheFeaturesInstalledEveryMessageThePageMaySendReachesAHandler() throws Exception {
		try (Harness h = Harness.launch(dir, "--scenario", "chat")) {
			installEveryFeature(h);
			Client view = h.openView("view");
			Map<String, Class<? extends FromWebview>> types = sendEverySample(h, view);
			assertTrue(h.launches.size() >= 2, "restart was acted on: " + h.launches.size());
			assertTrue(view.page.ofType(ToWebview.Turn.class).stream().anyMatch(turn -> "end_turn".equals(turn.stopReason())),
					"a prompt was answered");

			assertEquals(Set.of(), unhandled(h), h.logger.lines.toString());
			assertFalse(h.logger.has(FORGOTTEN), h.logger.lines.toString());
			for (Class<?> type : FromWebview.class.getPermittedSubclasses()) {
				assertTrue(h.controller.handles(type.asSubclass(FromWebview.class)), type.getSimpleName());
				assertTrue(types.containsValue(type), "a sample of " + type.getSimpleName() + " was sent");
			}
		}
	}

	@Test
	void everyMessageThePageUnderstandsIsBuiltSomewhereInThePlugIn() throws Exception {
		Path core = Path.of(System.getProperty("kiroacp.core.bundle")).toAbsolutePath().normalize();
		Path ui = core.resolveSibling("io.github.liaoalia.kiroacp.ui");
		StringBuilder sources = new StringBuilder();
		int files = 0;
		for (Path bundle : List.of(core, ui)) {
			Path src = bundle.resolve("src");
			assertTrue(Files.isDirectory(src), "sources of the bundle: " + src);
			try (Stream<Path> walk = Files.walk(src)) {
				for (Path file : walk.filter(path -> path.toString().endsWith(".java")).toList()) {
					// The models themselves only declare the records.
					if (!file.getFileName().toString().equals("ToWebview.java")) {
						sources.append(Files.readString(file, StandardCharsets.UTF_8)).append('\n');
						files++;
					}
				}
			}
		}
		assertTrue(files > 100, "the sources of both bundles were read: " + files);

		List<String> neverBuilt = new ArrayList<>();
		for (Class<?> type : ToWebview.class.getPermittedSubclasses()) {
			if (!sources.toString().contains("new ToWebview." + type.getSimpleName() + "(")) {
				neverBuilt.add(type.getSimpleName());
			}
		}
		assertEquals(List.of(), neverBuilt, "types of ToWebview that no production code constructs");
	}

	// --- plumbing ---

	/** The features of {@code KiroChat.install}, in its order, on fakes. */
	private static void installEveryFeature(Harness h) {
		SignIn signIn = SignIn.install(h.controller);
		PastSessions.install(h.controller, entries -> CompletableFuture.completedFuture(null));
		String[] stored = new String[1];
		RememberedOptions remembered = RememberedOptions.install(h.controller, () -> true, new OptionMemory() {
			@Override
			public String read() {
				return stored[0];
			}

			@Override
			public void write(String text) {
				stored[0] = text;
			}
		}, h.logger);
		DiffReview.install(h.controller, h.host, new FakeDiffEditors(h.scheduler::isControllerThread, h.signal::changed));
		ChatContext.install(h.controller,
				new ChatContext.Host(h.host.workspace(), h.settings, h.scheduler, new DiskTextFiles(), h.logger, null, null, null));
		Steering.install(h.controller);
		SlashCommands.install(h.controller, remembered::describe, signIn);
		UsageTracker.install(h.controller);
		PlanDocument.install(h.controller, new FakePlanDocuments(h.scheduler::isControllerThread, h.signal::changed), ZoneOffset.UTC,
				false);
	}

	/**
	 * Sends every sample of {@code fromWebview} through the page, one at a
	 * time: a prompt is given the time to be answered or refused, and the
	 * messages that start the agent again go last.
	 *
	 * @return the type of each sample that was sent, by its name in the protocol
	 */
	private static Map<String, Class<? extends FromWebview>> sendEverySample(Harness h, Client view) throws Exception {
		Map<String, List<FromWebview>> byName = new LinkedHashMap<>();
		for (Map.Entry<String, JsonNode> entry : samples().path("fromWebview").properties()) {
			List<FromWebview> messages = new ArrayList<>();
			for (JsonNode sample : entry.getValue()) {
				messages.add(UiJson.fromWebview(sample).orElseThrow(() -> new AssertionError("not understood: " + sample)));
			}
			assertFalse(messages.isEmpty(), "samples of " + entry.getKey());
			byName.put(entry.getKey(), messages);
		}
		Map<String, Class<? extends FromWebview>> types = new LinkedHashMap<>();
		List<String> order = new ArrayList<>(byName.keySet().stream().filter(name -> !RESTARTING.contains(name)).toList());
		order.addAll(byName.keySet().stream().filter(RESTARTING::contains).sorted().toList());
		for (String name : order) {
			for (FromWebview message : byName.get(name)) {
				types.put(name, message.getClass());
				int mark = view.mark();
				int launched = h.launches.size();
				view.send(message);
				h.scheduler.flush();
				if (message instanceof FromWebview.Prompt) {
					// Answered by a turn, or refused with a notice (a chip whose file is not there).
					h.await(() -> !view.page.ofType(ToWebview.Notice.class, mark).isEmpty() || turnEnded(view, mark),
							"the prompt " + message + " to be answered or refused");
					h.scheduler.flush();
					if (!turnEnded(view, mark) && view.snapshot().path("turnActive").asBoolean()) {
						h.await(() -> turnEnded(view, mark), "the turn of " + message + " to end");
					}
				} else if (RESTARTING.contains(name) && h.controller.handles(message.getClass())) {
					h.await(() -> h.launches.size() > launched || !view.page.ofType(ToWebview.Notice.class, mark).isEmpty(),
							name + " to start the agent again or to say why not");
					if (h.launches.size() > launched) {
						h.await(() -> view.page.jsonSince(mark).stream().anyMatch(posted -> "state".equals(posted.path("type").asText())
								&& posted.at("/state/sessionId").isTextual()), "a session of the new agent after " + name);
						h.awaitSettled(view);
					}
				}
				h.scheduler.flush();
			}
		}
		return types;
	}

	private static boolean turnEnded(Client view, int mark) {
		return view.page.ofType(ToWebview.Turn.class, mark).stream().anyMatch(turn -> !turn.active());
	}

	private static Set<String> unhandled(Harness h) {
		Set<String> names = new TreeSet<>();
		for (String line : h.logger.lines) {
			int at = line.indexOf(UNHANDLED);
			if (at >= 0) {
				names.add(line.substring(at + UNHANDLED.length()).strip());
			}
		}
		return names;
	}

	private static JsonNode samples() throws IOException {
		return Harness.MAPPER.readTree(Files.readString(FakeAgent.fixtures().resolve("protocol-samples.json")));
	}
}
