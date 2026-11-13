package io.github.liaoalia.kiroacp.core.pure.slash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import io.github.liaoalia.kiroacp.core.model.CommandView;
import io.github.liaoalia.kiroacp.core.model.ConfigOptionView;
import io.github.liaoalia.kiroacp.core.model.ConfigOptionView.Choice;
import io.github.liaoalia.kiroacp.core.model.ModeView;
import io.github.liaoalia.kiroacp.core.model.ModesView;
import io.github.liaoalia.kiroacp.core.pure.slash.Slash.Action;
import io.github.liaoalia.kiroacp.core.pure.slash.Slash.Context;
import io.github.liaoalia.kiroacp.core.pure.slash.Slash.ExtCommand;
import io.github.liaoalia.kiroacp.core.pure.slash.Slash.Parsed;
import io.github.liaoalia.kiroacp.core.pure.slash.Slash.UnavailableCommand;

/** The cases of the reference's {@code test/features-slashCommands.test.ts}. */
class SlashTest {

	private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

	private static final Context V3 = new Context(List.of("_kiro/account/getUsage", "_kiro/session/context"),
			new ModesView("vibe", List.of(new ModeView("vibe", "Autonomous", null), new ModeView("plan", "Plan", null))),
			List.of(new ConfigOptionView("model", "Model", null, "model", ConfigOptionView.Type.SELECT, JSON.textNode("auto"),
					List.of(new Choice("auto", "Auto", null, null), new Choice("claude-sonnet", "Claude Sonnet", null, null))),
					new ConfigOptionView("effortLevel", "Effort", null, null, ConfigOptionView.Type.SELECT, JSON.textNode("medium"),
							List.of(new Choice("low", "Low", null, null), new Choice("high", "High", null, null)))),
			List.of(new CommandView("review", "Custom prompt", null)));

	private static final Context BARE = Context.BARE;

	private static Action route(String text, Context ctx) {
		return Slash.route(Slash.parse(text), ctx);
	}

	private static Context methods(String... names) {
		return new Context(List.of(names), null, List.of(), List.of());
	}

	private static List<String> names(List<CommandView> commands) {
		return commands.stream().map(CommandView::name).toList();
	}

	private static List<String> unavailable(List<UnavailableCommand> commands) {
		return commands.stream().map(UnavailableCommand::name).toList();
	}

	@Test
	void parseSplitsNameAndArgsAndIgnoresNonCommands() {
		assertEquals(new Parsed("clear", ""), Slash.parse("/clear"));
		assertEquals(new Parsed("plan", "build a REST API"), Slash.parse("  /Plan  build a REST API "));
		assertNull(Slash.parse("hello /clear"));
		assertNull(Slash.parse("/"));
		// Beyond the reference's cases: the arguments may span lines, and a name is letters, digits, _ and -.
		assertEquals(new Parsed("plan", "one\ntwo"), Slash.parse("/plan one\ntwo"));
		assertEquals(new Parsed("my-cmd_2", "x"), Slash.parse("/my-cmd_2 x"));
		assertNull(Slash.parse("/2fast"));
		assertNull(Slash.parse(null));
		assertEquals("/plan build", new Parsed("plan", "build").raw());
		assertEquals("/clear", new Parsed("clear", "").raw());
	}

	@Test
	void sessionCommandsAreClientSide() {
		assertEquals(new Action.NewSession(true), route("/clear", BARE));
		assertEquals(new Action.NewSession(false), route("/new", BARE));
		assertEquals(new Action.Cancel(), route("/cancel", BARE));
		assertEquals(new Action.Cancel(), route("/stop", BARE));
		assertEquals(new Action.Restart(), route("/restart", BARE));
		assertEquals(new Action.Login(), route("/login", BARE));
		Action.Help help = assertInstanceOf(Action.Help.class, route("/help", BARE));
		assertEquals(List.of("usage", "context", "compact", "effort", "model"), unavailable(help.unavailable()));
		Action.Help helpV3 = assertInstanceOf(Action.Help.class, route("/help", V3));
		assertEquals(List.of("compact"), unavailable(helpV3.unavailable()));
	}

	@Test
	void extensionBackedCommandsAreGatedOnTheAdvertisedMethodList() {
		assertEquals(new Action.Extension("_kiro/account/getUsage", "usage"), route("/usage", V3));
		assertInstanceOf(Action.Unavailable.class, route("/compact", V3));
		assertInstanceOf(Action.Unavailable.class, route("/usage", BARE));
	}

	@Test
	void extensionBackedCommandsMatchAdvertisedMethodsByPatternNotByExactName() {
		Context renamed = methods("_kiro/Quota/get", "_kiro/session/summarize", "_kiro/other/Context", "_kiro/contextual/thing");
		assertEquals(new Action.Extension("_kiro/Quota/get", "usage"), route("/usage", renamed));
		assertEquals(new Action.Extension("_kiro/session/summarize", "compact"), route("/compact", renamed));
		// `context` must end the name (or be `session/context`); `_kiro/contextual/thing` does not qualify.
		assertEquals(new Action.Extension("_kiro/other/Context", "context"), route("/context", renamed));
		assertNull(Slash.findExtension(List.of("_kiro/contextual/thing"), ExtCommand.CONTEXT));
		assertEquals("_kiro/account/getCredits", Slash.findExtension(List.of("_kiro/account/getCredits"), ExtCommand.USAGE));
		assertEquals(List.of("clear", "new", "help", "capabilities", "usage", "context", "compact", "cancel", "restart", "login"),
				names(Slash.builtinCommands(renamed)));
		assertEquals(List.of("effort", "model"), unavailable(Slash.unavailableCommands(renamed)));
		Action.Help help = assertInstanceOf(Action.Help.class, route("/help", BARE));
		String reason = help.unavailable().stream().filter(u -> u.name().equals("usage")).findFirst().orElseThrow().reason();
		assertTrue(reason.contains("usage extension method"), reason);
		assertEquals("the agent did not advertise a usage extension method (such as _kiro/account/getUsage)", reason);
	}

	@Test
	void capabilitiesAndCapsAreAnsweredLocally() {
		assertEquals(new Action.Capabilities(), route("/capabilities", BARE));
		assertEquals(new Action.Capabilities(), route("/caps extra", V3));
		assertTrue(names(Slash.builtinCommands(BARE)).contains("capabilities"));
	}

	@Test
	void modelAndEffortMapToConfigOptionsListingValuesWhenTheArgumentIsMissingOrWrong() {
		assertEquals(new Action.SetConfig("model", "claude-sonnet"), route("/model claude-sonnet", V3));
		// Matching by display name is accepted too.
		assertEquals(new Action.SetConfig("model", "auto"), route("/model Auto", V3));
		assertEquals(new Action.ShowOptions("model"), route("/model", V3));
		assertEquals(new Action.ShowOptions("model"), route("/model gpt-9", V3));
		assertEquals(new Action.SetConfig("effortLevel", "high"), route("/effort high", V3));
		assertEquals(new Action.Unavailable("model", "the agent did not advertise a \"model\" option"), route("/model x", BARE));
	}

	@Test
	void modeAgentAndPlanUseSessionModes() {
		assertEquals(new Action.SetMode("plan"), route("/mode plan", V3));
		assertEquals(new Action.SetMode("vibe"), route("/agent Autonomous", V3));
		assertEquals(new Action.ShowOptions(null), route("/mode", V3));
		assertEquals(new Action.ShowOptions(null), route("/mode nonsense", V3));
		assertEquals(new Action.Plan("add tests", "plan"), route("/plan add tests", V3));
		assertEquals(new Action.Plan("", null), route("/plan", BARE));
		// Without session modes `/mode` falls back to a config option of that name.
		assertInstanceOf(Action.Unavailable.class, route("/mode plan", BARE));
	}

	@Test
	void thePlannerModeIsTheOneNamedPlanBeforeAnyThatStartsWithIt() {
		// A real Kiro workspace also had a custom agent named "qc-sprint-planner".
		Context custom = new Context(List.of(), new ModesView("vibe", List.of(new ModeView("planner-x", "Planner X", null),
				new ModeView("qc-sprint-planner", "QC", null), new ModeView("plan", "Plan", null))), List.of(), List.of());
		assertEquals("plan", Slash.plannerMode(custom).id());
		Context byName = new Context(List.of(), new ModesView("a", List.of(new ModeView("planner-x", "Planner X", null),
				new ModeView("p", "PLAN", null))), List.of(), List.of());
		assertEquals("p", Slash.plannerMode(byName).id());
		Context prefix = new Context(List.of(), new ModesView("a", List.of(new ModeView("qc-sprint-planner", "QC", null),
				new ModeView("planner", "Thinks first", null))), List.of(), List.of());
		assertEquals("planner", Slash.plannerMode(prefix).id());
		assertNull(Slash.plannerMode(new Context(List.of(), new ModesView("a", List.of(new ModeView("planning", "Planning", null))),
				List.of(), List.of())), "\"planning\" is not \"plan\" or \"planner\"");
	}

	@Test
	void agentAdvertisedAndUnknownCommandsAreForwardedToTheAgent() {
		assertEquals(new Action.Forward(), route("/review src", V3));
		assertEquals(new Action.Forward(), route("/frobnicate", V3));
		// A command of the agent's that the client cannot serve itself goes to the agent.
		Context own = new Context(List.of(), null, List.of(), List.of(new CommandView("usage", "agent's", null),
				new CommandView("model", "agent's", null), new CommandView("plan", "agent's", null)));
		assertEquals(new Action.Forward(), route("/usage", own));
		assertEquals(new Action.Forward(), route("/model x", own));
		assertEquals(new Action.Forward(), route("/plan x", own));
	}

	@Test
	void builtinCommandsAndMergeCommandsReflectCapabilitiesAndKeepAgentCommandsFirst() {
		assertEquals(List.of("clear", "new", "help", "capabilities", "mode", "plan", "model", "effort", "usage", "context", "cancel",
				"restart", "login"), names(Slash.builtinCommands(V3)));
		assertEquals(List.of("clear", "new", "help", "capabilities", "cancel", "restart", "login"), names(Slash.builtinCommands(BARE)));
		List<CommandView> agent = new ArrayList<>();
		agent.add(new CommandView("clear", "agent's own clear", null));
		agent.addAll(V3.agentCommands());
		List<CommandView> merged = Slash.mergeCommands(agent, V3);
		assertEquals("agent's own clear", merged.get(0).description());
		assertEquals(1, merged.stream().filter(c -> c.name().equals("clear")).count());
		assertTrue(names(merged).contains("review"));
		assertEquals(new CommandView("mode", "Switch the agent mode", "mode id"),
				merged.stream().filter(c -> c.name().equals("mode")).findFirst().orElseThrow());
	}

	@Test
	void describeOptionsAndFormatExtensionResultProduceReadableText() throws Exception {
		String text = Slash.describeOptions(V3, null);
		assertEquals("Modes (current: vibe): vibe (Autonomous), plan (Plan)\n"
				+ "model (current: auto): auto (Auto), claude-sonnet (Claude Sonnet)\n"
				+ "effortLevel (current: medium): low (Low), high (High)", text);
		assertEquals("model (current: auto): auto (Auto), claude-sonnet (Claude Sonnet)", Slash.describeOptions(V3, "model"));
		assertEquals("The agent did not advertise any options.", Slash.describeOptions(BARE, null));
		Context flag = new Context(List.of(), null, List.of(new ConfigOptionView("thinking", "Thinking", null, null,
				ConfigOptionView.Type.BOOLEAN, JSON.booleanNode(true), null)), List.of());
		assertEquals("thinking: true (true/false)", Slash.describeOptions(flag, null));

		assertEquals("plain", Slash.formatExtensionResult(JSON.textNode("plain")));
		assertEquals("```json\n{\n  \"used\": 1\n}\n```", Slash.formatExtensionResult(JSON.objectNode().put("used", 1)));
		assertEquals("(no result)", Slash.formatExtensionResult(null));
		assertEquals("(no result)", Slash.formatExtensionResult(JSON.nullNode()));
		// As JSON.stringify(value, null, 2) writes it.
		JsonNode nested = new ObjectMapper().readTree("{\"ok\":true,\"usage\":{\"used\":12,\"limit\":100.0},\"list\":[1,\"a\\n\",[],{}],\"none\":null}");
		assertEquals("{\n  \"ok\": true,\n  \"usage\": {\n    \"used\": 12,\n    \"limit\": 100\n  },\n  \"list\": [\n    1,\n    \"a\\n\",\n    [],\n    {}\n  ],\n  \"none\": null\n}",
				Slash.prettyJson(nested));
	}

	@Test
	void helpListsTheCommandsWithTheirHintsAndWhatIsNotAvailable() {
		Action.Help help = assertInstanceOf(Action.Help.class, route("/help", V3));
		String text = Slash.describeHelp(help);
		assertTrue(text.startsWith("Available commands:\n- `/clear` — Start over: a fresh session replaces this one\n"), text);
		assertTrue(text.contains("\n- `/mode` *mode id* — Switch the agent mode\n"), text);
		assertTrue(text.endsWith("\n\nNot available with this agent:\n- `/compact` — the agent did not advertise a compact extension "
				+ "method (such as _kiro/session/compact)"), text);
		Context all = methods("_kiro/account/getUsage", "_kiro/session/context", "_kiro/session/compact");
		Context full = new Context(all.extensionMethods(), V3.modes(), V3.configOptions(), List.of());
		assertTrue(!Slash.describeHelp((Action.Help) route("/help", full)).contains("Not available"));
	}
}
