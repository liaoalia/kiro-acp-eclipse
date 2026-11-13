package io.github.liaoalia.kiroacp.core.pure.slash;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.liaoalia.kiroacp.core.model.CommandView;
import io.github.liaoalia.kiroacp.core.model.ConfigOptionView;
import io.github.liaoalia.kiroacp.core.model.ModeView;
import io.github.liaoalia.kiroacp.core.model.ModesView;
import io.github.liaoalia.kiroacp.core.pure.text.JsText;

/**
 * Client-side slash command router (the reference's
 * {@code features/pure/slashCommands.ts}, its decision D18).
 *
 * <p>Kiro CLI V3 no longer executes slash commands sent as prompt text; its
 * ACP migration guide maps each command to client behaviour, a standard ACP
 * method, or an advertised {@code _kiro/...} extension. This decides what a
 * {@code /command} typed in the chat should do, given what the running agent
 * advertised.
 */
public final class Slash {

	/**
	 * What the agent advertised.
	 *
	 * @param extensionMethods {@code agentCapabilities._meta.kiro.extensionMethods} from initialize
	 * @param modes            the session's modes, or {@code null}
	 * @param agentCommands    commands the agent advertised through {@code available_commands_update}
	 */
	public record Context(List<String> extensionMethods, ModesView modes, List<ConfigOptionView> configOptions,
			List<CommandView> agentCommands) {

		public static final Context BARE = new Context(List.of(), null, List.of(), List.of());

		public Context {
			extensionMethods = extensionMethods == null ? List.of() : List.copyOf(extensionMethods);
			configOptions = configOptions == null ? List.of() : List.copyOf(configOptions);
			agentCommands = agentCommands == null ? List.of() : List.copyOf(agentCommands);
		}
	}

	/**
	 * @param name lower case
	 * @param args everything after the command name, trimmed
	 */
	public record Parsed(String name, String args) {

		/** The command as it is shown in the transcript. */
		public String raw() {
			return "/" + name + (args.isEmpty() ? "" : " " + args);
		}
	}

	/** A known command the connected agent does not support, with the reason. */
	public record UnavailableCommand(String name, String reason) {
	}

	/** What a command does. */
	public sealed interface Action {

		/** {@code /new} keeps the current session in the list; {@code /clear} (replace) forgets it. */
		record NewSession(boolean replace) implements Action {
		}

		record Cancel() implements Action {
		}

		record Restart() implements Action {
		}

		/** Sign in and reconnect. */
		record Login() implements Action {
		}

		record Help(List<CommandView> commands, List<UnavailableCommand> unavailable) implements Action {
		}

		/** Call an advertised Kiro extension method and show its result. */
		record Extension(String method, String label) implements Action {
		}

		record SetMode(String modeId) implements Action {
		}

		record SetConfig(String configId, String value) implements Action {
		}

		/** List the values of a config option, or of all options and the modes when {@code configId} is {@code null}. */
		record ShowOptions(String configId) implements Action {
		}

		/** Switch to the planner mode (when one exists, else {@code modeId} is {@code null}) and send the trailing text, if any. */
		record Plan(String text, String modeId) implements Action {
		}

		/** {@code /capabilities}: the caller lists what the agent advertised. */
		record Capabilities() implements Action {
		}

		/** The agent advertised this command, or nobody knows it: send the text through as a prompt. */
		record Forward() implements Action {
		}

		record Unavailable(String name, String reason) implements Action {
		}
	}

	/**
	 * The commands served by a Kiro extension method. The advertised methods
	 * are matched by pattern rather than by exact name, to tolerate variants
	 * and renames; the documented names match their own pattern.
	 */
	public enum ExtCommand {
		USAGE("usage", "_kiro/account/getUsage", "usage|quota|credit"),
		CONTEXT("context", "_kiro/session/context", "session/context|context$"),
		COMPACT("compact", "_kiro/session/compact", "compact|summari[sz]e");

		private final String label;
		private final String documented;
		private final Pattern pattern;

		ExtCommand(String label, String documented, String pattern) {
			this.label = label;
			this.documented = documented;
			this.pattern = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
		}

		String unavailableReason() {
			return "the agent did not advertise a " + label + " extension method (such as " + documented + ")";
		}
	}

	private static final Pattern COMMAND = Pattern.compile("^/([A-Za-z][A-Za-z0-9_-]*)" + JsText.WHITESPACE_CLASS + "*(.*)$",
			Pattern.DOTALL);
	private static final Pattern PLANNER = Pattern.compile("^plan(ner)?\\b", Pattern.CASE_INSENSITIVE);

	private Slash() {
	}

	/** The advertised extension method serving {@code command} (first match wins), or {@code null}. */
	public static String findExtension(List<String> extensionMethods, ExtCommand command) {
		for (String method : extensionMethods) {
			if (command.pattern.matcher(method).find()) {
				return method;
			}
		}
		return null;
	}

	/** @return {@code null} when the text is not a command */
	public static Parsed parse(String text) {
		Matcher m = COMMAND.matcher(JsText.trim(text == null ? "" : text));
		if (!m.matches()) {
			return null;
		}
		return new Parsed(lower(m.group(1)), JsText.trim(m.group(2)));
	}

	private static String lower(String s) {
		return s.toLowerCase(Locale.ROOT);
	}

	private static ModeView findMode(Context ctx, String needle) {
		if (ctx.modes() == null) {
			return null;
		}
		String n = lower(needle);
		for (ModeView mode : ctx.modes().availableModes()) {
			if (lower(mode.id()).equals(n) || lower(mode.name()).equals(n)) {
				return mode;
			}
		}
		return null;
	}

	/** The mode {@code /plan} switches to, or {@code null}. */
	public static ModeView plannerMode(Context ctx) {
		List<ModeView> modes = ctx.modes() == null ? List.of() : ctx.modes().availableModes();
		// Exact id first: a real Kiro workspace also had a custom agent named
		// "qc-sprint-planner", which must not win over the built-in "plan" mode.
		for (ModeView mode : modes) {
			if (lower(mode.id()).equals("plan")) {
				return mode;
			}
		}
		for (ModeView mode : modes) {
			if (lower(mode.name()).equals("plan")) {
				return mode;
			}
		}
		for (ModeView mode : modes) {
			if (PLANNER.matcher(mode.id()).find() || PLANNER.matcher(mode.name()).find()) {
				return mode;
			}
		}
		return null;
	}

	private static ConfigOptionView findOption(Context ctx, String idOrCategory) {
		for (ConfigOptionView option : ctx.configOptions()) {
			if (idOrCategory.equals(option.id())) {
				return option;
			}
		}
		for (ConfigOptionView option : ctx.configOptions()) {
			if (idOrCategory.equals(option.category())) {
				return option;
			}
		}
		return null;
	}

	private static String matchValue(ConfigOptionView option, String needle) {
		String n = lower(needle);
		if (option.options() != null) {
			for (ConfigOptionView.Choice choice : option.options()) {
				if (lower(choice.value()).equals(n) || lower(choice.name()).equals(n)) {
					return choice.value();
				}
			}
		}
		return null;
	}

	private static boolean hasAgentCommand(Context ctx, String name) {
		return ctx.agentCommands().stream().anyMatch(command -> lower(command.name()).equals(name));
	}

	private static Action configCommand(Context ctx, String name, String optionId, String args) {
		ConfigOptionView option = findOption(ctx, optionId);
		if (option == null) {
			if (hasAgentCommand(ctx, name)) {
				return new Action.Forward();
			}
			return new Action.Unavailable(name, "the agent did not advertise a \"" + optionId + "\" option");
		}
		if (args.isEmpty()) {
			return new Action.ShowOptions(option.id());
		}
		String value = matchValue(option, args);
		if (value == null) {
			return new Action.ShowOptions(option.id());
		}
		return new Action.SetConfig(option.id(), value);
	}

	private static Action extensionCommand(Context ctx, String name, ExtCommand command) {
		String method = findExtension(ctx.extensionMethods(), command);
		if (method != null) {
			return new Action.Extension(method, name);
		}
		if (hasAgentCommand(ctx, name)) {
			return new Action.Forward();
		}
		return new Action.Unavailable(name, command.unavailableReason());
	}

	/** Decide what a parsed slash command does for the current agent. */
	public static Action route(Parsed parsed, Context ctx) {
		String name = parsed.name();
		String args = parsed.args();
		switch (name) {
		case "clear":
			return new Action.NewSession(true);
		case "new":
			return new Action.NewSession(false);
		case "cancel":
		case "stop":
			return new Action.Cancel();
		case "restart":
			return new Action.Restart();
		case "login":
			return new Action.Login();
		case "help":
			return new Action.Help(builtinCommands(ctx), unavailableCommands(ctx));
		case "usage":
			return extensionCommand(ctx, name, ExtCommand.USAGE);
		case "compact":
			return extensionCommand(ctx, name, ExtCommand.COMPACT);
		case "context":
			return extensionCommand(ctx, name, ExtCommand.CONTEXT);
		case "capabilities":
		case "caps":
			return new Action.Capabilities();
		case "model":
			return configCommand(ctx, name, "model", args);
		case "effort":
			return configCommand(ctx, name, "effortLevel", args);
		case "mode":
		case "agent": {
			if (ctx.modes() != null) {
				if (args.isEmpty()) {
					return new Action.ShowOptions(null);
				}
				ModeView mode = findMode(ctx, args);
				return mode != null ? new Action.SetMode(mode.id()) : new Action.ShowOptions(null);
			}
			return configCommand(ctx, name, "mode", args);
		}
		case "plan": {
			ModeView mode = plannerMode(ctx);
			if (mode == null && hasAgentCommand(ctx, name)) {
				return new Action.Forward();
			}
			return new Action.Plan(args, mode != null ? mode.id() : null);
		}
		default:
			// Advertised or not, let the agent decide what an unknown command means
			// (custom prompts and skills are not always advertised).
			return new Action.Forward();
		}
	}

	/** Built-in commands to merge into the {@code /} popup, in display order. */
	public static List<CommandView> builtinCommands(Context ctx) {
		List<CommandView> list = new ArrayList<>();
		list.add(new CommandView("clear", "Start over: a fresh session replaces this one", null));
		list.add(new CommandView("new", "Start a new session alongside this one", null));
		list.add(new CommandView("help", "List available commands", null));
		list.add(new CommandView("capabilities", "Show what the connected agent advertises", null));
		if (ctx.modes() != null || findOption(ctx, "mode") != null) {
			list.add(new CommandView("mode", "Switch the agent mode", "mode id"));
		}
		if (plannerMode(ctx) != null) {
			list.add(new CommandView("plan", "Switch to planner mode", "what to plan"));
		}
		if (findOption(ctx, "model") != null) {
			list.add(new CommandView("model", "Change the model", "model id"));
		}
		if (findOption(ctx, "effortLevel") != null) {
			list.add(new CommandView("effort", "Change the effort level", "level"));
		}
		if (findExtension(ctx.extensionMethods(), ExtCommand.USAGE) != null) {
			list.add(new CommandView("usage", "Show account usage", null));
		}
		if (findExtension(ctx.extensionMethods(), ExtCommand.CONTEXT) != null) {
			list.add(new CommandView("context", "Show the session context", null));
		}
		if (findExtension(ctx.extensionMethods(), ExtCommand.COMPACT) != null) {
			list.add(new CommandView("compact", "Compact the conversation", null));
		}
		list.add(new CommandView("cancel", "Cancel the current turn", null));
		list.add(new CommandView("restart", "Restart the agent process", null));
		list.add(new CommandView("login", "Sign in to Kiro (runs kiro-cli login in a terminal)", null));
		return list;
	}

	/** Known commands this agent cannot serve (shown in /help so users know why). */
	public static List<UnavailableCommand> unavailableCommands(Context ctx) {
		List<UnavailableCommand> out = new ArrayList<>();
		for (ExtCommand command : List.of(ExtCommand.USAGE, ExtCommand.CONTEXT, ExtCommand.COMPACT)) {
			if (findExtension(ctx.extensionMethods(), command) == null && !hasAgentCommand(ctx, command.label)) {
				out.add(new UnavailableCommand(command.label, command.unavailableReason()));
			}
		}
		if (findOption(ctx, "effortLevel") == null && !hasAgentCommand(ctx, "effort")) {
			out.add(new UnavailableCommand("effort", "the agent did not advertise an effortLevel option"));
		}
		if (findOption(ctx, "model") == null && !hasAgentCommand(ctx, "model")) {
			out.add(new UnavailableCommand("model", "the agent did not advertise a model option"));
		}
		return out;
	}

	/** Agent-advertised commands first, then built-ins the agent did not already name. */
	public static List<CommandView> mergeCommands(List<CommandView> agentCommands, Context ctx) {
		Set<String> seen = new HashSet<>();
		for (CommandView command : agentCommands) {
			seen.add(lower(command.name()));
		}
		List<CommandView> merged = new ArrayList<>(agentCommands);
		for (CommandView command : builtinCommands(ctx)) {
			if (!seen.contains(command.name())) {
				merged.add(command);
			}
		}
		return merged;
	}

	/** The reply to {@code /help}. */
	public static String describeHelp(Action.Help help) {
		StringBuilder text = new StringBuilder("Available commands:\n");
		List<String> lines = new ArrayList<>();
		for (CommandView command : help.commands()) {
			lines.add("- `/" + command.name() + "`" + (command.hint() != null && !command.hint().isEmpty() ? " *" + command.hint() + "*" : "")
					+ " — " + command.description());
		}
		text.append(String.join("\n", lines));
		if (!help.unavailable().isEmpty()) {
			text.append("\n\nNot available with this agent:\n");
			text.append(String.join("\n", help.unavailable().stream().map(u -> "- `/" + u.name() + "` — " + u.reason()).toList()));
		}
		return text.toString();
	}

	/** Human-readable listing for {@link Action.ShowOptions}. */
	public static String describeOptions(Context ctx, String configId) {
		List<String> lines = new ArrayList<>();
		if (configId == null && ctx.modes() != null) {
			List<String> modes = new ArrayList<>();
			for (ModeView mode : ctx.modes().availableModes()) {
				modes.add(mode.id() + (!mode.id().equals(mode.name()) ? " (" + mode.name() + ")" : ""));
			}
			lines.add("Modes (current: " + ctx.modes().currentModeId() + "): " + String.join(", ", modes));
		}
		for (ConfigOptionView option : ctx.configOptions()) {
			if (configId != null && !configId.equals(option.id())) {
				continue;
			}
			if (option.type() == ConfigOptionView.Type.BOOLEAN) {
				lines.add(option.id() + ": " + valueText(option.currentValue()) + " (true/false)");
				continue;
			}
			List<String> values = new ArrayList<>();
			if (option.options() != null) {
				for (ConfigOptionView.Choice choice : option.options()) {
					values.add(choice.value() + (!choice.value().equals(choice.name()) ? " (" + choice.name() + ")" : ""));
				}
			}
			lines.add(option.id() + " (current: " + valueText(option.currentValue()) + "): " + String.join(", ", values));
		}
		return lines.isEmpty() ? "The agent did not advertise any options." : String.join("\n", lines);
	}

	/** A config option's value as text: {@code String(value)} of the reference. */
	public static String valueText(JsonNode value) {
		return value == null || value.isMissingNode() ? "undefined" : value.asText(value.toString());
	}

	/** Render an extension result as readable text (JSON pretty-printed unless it is a string). */
	public static String formatExtensionResult(JsonNode result) {
		if (result == null || result.isNull() || result.isMissingNode()) {
			return "(no result)";
		}
		if (result.isTextual()) {
			return result.textValue();
		}
		return "```json\n" + prettyJson(result) + "\n```";
	}

	/** {@code JSON.stringify(value, null, 2)}. */
	public static String prettyJson(JsonNode value) {
		StringBuilder out = new StringBuilder();
		pretty(value, "", out);
		return out.toString();
	}

	private static void pretty(JsonNode node, String indent, StringBuilder out) {
		String inner = indent + "  ";
		if (node.isObject()) {
			if (node.isEmpty()) {
				out.append("{}");
				return;
			}
			out.append("{\n");
			for (Iterator<Map.Entry<String, JsonNode>> fields = node.fields(); fields.hasNext();) {
				Map.Entry<String, JsonNode> field = fields.next();
				out.append(inner).append(JsText.jsonQuote(field.getKey())).append(": ");
				pretty(field.getValue(), inner, out);
				out.append(fields.hasNext() ? ",\n" : "\n");
			}
			out.append(indent).append('}');
		} else if (node.isArray()) {
			if (node.isEmpty()) {
				out.append("[]");
				return;
			}
			out.append("[\n");
			for (int i = 0; i < node.size(); i++) {
				out.append(inner);
				pretty(node.get(i), inner, out);
				out.append(i + 1 < node.size() ? ",\n" : "\n");
			}
			out.append(indent).append(']');
		} else if (node.isTextual()) {
			out.append(JsText.jsonQuote(node.textValue()));
		} else if (node.isFloatingPointNumber() && node.doubleValue() == Math.rint(node.doubleValue())
				&& Math.abs(node.doubleValue()) < 1e15) {
			// A whole number is written without a fraction, as JavaScript writes it.
			out.append(node.longValue());
		} else {
			out.append(node.toString());
		}
	}
}
