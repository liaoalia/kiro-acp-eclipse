package io.github.liaoalia.kiroacp.core.session.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.TextNode;

import io.github.liaoalia.kiroacp.core.acp.api.KiroAgent;
import io.github.liaoalia.kiroacp.core.acp.api.ModelState;
import io.github.liaoalia.kiroacp.core.acp.api.SessionState;
import io.github.liaoalia.kiroacp.core.host.Logger;
import io.github.liaoalia.kiroacp.core.session.ChatHooks.Option;
import io.github.liaoalia.kiroacp.core.session.history.RememberedOptions.Remembered;

/**
 * What is remembered when, what a new session is given and what is left out
 * (reference D24), without an agent process: a memory in memory and an agent
 * that writes down what it is asked.
 */
class RememberedOptionsTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	private static final class FakeMemory implements OptionMemory {
		String text;
		int writes;

		@Override
		public String read() {
			return text;
		}

		@Override
		public void write(String next) {
			text = next;
			writes++;
		}
	}

	private static final class Lines implements Logger {
		final List<String> lines = new ArrayList<>();

		@Override
		public void info(String message) {
			lines.add("info: " + message);
		}

		@Override
		public void warn(String message) {
			lines.add("warn: " + message);
		}

		@Override
		public void error(String message, Throwable cause) {
			lines.add("error: " + message);
		}
	}

	private final FakeMemory memory = new FakeMemory();
	private final Lines log = new Lines();
	private final AtomicBoolean enabled = new AtomicBoolean(true);
	private final List<String> sent = new ArrayList<>();
	/** Requests the agent refuses, as they are written to {@link #sent}. */
	private final List<String> refused = new ArrayList<>();
	private final RememberedOptions options = new RememberedOptions(enabled::get, memory, log);

	// --- what is remembered ---

	@Test
	void nothingStoredIsNothingRemembered() {
		assertNull(options.remembered());
		assertNull(options.hints());
		assertEquals("none", options.describe());
		memory.text = "";
		assertNull(options.remembered());
		memory.text = "not json {";
		assertNull(options.remembered(), "what cannot be read counts as nothing");
		memory.text = "[1,2]";
		assertNull(options.remembered());
		memory.text = "{\"modeId\":\"\",\"modelId\":7,\"configOptions\":{\"depth\":3,\"shape\":null,\"list\":[]}}";
		assertNull(options.remembered(), "neither empty ids nor values that are no text and no flag");
		memory.text = "{\"configOptions\":\"model\"}";
		assertNull(options.remembered());
	}

	@Test
	void choicesAreMergedIntoWhatIsRemembered() throws Exception {
		options.remember(Option.MODE, "plan", TextNode.valueOf("plan"));
		assertEquals(json("{\"modeId\":\"plan\",\"configOptions\":{}}"), json(memory.text));
		assertEquals(new Remembered("plan", null, java.util.Map.of()), options.remembered());

		options.remember(Option.CONFIG, "model", TextNode.valueOf("claude-opus-4"));
		options.remember(Option.CONFIG, "autoCompact", BooleanNode.FALSE);
		options.remember(Option.MODEL, "nova-pro", TextNode.valueOf("nova-pro"));
		assertEquals(json("{\"modeId\":\"plan\",\"modelId\":\"nova-pro\",\"configOptions\":{\"model\":\"claude-opus-4\",\"autoCompact\":false}}"),
				json(memory.text));

		// A later choice replaces the earlier one of its kind and leaves the rest.
		options.remember(Option.CONFIG, "model", TextNode.valueOf("nova-pro"));
		options.remember(Option.MODE, "vibe", null);
		Remembered remembered = options.remembered();
		assertEquals("vibe", remembered.modeId());
		assertEquals("nova-pro", remembered.modelId());
		assertEquals(List.of("model", "autoCompact"), List.copyOf(remembered.configOptions().keySet()), "in the order they were first chosen");
		assertEquals(TextNode.valueOf("nova-pro"), remembered.configOptions().get("model"));
		assertEquals(BooleanNode.FALSE, remembered.configOptions().get("autoCompact"));
		assertEquals("mode `vibe`, model `nova-pro`, `model` = `nova-pro`, `autoCompact` = `false`", options.describe());

		// Neither a number nor nothing is a value of a config option.
		int writes = memory.writes;
		options.remember(Option.CONFIG, "depth", IntNode.valueOf(3));
		options.remember(Option.CONFIG, "depth", null);
		assertEquals(writes, memory.writes);
	}

	@Test
	void withTheSettingOffNothingIsRecordedAndNothingIsHinted() {
		options.remember(Option.MODE, "plan", null);
		enabled.set(false);
		int writes = memory.writes;

		options.remember(Option.CONFIG, "model", TextNode.valueOf("nova-pro"));
		options.remember(Option.MODE, "vibe", null);

		assertEquals(writes, memory.writes);
		assertEquals("plan", options.remembered().modeId(), "what is stored stays, and can still be read");
		assertNull(options.hints(), "but a new session gets none of it");
		assertEquals("mode `plan` (not applied: \"Remember session options\" is off)", options.describe());
		enabled.set(true);
		assertEquals(json("{\"kiro\":{\"modeId\":\"plan\"}}"), options.hints());
	}

	@Test
	void forgettingClearsTheMemoryWhateverTheSetting() {
		options.remember(Option.MODE, "plan", null);
		enabled.set(false);

		options.forget();

		assertNull(memory.text);
		assertNull(options.remembered());
		assertEquals("none (\"Remember session options\" is off)", options.describe());
		assertTrue(log.lines.contains("info: remembered session options forgotten"), log.lines.toString());
	}

	// --- the hints on session/new ---

	@Test
	void theHintsNameTheModeAndTheModelAndFallBackToTheModelOption() {
		memory.text = "{\"modeId\":\"plan\",\"modelId\":\"nova-pro\",\"configOptions\":{\"model\":\"claude-opus-4\"}}";
		assertEquals(json("{\"kiro\":{\"modeId\":\"plan\",\"modelId\":\"nova-pro\"}}"), options.hints(), "the v2 model wins");
		memory.text = "{\"configOptions\":{\"model\":\"claude-opus-4\",\"effortLevel\":\"high\"}}";
		assertEquals(json("{\"kiro\":{\"modelId\":\"claude-opus-4\"}}"), options.hints(), "else the `model` config option");
		memory.text = "{\"configOptions\":{\"effortLevel\":\"high\"}}";
		assertNull(options.hints(), "nothing to hint: a plain session/new");
		memory.text = "{\"configOptions\":{\"model\":true}}";
		assertNull(options.hints());
	}

	// --- what a new session is given ---

	@Test
	void aNewSessionIsGivenWhatDiffersConfigOptionsFirstThenTheModeThenTheModel() {
		memory.text = "{\"modeId\":\"plan\",\"modelId\":\"nova-pro\",\"configOptions\":"
				+ "{\"model\":\"claude-opus-4\",\"effortLevel\":\"medium\",\"autoCompact\":false}}";

		options.apply(agent(), v3AndV2Session(), options.remembered()).join();

		assertEquals(List.of("setConfigOption s1 model \"claude-opus-4\"", "setConfigOption s1 autoCompact false", "setMode s1 plan",
				"setModel s1 nova-pro"), sent, "effortLevel is medium already");
		assertEquals(List.of("info: session s1: remembered options applied: model=claude-opus-4, autoCompact=false, mode=plan, model=nova-pro"),
				log.lines);
	}

	@Test
	void aSessionThatHasItAllIsAskedNothingAndNothingIsLogged() {
		memory.text = "{\"modeId\":\"vibe\",\"modelId\":\"claude-sonnet-4\",\"configOptions\":"
				+ "{\"model\":\"claude-sonnet-4\",\"effortLevel\":\"medium\",\"autoCompact\":true}}";

		options.apply(agent(), v3AndV2Session(), options.remembered()).join();

		assertEquals(List.of(), sent);
		assertEquals(List.of(), log.lines);
	}

	@Test
	void whatTheAgentDoesNotAdvertiseIsNeverSent() {
		memory.text = "{\"modeId\":\"architect\",\"modelId\":\"gone\",\"configOptions\":{\"model\":\"retired\",\"verbosity\":\"loud\","
				+ "\"autoCompact\":\"yes\",\"effortLevel\":true}}";

		options.apply(agent(), v3AndV2Session(), options.remembered()).join();

		assertEquals(List.of(), sent);
		assertEquals(List.of("info: session s1: remembered options applied: none; skipped: model=retired (not an advertised value), "
				+ "verbosity (not advertised), autoCompact=yes (not an advertised value), effortLevel=true (not an advertised value), "
				+ "mode architect (not advertised), model gone (not advertised)"), log.lines);
	}

	@Test
	void aSessionWithoutModesOptionsOrModelsSkipsThemAll() {
		memory.text = "{\"modeId\":\"plan\",\"modelId\":\"nova-pro\",\"configOptions\":{\"model\":\"claude-opus-4\"}}";
		SessionState bare = new SessionState("s1", "/work", null, null, null, List.of(), null);

		options.apply(agent(), bare, options.remembered()).join();

		assertEquals(List.of(), sent);
		assertEquals(List.of("info: session s1: remembered options applied: none; skipped: model (not advertised), "
				+ "mode plan (not advertised), model nova-pro (not advertised)"), log.lines);
	}

	@Test
	void aRequestTheAgentRefusesIsLoggedAndTheOthersStillGoOut() {
		memory.text = "{\"modeId\":\"plan\",\"modelId\":\"nova-pro\",\"configOptions\":{\"model\":\"claude-opus-4\",\"autoCompact\":false}}";
		refused.add("setConfigOption s1 model \"claude-opus-4\"");
		refused.add("setMode s1 plan");

		CompletableFuture<Void> applied = options.apply(agent(), v3AndV2Session(), options.remembered());

		assertTrue(applied.isDone() && !applied.isCompletedExceptionally(), "applying never fails");
		assertEquals(4, sent.size(), sent.toString());
		assertEquals(List.of("warn: session s1: could not apply remembered model=claude-opus-4: refused",
				"warn: session s1: could not apply remembered mode plan: refused",
				"info: session s1: remembered options applied: autoCompact=false, model=nova-pro"), log.lines);
	}

	@Test
	void groupedAndPlainSelectValuesBothCountAsAdvertised() {
		memory.text = "{\"configOptions\":{\"model\":\"nova-pro\",\"effortLevel\":\"low\"}}";

		options.apply(agent(), v3AndV2Session(), options.remembered()).join();

		assertEquals(List.of("setConfigOption s1 model \"nova-pro\"", "setConfigOption s1 effortLevel \"low\""), sent);
	}

	// --- fakes ---

	/** An agent that accepts what it is asked, except {@link #refused}, and writes it down. */
	private KiroAgent agent() {
		return (KiroAgent) Proxy.newProxyInstance(KiroAgent.class.getClassLoader(), new Class<?>[] { KiroAgent.class },
				(proxy, method, args) -> {
					StringBuilder call = new StringBuilder(method.getName());
					for (Object arg : args == null ? new Object[0] : args) {
						call.append(' ').append(arg);
					}
					sent.add(call.toString());
					if (refused.contains(call.toString())) {
						return CompletableFuture.failedFuture(new IllegalStateException("refused"));
					}
					return CompletableFuture.completedFuture(method.getName().equals("setConfigOption") ? List.of() : null);
				});
	}

	/** A session with the fake agent's modes and config options, and with v2 models as well. */
	private static SessionState v3AndV2Session() {
		JsonNode modes = json("{\"currentModeId\":\"vibe\",\"availableModes\":[{\"id\":\"vibe\",\"name\":\"Vibe\"},{\"id\":\"plan\",\"name\":\"Plan\"}]}");
		List<JsonNode> configOptions = List.of(
				json("{\"id\":\"model\",\"type\":\"select\",\"currentValue\":\"claude-sonnet-4\",\"options\":["
						+ "{\"group\":\"anthropic\",\"name\":\"Anthropic\",\"options\":[{\"value\":\"claude-sonnet-4\"},{\"value\":\"claude-opus-4\"}]},"
						+ "{\"group\":\"amazon\",\"name\":\"Amazon\",\"options\":[{\"value\":\"nova-pro\"}]}]}"),
				json("{\"id\":\"effortLevel\",\"type\":\"select\",\"currentValue\":\"medium\",\"options\":["
						+ "{\"value\":\"low\"},{\"value\":\"medium\"},{\"value\":\"high\"}]}"),
				json("{\"id\":\"autoCompact\",\"type\":\"boolean\",\"currentValue\":true}"));
		ModelState models = new ModelState("claude-sonnet-4",
				List.of(new ModelState.Model("claude-sonnet-4", "Claude Sonnet 4", null), new ModelState.Model("nova-pro", "Nova Pro", null)));
		return new SessionState("s1", "/work", modes, configOptions, models, List.of(), null);
	}

	private static JsonNode json(String text) {
		try {
			return JSON.readTree(text);
		} catch (JsonProcessingException e) {
			throw new IllegalArgumentException(text, e);
		}
	}

}
