package io.github.liaoalia.kiroacp.core.session.commands;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.liaoalia.kiroacp.core.acp.api.AgentInfo;
import io.github.liaoalia.kiroacp.core.acp.api.KiroAgent;
import io.github.liaoalia.kiroacp.core.host.Subscription;
import io.github.liaoalia.kiroacp.core.host.UiScheduler;
import io.github.liaoalia.kiroacp.core.model.CommandView;
import io.github.liaoalia.kiroacp.core.model.ConfigOptionView;
import io.github.liaoalia.kiroacp.core.model.ModeView;
import io.github.liaoalia.kiroacp.core.model.ModesView;
import io.github.liaoalia.kiroacp.core.model.NoticeView.Severity;
import io.github.liaoalia.kiroacp.core.pure.slash.Slash;
import io.github.liaoalia.kiroacp.core.pure.slash.Slash.Action;
import io.github.liaoalia.kiroacp.core.pure.slash.Slash.Parsed;
import io.github.liaoalia.kiroacp.core.pure.transcript.TranscriptState;
import io.github.liaoalia.kiroacp.core.session.ChatController;
import io.github.liaoalia.kiroacp.core.session.ChatHooks;
import io.github.liaoalia.kiroacp.core.session.ChatHooks.PromptRequest;
import io.github.liaoalia.kiroacp.core.session.PageHandle;
import io.github.liaoalia.kiroacp.core.session.auth.SignIn;

/**
 * Slash commands, run on the client (the reference's {@code runSlash} and
 * its decision D18; the routing itself is {@link Slash}).
 *
 * <p>A command is a prompt that is dealt with here instead of being sent:
 * the feature is a prompt interceptor. Commands run at once, turn or no
 * turn: {@code /cancel} must stop the running turn and {@code /clear} must
 * be able to leave it. What the agent advertises itself, and what nobody
 * knows, goes out as a prompt. The {@code /} popup lists the agent's commands
 * followed by the ones served here.
 *
 * <p>Install it after whatever checks a prompt's images: the reference
 * refuses a message with a bad image before it looks for a command.
 */
public final class SlashCommands implements AutoCloseable {

	private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

	private final ChatController controller;
	private final UiScheduler scheduler;
	private final Supplier<String> remembered;
	private final SignIn signIn;
	private final List<Subscription> subscriptions = new ArrayList<>();
	private int localSeq;

	private SlashCommands(ChatController controller, Supplier<String> remembered, SignIn signIn) {
		this.signIn = signIn;
		this.controller = controller;
		this.scheduler = controller.host().scheduler();
		this.remembered = remembered;
	}

	/**
	 * @param remembered what {@code /capabilities} says is remembered for new sessions
	 *                   ({@code RememberedOptions.describe()}); {@code null} to leave the line out
	 * @param signIn     what {@code /login} runs; {@code null} when signing in is not installed
	 */
	public static SlashCommands install(ChatController controller, Supplier<String> remembered, SignIn signIn) {
		SlashCommands commands = new SlashCommands(controller, remembered, signIn);
		ChatHooks hooks = controller.hooks();
		commands.subscriptions.add(hooks.addPromptInterceptor(commands::intercept));
		hooks.setCommandCatalogue(SlashCommands::catalogue);
		commands.subscriptions.add(() -> hooks.setCommandCatalogue(null));
		return commands;
	}

	@Override
	public void close() {
		subscriptions.forEach(Subscription::close);
		subscriptions.clear();
	}

	/** What the routing needs to know of a session and its agent. */
	public static Slash.Context context(TranscriptState transcript, AgentInfo agent) {
		return new Slash.Context(agent != null ? agent.kiroExtensionMethods() : List.of(), transcript.modes(),
				transcript.configOptions(), transcript.commands());
	}

	private static List<CommandView> catalogue(TranscriptState transcript, AgentInfo agent) {
		return Slash.mergeCommands(transcript.commands(), context(transcript, agent));
	}

	// ---------------------------------------------------------------------
	// The interceptor
	// ---------------------------------------------------------------------

	private CompletionStage<Boolean> intercept(PromptRequest request) {
		Parsed parsed = Slash.parse(request.text());
		if (parsed == null) {
			return done(false);
		}
		String sessionId = request.sessionId();
		if (sessionId == null) {
			// `/login` and `/restart` exist for exactly this state (signed out, crashed):
			// they run without a session or a ready agent.
			if (parsed.name().equals("login")) {
				return login().thenApply(signedIn -> true);
			}
			if (parsed.name().equals("restart")) {
				return controller.restart().handle((restarted, error) -> true);
			}
			return done(false);
		}
		TranscriptState transcript = controller.transcript(sessionId);
		if (transcript == null) {
			return done(false);
		}
		Slash.Context context = context(transcript, controller.agentInfo());
		Action action = Slash.route(parsed, context);
		if (action instanceof Action.Forward) {
			return done(false);
		}
		// `/plan <text>` sends the images with its text; any other command runs here, so
		// attached images are not sent and a notice says so once the command ran (reference D29).
		boolean sendsImages = action instanceof Action.Plan plan && plan.modeId() != null && !plan.text().isEmpty();
		int images = request.images().size();
		return run(request, parsed, action, context).thenApply(handled -> {
			if (images > 0 && !sendsImages) {
				// Told after the command: `/clear`, `/new` and `/restart` change the session the page shows.
				String shown = request.page() != null ? request.page().sessionId() : null;
				String target = shown != null && controller.transcript(shown) != null ? shown
						: controller.transcript(sessionId) != null ? sessionId : null;
				controller.notice(target, Severity.INFO, (images == 1 ? "The image was" : "The " + images + " images were")
						+ " not sent: /" + parsed.name() + " runs in the plugin.");
			}
			return true;
		});
	}

	/** Runs a command that is served here; completes on the controller thread once it is through. */
	private CompletableFuture<Void> run(PromptRequest request, Parsed parsed, Action action, Slash.Context context) {
		String sessionId = request.sessionId();
		String raw = parsed.raw();
		KiroAgent agent = controller.agent();
		if (action instanceof Action.NewSession fresh) {
			// A new session in the page the command came from (else the focused page, else any)
			// when it displays this session. `/new` leaves the old session alone, running turn
			// included; `/clear` replaces it, through the controller's own way of closing a session.
			if (fresh.replace()) {
				return quiet(controller.replaceSession(sessionId, request.page()), "/clear");
			}
			PageHandle page = request.page() != null && sessionId.equals(request.page().sessionId()) ? request.page() : null;
			return quiet(controller.newSession(page), "/new");
		}
		if (action instanceof Action.Capabilities) {
			localExchange(sessionId, raw, describeCapabilities(sessionId));
		} else if (action instanceof Action.Cancel) {
			return quiet(controller.cancel(sessionId), "/" + parsed.name());
		} else if (action instanceof Action.Restart) {
			return quiet(controller.restart(), "/restart");
		} else if (action instanceof Action.Login) {
			return login();
		} else if (action instanceof Action.Help help) {
			localExchange(sessionId, raw, Slash.describeHelp(help));
		} else if (action instanceof Action.ShowOptions show) {
			localExchange(sessionId, raw, Slash.describeOptions(context, show.configId()));
		} else if (action instanceof Action.Unavailable unavailable) {
			localExchange(sessionId, raw, "`/" + unavailable.name() + "` is not available: " + unavailable.reason() + ".");
		} else if (agent == null) {
			// Whatever is left needs the agent.
			return done(null);
		} else if (action instanceof Action.SetMode mode) {
			return ui(agent.setMode(sessionId, mode.modeId())).handle((set, error) -> {
				if (error == null) {
					// Remembered for new sessions, as a choice made in the page is (reference D24).
					controller.optionChosen(sessionId, ChatHooks.Option.MODE, mode.modeId(), null);
					localExchange(sessionId, raw, "Mode set to `" + mode.modeId() + "`.");
				} else {
					localExchange(sessionId, raw, "Could not set the mode: " + message(error));
				}
				return null;
			});
		} else if (action instanceof Action.SetConfig config) {
			JsonNode value = JSON.textNode(config.value());
			return ui(agent.setConfigOption(sessionId, config.configId(), value)).handle((set, error) -> {
				if (error == null) {
					controller.optionChosen(sessionId, ChatHooks.Option.CONFIG, config.configId(), value);
					localExchange(sessionId, raw, "`" + config.configId() + "` set to `" + config.value() + "`.");
				} else {
					localExchange(sessionId, raw, "Could not change `" + config.configId() + "`: " + message(error));
				}
				return null;
			});
		} else if (action instanceof Action.Extension extension) {
			return ui(agent.callExtension(extension.method(), JSON.objectNode().put("sessionId", sessionId))).handle((result, error) -> {
				localExchange(sessionId, raw, error == null ? Slash.formatExtensionResult(result)
						: "`/" + extension.label() + "` failed: " + message(error));
				return null;
			});
		} else if (action instanceof Action.Plan plan) {
			return plan(request, raw, plan, agent);
		}
		return done(null);
	}

	/**
	 * {@code /plan [text]}: the planner mode, then the text as a prompt of its
	 * own, with the chips and images of the command. The switch is implied by
	 * the command, not a choice of mode: it is not remembered for new sessions.
	 */
	private CompletableFuture<Void> plan(PromptRequest request, String raw, Action.Plan plan, KiroAgent agent) {
		String sessionId = request.sessionId();
		if (plan.modeId() == null) {
			localExchange(sessionId, raw, "The agent did not advertise a planner mode. Ask it to plan in plain words instead.");
			return done(null);
		}
		TranscriptState transcript = controller.transcript(sessionId);
		ModesView modes = transcript != null ? transcript.modes() : null;
		CompletableFuture<Boolean> switched = modes != null && plan.modeId().equals(modes.currentModeId()) ? done(true)
				: ui(agent.setMode(sessionId, plan.modeId())).handle((set, error) -> {
					if (error != null) {
						localExchange(sessionId, raw, "Could not switch to planner mode: " + message(error));
					}
					return error == null;
				});
		return switched.thenCompose(ok -> {
			if (!ok) {
				return done(null);
			}
			if (plan.text().isEmpty()) {
				localExchange(sessionId, raw, "Planner mode `" + plan.modeId() + "` is active. Describe what to plan.");
				return done(null);
			}
			// Through the front door: its images are checked and, while a turn runs, it is
			// steered or queued as any other prompt typed then.
			return quiet(controller.prompt(plan.text(), request.chips(), request.images(), sessionId, null), "/plan");
		});
	}

	/** Sign in with the agent's own method and reconnect, as the page's button does. */
	private CompletableFuture<Void> login() {
		if (signIn == null) {
			controller.notice(null, Severity.WARNING, "Signing in is not available here.");
			return done(null);
		}
		return quiet(signIn.login(null), "/login");
	}

	/** Show the user's command and a local reply in the transcript. */
	private void localExchange(String sessionId, String userText, String replyText) {
		if (controller.echoUserMessage(sessionId, userText, List.of(), null) == null) {
			return;
		}
		ObjectNode reply = JSON.objectNode();
		reply.put("sessionUpdate", "agent_message_chunk");
		reply.put("messageId", "local-" + (++localSeq));
		reply.set("content", JSON.objectNode().put("type", "text").put("text", replyText));
		controller.applyLocalUpdate(sessionId, reply);
		// Close the local message so the agent's next chunk starts a new one.
		controller.closeOpenMessage(sessionId);
	}

	// ---------------------------------------------------------------------
	// /capabilities
	// ---------------------------------------------------------------------

	/** {@code /capabilities}: everything the agent advertised, raw enough to paste into a bug report. */
	String describeCapabilities(String sessionId) {
		AgentInfo info = controller.agentInfo();
		List<String> lines = new ArrayList<>();
		if (info == null) {
			lines.add("The agent is not connected; nothing has been advertised yet.");
		} else {
			JsonNode about = info.agentInfo();
			String name = "unknown agent";
			if (about != null && about.isObject()) {
				String title = about.path("title").isTextual() ? about.get("title").textValue() : about.path("name").asText("");
				name = (title + " " + about.path("version").asText("")).trim();
			}
			lines.add("**Agent:** " + name + " (protocol " + info.protocolVersion() + ")");
			JsonNode caps = info.agentCapabilities();
			JsonNode prompt = caps.get("promptCapabilities");
			String promptSummary = "none";
			if (prompt != null && prompt.isObject()) {
				List<String> parts = new ArrayList<>();
				for (Iterator<Map.Entry<String, JsonNode>> fields = prompt.fields(); fields.hasNext();) {
					Map.Entry<String, JsonNode> field = fields.next();
					parts.add(field.getKey() + "=" + field.getValue());
				}
				promptSummary = parts.isEmpty() ? "(empty)" : String.join(", ", parts);
			}
			List<String> authMethods = info.authMethods().stream().map(method -> method.path("id").asText("")).toList();
			lines.add("");
			lines.add("**Agent capabilities:**");
			lines.add("- loadSession: " + (caps.hasNonNull("loadSession") ? caps.get("loadSession").asText() : "false"));
			lines.add("- promptCapabilities: " + promptSummary);
			lines.add("- sessionCapabilities: " + keysOf(caps.get("sessionCapabilities")));
			lines.add("- mcpCapabilities: " + keysOf(caps.get("mcpCapabilities")));
			lines.add("- authMethods: " + (authMethods.isEmpty() ? "none" : String.join(", ", authMethods)));
			lines.add("");
			lines.add("**Extension methods (" + info.kiroExtensionMethods().size() + "):**");
			lines.add("```json");
			lines.add(Slash.prettyJson(JSON.arrayNode().addAll(info.kiroExtensionMethods().stream().map(m -> (JsonNode) JSON.textNode(m)).toList())));
			lines.add("```");
		}
		TranscriptState transcript = controller.transcript(sessionId);
		ModesView modes = transcript != null ? transcript.modes() : null;
		lines.add("");
		if (modes != null) {
			List<String> listed = new ArrayList<>();
			for (ModeView mode : modes.availableModes()) {
				listed.add("`" + mode.id() + "`" + (!mode.id().equals(mode.name()) ? " (" + mode.name() + ")" : ""));
			}
			lines.add("**Modes** (current: `" + modes.currentModeId() + "`): " + String.join(", ", listed));
		} else {
			lines.add("**Modes:** none advertised");
		}
		List<ConfigOptionView> options = transcript != null ? transcript.configOptions() : List.of();
		lines.add("");
		lines.add(options.isEmpty() ? "**Config options:** none advertised" : "**Config options:**");
		for (ConfigOptionView option : options) {
			String allowed;
			if (option.type() == ConfigOptionView.Type.BOOLEAN) {
				allowed = "true, false";
			} else {
				List<String> values = option.options() == null ? List.of()
						: option.options().stream().map(choice -> "`" + choice.value() + "`").toList();
				allowed = values.isEmpty() ? "(none)" : String.join(", ", values);
			}
			lines.add("- `" + option.id() + "`" + (option.category() != null && !option.category().isEmpty() ? " [" + option.category() + "]" : "")
					+ ": current `" + Slash.valueText(option.currentValue()) + "` — allowed: " + allowed);
		}
		List<CommandView> commands = transcript != null ? transcript.commands() : List.of();
		if (!commands.isEmpty()) {
			lines.add("");
			lines.add("**Agent commands:** " + String.join(", ", commands.stream().map(command -> "`/" + command.name() + "`").toList()));
		}
		if (remembered != null) {
			lines.add("");
			lines.add("Remembered for new sessions: " + remembered.get());
		}
		return String.join("\n", lines);
	}

	private static String keysOf(JsonNode value) {
		if (value == null || !(value.isObject() || value.isArray())) {
			return "none";
		}
		List<String> keys = new ArrayList<>();
		if (value.isObject()) {
			value.fieldNames().forEachRemaining(keys::add);
		} else {
			for (int i = 0; i < value.size(); i++) {
				keys.add(String.valueOf(i));
			}
		}
		return keys.isEmpty() ? "(empty)" : String.join(", ", keys);
	}

	// ---------------------------------------------------------------------
	// Threads
	// ---------------------------------------------------------------------

	private static <T> CompletableFuture<T> done(T value) {
		return CompletableFuture.completedFuture(value);
	}

	/** The same outcome on the controller thread: the agent answers on its reader thread. */
	private <T> CompletableFuture<T> ui(CompletionStage<T> source) {
		CompletableFuture<T> out = new CompletableFuture<>();
		source.whenComplete((value, error) -> scheduler.execute(() -> {
			if (error != null) {
				out.completeExceptionally(error);
			} else {
				out.complete(value);
			}
		}));
		return out;
	}

	/** A command is through when what it started is, however that went: the controller says what failed. */
	private CompletableFuture<Void> quiet(CompletableFuture<?> flow, String command) {
		return flow.handle((result, error) -> {
			if (error != null) {
				controller.host().logger().warn(command + " failed: " + message(error));
			}
			return null;
		});
	}

	private static String message(Throwable error) {
		Throwable cause = error;
		while (cause instanceof CompletionException && cause.getCause() != null) {
			cause = cause.getCause();
		}
		return cause.getMessage() != null && !cause.getMessage().isEmpty() ? cause.getMessage() : cause.toString();
	}
}
