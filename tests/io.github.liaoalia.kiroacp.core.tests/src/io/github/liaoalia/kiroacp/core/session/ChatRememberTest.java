package io.github.liaoalia.kiroacp.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import io.github.liaoalia.kiroacp.core.model.FromWebview;
import io.github.liaoalia.kiroacp.core.model.ToWebview;
import io.github.liaoalia.kiroacp.core.session.Harness.Client;
import io.github.liaoalia.kiroacp.core.session.history.OptionMemory;
import io.github.liaoalia.kiroacp.core.session.history.PastSessions;
import io.github.liaoalia.kiroacp.core.session.history.RememberedOptions;

/**
 * Mode and config options chosen once are given to every new session
 * (reference D24; e2e F13), against the fake agent: it ignores the hints on
 * {@code session/new}, so what is seen here is what is set once the session
 * exists.
 */
class ChatRememberTest {

	private static final String DEFAULT_MODEL = "claude-sonnet-4";
	private static final String CHOSEN_MODEL = "claude-opus-4";

	/** The workspace's memory, in memory. */
	private static final class FakeMemory implements OptionMemory {
		volatile String text;
		/** Wakes whoever waits for something to be remembered. */
		volatile Runnable changed = () -> {
		};

		@Override
		public String read() {
			return text;
		}

		@Override
		public void write(String next) {
			text = next;
			changed.run();
		}
	}

	@TempDir
	Path dir;

	private final FakeMemory memory = new FakeMemory();
	private final AtomicBoolean enabled = new AtomicBoolean(true);

	@Test
	void aModelAndAModeChosenInOneSessionAreGivenToTheNextUntilTheyAreForgotten() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			memory.changed = h.signal::changed;
			RememberedOptions options = RememberedOptions.install(h.controller, enabled::get, memory, h.logger);
			Client view = h.openView("view");
			String a = view.sessionId();
			assertNull(options.remembered(), "nothing is remembered to begin with");
			assertEquals("none", options.describe());
			assertEquals(DEFAULT_MODEL, model(view));
			assertEquals("vibe", mode(view));

			choose(h, view, "model", CHOSEN_MODEL);
			int mark = view.mark();
			view.send(new FromWebview.SetMode("plan"));
			view.page.await(ToWebview.Modes.class, mark, m -> m.modes().currentModeId().equals("plan"), "the mode to change");
			h.await(() -> options.remembered() != null && "plan".equals(options.remembered().modeId()), "the mode to be remembered");

			assertEquals(CHOSEN_MODEL, options.remembered().configOptions().get("model").asText(), "the model is remembered");
			assertEquals(Harness.json("{\"modeId\":\"plan\",\"configOptions\":{\"model\":\"claude-opus-4\"}}"), Harness.json(memory.text),
					"as the reference stores it");
			assertEquals("mode `plan`, `model` = `claude-opus-4`", options.describe());

			// The next session starts with both, and the page never sees it without them.
			String b = newSession(h, view);
			assertNotEquals(a, b);
			ToWebview.State first = view.page.ofType(ToWebview.State.class).stream().filter(s -> b.equals(s.state().sessionId()))
					.findFirst().orElseThrow();
			assertEquals("plan", first.state().modes().currentModeId());
			assertEquals(CHOSEN_MODEL, first.state().configOptions().get(0).currentValue().asText());
			assertEquals(CHOSEN_MODEL, model(view), "session B starts with the remembered model");
			assertEquals("plan", mode(view), "and in the remembered mode");
			assertTrue(h.logger.has("session " + b + ": remembered options applied: model=claude-opus-4, mode=plan"),
					h.logger.lines.toString());
			assertEquals("medium", view.snapshot().at("/configOptions/1/currentValue").asText(), "what was never chosen is the agent's");

			// A restored session keeps its own: nothing is applied on session/load.
			PastSessions past = PastSessions.install(h.controller, entries -> null);
			assertEquals("past-1", h.get(past.open("past-1", view.handle)));
			h.scheduler.flush();
			assertEquals("vibe", mode(view));
			assertEquals(DEFAULT_MODEL, model(view));
			assertEquals(1, h.logger.lines.stream().filter(line -> line.contains("remembered options applied")).count());

			// Forgotten: the next session has the agent's defaults again.
			options.forget();
			assertNull(options.remembered());
			assertNull(memory.text);
			assertTrue(h.logger.has("remembered session options forgotten"));
			newSession(h, view);
			assertEquals(DEFAULT_MODEL, model(view), "the model is the default again");
			assertEquals("vibe", mode(view), "and so is the mode");
		}
	}

	@Test
	void withTheSettingOffNothingIsAppliedAndNothingIsRecorded() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			memory.changed = h.signal::changed;
			RememberedOptions options = RememberedOptions.install(h.controller, enabled::get, memory, h.logger);
			Client view = h.openView("view");
			choose(h, view, "model", CHOSEN_MODEL);
			h.await(() -> options.remembered() != null, "the model to be remembered while the setting is on");

			enabled.set(false);
			String kept = memory.text;
			newSession(h, view);
			assertEquals(DEFAULT_MODEL, model(view), "not applied while the setting is off");
			assertEquals("`model` = `claude-opus-4` (not applied: \"Remember session options\" is off)", options.describe());
			// A choice made while the setting is off is not recorded either.
			choose(h, view, "effortLevel", "high");
			h.scheduler.flush();
			assertEquals(kept, memory.text, "what was stored stays as it was");
			assertNull(options.remembered().configOptions().get("effortLevel"));

			// On again: what was stored counts again, until it is forgotten.
			enabled.set(true);
			newSession(h, view);
			assertEquals(CHOSEN_MODEL, model(view));
			options.forget();
			newSession(h, view);
			assertEquals(DEFAULT_MODEL, model(view), "the defaults again once forgotten");
			enabled.set(false);
			assertEquals("none (\"Remember session options\" is off)", options.describe());
		}
	}

	@Test
	void whatTheAgentDoesNotOfferIsSkippedAndNeverKeepsASessionFromOpening() throws Exception {
		// Remembered with another account or another version of the CLI.
		memory.text = "{\"modeId\":\"architect\",\"modelId\":\"gone-model\",\"configOptions\":"
				+ "{\"model\":\"retired-model\",\"effortLevel\":\"high\",\"autoCompact\":false,\"verbosity\":\"loud\"}}";
		try (Harness h = Harness.launch(dir)) {
			RememberedOptions.install(h.controller, enabled::get, memory, h.logger);
			Client view = h.openView("view");

			assertEquals("vibe", mode(view), "a mode the agent does not have is not asked for");
			assertEquals(DEFAULT_MODEL, model(view));
			assertEquals("high", view.snapshot().at("/configOptions/1/currentValue").asText(), "what it does offer is applied");
			assertEquals(false, view.snapshot().at("/configOptions/2/currentValue").asBoolean(true));
			assertTrue(h.logger.has("session " + view.sessionId() + ": remembered options applied: effortLevel=high, autoCompact=false; "
					+ "skipped: model=retired-model (not an advertised value), verbosity (not advertised), mode architect (not advertised), "
					+ "model gone-model (not advertised)"), h.logger.lines.toString());
			assertEquals("end_turn", view.turn("/scenario chat").stopReason());
		}
	}

	/** Chooses a value in the page's picker and waits until the page shows it. */
	private static void choose(Harness h, Client view, String configId, String value) throws Exception {
		int mark = view.mark();
		view.send(new FromWebview.SetConfigOption(configId, JsonNodeFactory.instance.textNode(value)));
		view.page.await(ToWebview.ConfigOptions.class, mark,
				c -> c.options().stream().anyMatch(o -> o.id().equals(configId) && o.currentValue().asText().equals(value)),
				configId + " = " + value);
	}

	/** "New session" in the page; returns once the page shows it, settled. */
	private static String newSession(Harness h, Client view) throws Exception {
		String before = view.sessionId();
		int mark = view.mark();
		view.send(new FromWebview.NewSession());
		view.page.await(ToWebview.Session.class, mark, s -> s.sessionId() != null && !s.sessionId().equals(before), "the new session");
		h.scheduler.flush();
		return view.sessionId();
	}

	private static String model(Client view) throws Exception {
		JsonNode options = view.snapshot().path("configOptions");
		assertEquals("model", options.at("/0/id").asText());
		return options.at("/0/currentValue").asText();
	}

	private static String mode(Client view) throws Exception {
		return view.snapshot().at("/modes/currentModeId").asText();
	}
}
