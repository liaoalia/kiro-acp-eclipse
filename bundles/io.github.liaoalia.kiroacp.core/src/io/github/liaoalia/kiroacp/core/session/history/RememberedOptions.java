package io.github.liaoalia.kiroacp.core.session.history;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.liaoalia.kiroacp.core.acp.api.KiroAgent;
import io.github.liaoalia.kiroacp.core.acp.api.ModelState;
import io.github.liaoalia.kiroacp.core.acp.api.SessionState;
import io.github.liaoalia.kiroacp.core.host.Logger;
import io.github.liaoalia.kiroacp.core.model.ConfigOptionView;
import io.github.liaoalia.kiroacp.core.pure.transcript.Transcript;
import io.github.liaoalia.kiroacp.core.session.ChatController;
import io.github.liaoalia.kiroacp.core.session.ChatHooks;
import io.github.liaoalia.kiroacp.core.session.ChatHooks.Option;

/**
 * The mode, model and config options the user chose last, given to every new
 * session (reference D24).
 *
 * <p>A choice made in a page is remembered once the agent accepted it. A new
 * session gets the mode and the model as hints on {@code session/new} and,
 * once it exists, every remembered value the agent advertises for it and
 * that differs from what it started with. A restored session
 * ({@code session/load}) keeps its own. With the setting off nothing is
 * remembered or applied; what is stored stays until {@link #forget()}.
 */
public final class RememberedOptions {

	private static final ObjectMapper JSON = new ObjectMapper();

	/**
	 * What is remembered. Never empty: nothing remembered is {@code null}.
	 *
	 * @param modeId        {@code session/set_mode} id, or {@code null}
	 * @param modelId       CLI v2 {@code session/set_model} id, or {@code null}
	 * @param configOptions {@code session/set_config_option} values by option id: text or boolean nodes
	 */
	public record Remembered(String modeId, String modelId, Map<String, JsonNode> configOptions) {

		public Remembered {
			configOptions = Collections.unmodifiableMap(new LinkedHashMap<>(configOptions));
		}
	}

	private final BooleanSupplier enabled;
	private final OptionMemory memory;
	private final Logger logger;

	RememberedOptions(BooleanSupplier enabled, OptionMemory memory, Logger logger) {
		this.enabled = Objects.requireNonNull(enabled, "enabled");
		this.memory = Objects.requireNonNull(memory, "memory");
		this.logger = logger != null ? logger : Logger.NONE;
	}

	/** Remember what is chosen in the controller's pages and shape its new sessions, while the user's setting says so. */
	public static RememberedOptions install(ChatController controller, OptionMemory memory) {
		return install(controller, controller.host().settings()::rememberSessionOptions, memory, controller.host().logger());
	}

	/** @param enabled the setting, asked each time */
	public static RememberedOptions install(ChatController controller, BooleanSupplier enabled, OptionMemory memory, Logger logger) {
		RememberedOptions options = new RememberedOptions(enabled, memory, logger);
		controller.hooks().addListener(new ChatHooks.SessionListener() {
			@Override
			public void optionChosen(String sessionId, Option option, String id, JsonNode value) {
				options.remember(option, id, value);
			}
		});
		controller.hooks().setNewSessions(new ChatHooks.NewSessions() {
			@Override
			public JsonNode meta() {
				return options.hints();
			}

			@Override
			public CompletionStage<Void> opened(KiroAgent agent, SessionState session) {
				Remembered remembered = options.enabled.getAsBoolean() ? options.remembered() : null;
				return remembered == null ? CompletableFuture.completedFuture(null) : options.apply(agent, session, remembered);
			}
		});
		return options;
	}

	/** The options remembered for new sessions, or {@code null} when nothing is remembered. */
	public Remembered remembered() {
		String text = memory.read();
		if (text == null || text.isEmpty()) {
			return null;
		}
		JsonNode raw;
		try {
			raw = JSON.readTree(text);
		} catch (JsonProcessingException e) {
			return null;
		}
		if (raw == null || !raw.isObject()) {
			return null;
		}
		String modeId = nonEmptyText(raw.get("modeId"));
		String modelId = nonEmptyText(raw.get("modelId"));
		Map<String, JsonNode> configOptions = new LinkedHashMap<>();
		JsonNode stored = raw.get("configOptions");
		if (stored != null && stored.isObject()) {
			for (Iterator<Map.Entry<String, JsonNode>> fields = stored.fields(); fields.hasNext();) {
				Map.Entry<String, JsonNode> field = fields.next();
				if (field.getValue().isTextual() || field.getValue().isBoolean()) {
					configOptions.put(field.getKey(), field.getValue());
				}
			}
		}
		return modeId != null || modelId != null || !configOptions.isEmpty() ? new Remembered(modeId, modelId, configOptions) : null;
	}

	/** Forget the remembered options; the next session starts with the agent's defaults. */
	public void forget() {
		forget(memory, logger);
	}

	/** {@link #forget()} for whoever has the memory but no chat: the command works before a chat was opened. */
	public static void forget(OptionMemory memory, Logger logger) {
		memory.write(null);
		(logger != null ? logger : Logger.NONE).info("remembered session options forgotten");
	}

	/** One line for {@code /capabilities}: what the next session gets. */
	public String describe() {
		Remembered remembered = remembered();
		boolean on = enabled.getAsBoolean();
		if (remembered == null) {
			return on ? "none" : "none (\"Remember session options\" is off)";
		}
		List<String> parts = new ArrayList<>();
		if (remembered.modeId() != null) {
			parts.add("mode `" + remembered.modeId() + "`");
		}
		if (remembered.modelId() != null) {
			parts.add("model `" + remembered.modelId() + "`");
		}
		remembered.configOptions().forEach((id, value) -> parts.add("`" + id + "` = `" + value.asText() + "`"));
		return String.join(", ", parts) + (on ? "" : " (not applied: \"Remember session options\" is off)");
	}

	/** Merge a choice of the user into what is remembered; nothing happens while the setting is off. */
	void remember(Option option, String id, JsonNode value) {
		if (!enabled.getAsBoolean() || option == null || id == null) {
			return;
		}
		if (option == Option.CONFIG && (value == null || !(value.isTextual() || value.isBoolean()))) {
			return;
		}
		try {
			Remembered current = remembered();
			ObjectNode next = JsonNodeFactory.instance.objectNode();
			String modeId = option == Option.MODE ? id : current != null ? current.modeId() : null;
			String modelId = option == Option.MODEL ? id : current != null ? current.modelId() : null;
			if (modeId != null) {
				next.put("modeId", modeId);
			}
			if (modelId != null) {
				next.put("modelId", modelId);
			}
			ObjectNode configOptions = next.putObject("configOptions");
			if (current != null) {
				current.configOptions().forEach(configOptions::set);
			}
			if (option == Option.CONFIG) {
				configOptions.set(id, value);
			}
			memory.write(next.toString());
		} catch (RuntimeException e) {
			logger.warn("could not remember session options: " + message(e));
		}
	}

	/** {@code _meta} of {@code session/new}: Kiro V3 reads the mode and the model there; other agents ignore it. */
	JsonNode hints() {
		Remembered remembered = enabled.getAsBoolean() ? remembered() : null;
		if (remembered == null) {
			return null;
		}
		ObjectNode kiro = JsonNodeFactory.instance.objectNode();
		if (remembered.modeId() != null) {
			kiro.put("modeId", remembered.modeId());
		}
		String modelId = remembered.modelId();
		if (modelId == null) {
			JsonNode model = remembered.configOptions().get("model");
			modelId = model != null && model.isTextual() ? model.textValue() : null;
		}
		if (modelId != null && !modelId.isEmpty()) {
			kiro.put("modelId", modelId);
		}
		return kiro.isEmpty() ? null : JsonNodeFactory.instance.objectNode().set("kiro", kiro);
	}

	/**
	 * Give a session that was just created what is remembered, each value
	 * only when the agent advertises it and the session does not have it
	 * already: the config options, then the mode, then the v2 model. Nothing
	 * the agent does not advertise is ever sent. Never fails.
	 */
	CompletableFuture<Void> apply(KiroAgent agent, SessionState session, Remembered remembered) {
		String sessionId = session.sessionId();
		List<String> applied = Collections.synchronizedList(new ArrayList<>());
		List<String> skipped = new ArrayList<>();
		CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);

		for (Map.Entry<String, JsonNode> entry : remembered.configOptions().entrySet()) {
			String id = entry.getKey();
			JsonNode value = entry.getValue();
			JsonNode option = configOption(session, id);
			if (option == null) {
				skipped.add(id + " (not advertised)");
				continue;
			}
			boolean allowed = "boolean".equals(option.path("type").asText()) ? value.isBoolean()
					: value.isTextual() && selectValues(option).contains(value.textValue());
			if (!allowed) {
				skipped.add(id + "=" + value.asText() + " (not an advertised value)");
				continue;
			}
			if (value.equals(option.get("currentValue"))) {
				continue;
			}
			chain = then(chain, () -> agent.setConfigOption(sessionId, id, value), id + "=" + value.asText(), id + "=" + value.asText(),
					sessionId, applied);
		}
		String modeId = remembered.modeId();
		if (modeId != null) {
			JsonNode modes = session.modes();
			if (modes == null || !advertised(modes.path("availableModes"), "id", modeId)) {
				skipped.add("mode " + modeId + " (not advertised)");
			} else if (!modeId.equals(modes.path("currentModeId").asText(null))) {
				chain = then(chain, () -> agent.setMode(sessionId, modeId), "mode=" + modeId, "mode " + modeId, sessionId, applied);
			}
		}
		String modelId = remembered.modelId();
		if (modelId != null) {
			ModelState models = session.models();
			if (models == null || models.availableModels().stream().noneMatch(model -> modelId.equals(model.modelId()))) {
				skipped.add("model " + modelId + " (not advertised)");
			} else if (!modelId.equals(models.currentModelId())) {
				chain = then(chain, () -> agent.setModel(sessionId, modelId), "model=" + modelId, "model " + modelId, sessionId, applied);
			}
		}
		return chain.thenRun(() -> {
			if (!applied.isEmpty() || !skipped.isEmpty()) {
				logger.info("session " + sessionId + ": remembered options applied: " + (applied.isEmpty() ? "none" : String.join(", ", applied))
						+ (skipped.isEmpty() ? "" : "; skipped: " + String.join(", ", skipped)));
			}
		});
	}

	/** One request after the other; one that fails is logged and the rest still goes out. */
	private CompletableFuture<Void> then(CompletableFuture<Void> chain, Supplier<CompletableFuture<?>> request, String done,
			String what, String sessionId, List<String> applied) {
		return chain.thenCompose(previous -> {
			CompletableFuture<?> sent;
			try {
				sent = request.get();
			} catch (RuntimeException e) {
				sent = CompletableFuture.failedFuture(e);
			}
			return sent.handle((result, error) -> {
				if (error == null) {
					applied.add(done);
				} else {
					logger.warn("session " + sessionId + ": could not apply remembered " + what + ": " + message(error));
				}
				return (Void) null;
			});
		});
	}

	private static JsonNode configOption(SessionState session, String id) {
		if (session.configOptions() != null) {
			for (JsonNode option : session.configOptions()) {
				if (id.equals(option.path("id").asText(null))) {
					return option;
				}
			}
		}
		return null;
	}

	/** The values of a select option, grouped or not; none for any other type. */
	private static List<String> selectValues(JsonNode option) {
		if (!"select".equals(option.path("type").asText())) {
			return List.of();
		}
		return Transcript.flattenSelectOptions(option.get("options")).stream().map(ConfigOptionView.Choice::value).toList();
	}

	private static boolean advertised(JsonNode list, String key, String id) {
		for (JsonNode entry : list) {
			if (id.equals(entry.path(key).asText(null))) {
				return true;
			}
		}
		return false;
	}

	private static String nonEmptyText(JsonNode node) {
		return node != null && node.isTextual() && !node.textValue().isEmpty() ? node.textValue() : null;
	}

	private static String message(Throwable error) {
		Throwable cause = error;
		while (cause instanceof CompletionException && cause.getCause() != null) {
			cause = cause.getCause();
		}
		return cause.getMessage() != null && !cause.getMessage().isEmpty() ? cause.getMessage() : cause.toString();
	}
}
