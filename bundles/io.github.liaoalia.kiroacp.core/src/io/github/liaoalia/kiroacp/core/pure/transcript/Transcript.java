package io.github.liaoalia.kiroacp.core.pure.transcript;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.TextNode;

import io.github.liaoalia.kiroacp.core.acp.api.ModelState;
import io.github.liaoalia.kiroacp.core.acp.api.SessionState;
import io.github.liaoalia.kiroacp.core.model.CommandView;
import io.github.liaoalia.kiroacp.core.model.ConfigOptionView;
import io.github.liaoalia.kiroacp.core.model.DeliveryState;
import io.github.liaoalia.kiroacp.core.model.MessageRole;
import io.github.liaoalia.kiroacp.core.model.MessageSegment;
import io.github.liaoalia.kiroacp.core.model.MessageView;
import io.github.liaoalia.kiroacp.core.model.ModeView;
import io.github.liaoalia.kiroacp.core.model.ModelView;
import io.github.liaoalia.kiroacp.core.model.ModesView;
import io.github.liaoalia.kiroacp.core.model.PlanEntryView;
import io.github.liaoalia.kiroacp.core.model.ToWebview;
import io.github.liaoalia.kiroacp.core.model.ToolCallView;
import io.github.liaoalia.kiroacp.core.model.ToolContentView;
import io.github.liaoalia.kiroacp.core.model.ToolKind;
import io.github.liaoalia.kiroacp.core.model.ToolLocation;
import io.github.liaoalia.kiroacp.core.model.ToolStatus;
import io.github.liaoalia.kiroacp.core.model.TranscriptItem;
import io.github.liaoalia.kiroacp.core.model.UiState;

/**
 * Pure transcript reducer: turns ACP {@code SessionUpdate}s into the chat
 * page's model. A port of the reference's {@code src/features/pure/transcript.ts};
 * deterministic, no I/O.
 *
 * <p>{@link #applyUpdate} never changes its input state; it returns a new state
 * plus the incremental {@link ToWebview} messages the host should forward.
 * Updates are untyped JSON from the agent, so nothing here trusts a field to
 * be present or of the right type.
 */
public final class Transcript {

	/**
	 * Largest base64 payload a transcript image segment shows inline
	 * ({@code MAX_INLINE_PREVIEW_CHARS} in the reference's images.ts); larger
	 * images stay a named chip so the transcript does not hold megabytes.
	 */
	static final int MAX_INLINE_PREVIEW_CHARS = 512 * 1024;

	/** The only types the page may put on an {@code <img>}: no SVG, which can carry script. */
	private static final Set<String> SAFE_IMAGE_TYPES = Set.of("image/png", "image/jpeg", "image/gif", "image/webp");

	/** Two or more characters before the colon: {@code C:\...} is a path, not a URL. */
	private static final Pattern URL_SCHEME = Pattern.compile("^[a-z][a-z0-9+.-]+:", Pattern.CASE_INSENSITIVE);

	private static final Map<String, ToolKind> TOOL_KINDS = byWireName(ToolKind.values());
	private static final Map<String, ToolStatus> TOOL_STATUSES = byWireName(ToolStatus.values());
	private static final Map<String, PlanEntryView.Priority> PLAN_PRIORITIES = byWireName(PlanEntryView.Priority.values());
	private static final Map<String, PlanEntryView.Status> PLAN_STATUSES = byWireName(PlanEntryView.Status.values());

	private Transcript() {
	}

	public static TranscriptState createTranscript() {
		return new TranscriptState(List.of(), Map.of(), List.of(), null, List.of(), List.of(), null, 1);
	}

	// ---------------------------------------------------------------------
	// Converters (also used by the host for session/new snapshots)
	// ---------------------------------------------------------------------

	/** Anything the agent sends beyond the ACP kinds, or no kind at all, is {@link ToolKind#OTHER}. */
	public static ToolKind toToolKind(JsonNode kind) {
		ToolKind known = isString(kind) ? TOOL_KINDS.get(kind.textValue()) : null;
		return known != null ? known : ToolKind.OTHER;
	}

	public static ToolStatus toToolStatus(JsonNode status) {
		return toToolStatus(status, ToolStatus.PENDING);
	}

	public static ToolStatus toToolStatus(JsonNode status, ToolStatus fallback) {
		ToolStatus known = isString(status) ? TOOL_STATUSES.get(status.textValue()) : null;
		return known != null ? known : fallback;
	}

	/**
	 * Last path segment of a {@code file:} URL, another URL or a plain path
	 * (either slash), percent-decoded for URLs; empty when there is none.
	 */
	public static Optional<String> fileNameOfUri(String uri) {
		boolean isUrl = URL_SCHEME.matcher(uri).find();
		String pathPart = uri;
		if (isUrl) {
			int cut = firstOf(uri, '?', '#');
			if (cut >= 0) {
				pathPart = uri.substring(0, cut);
			}
		}
		String last = pathPart.substring(Math.max(pathPart.lastIndexOf('/'), pathPart.lastIndexOf('\\')) + 1);
		if (isUrl) {
			try {
				last = percentDecode(last);
			} catch (IllegalArgumentException e) {
				// Not a valid encoding: the raw segment is still the best name there is.
			}
		}
		return last.isEmpty() ? Optional.empty() : Optional.of(last);
	}

	/**
	 * Map a content block to a message segment. An image gets a {@code name}
	 * from its {@code uri}, and a {@code src} (a data URL to show) only for
	 * data small enough to keep in the transcript and of a type that is safe
	 * on an {@code <img>} (D29).
	 */
	public static MessageSegment toMessageSegment(JsonNode block) {
		String type = text(block, "type");
		if (type == null) {
			return new MessageSegment.Text("");
		}
		switch (type) {
		case "text":
			return new MessageSegment.Text(textOr(block, "text", ""));
		case "image":
			return toImageSegment(block);
		case "audio":
			return new MessageSegment.Text("[audio]");
		case "resource_link": {
			String uri = textOr(block, "uri", "");
			String label = text(block, "title");
			if (label == null) {
				label = textOr(block, "name", uri);
			}
			return new MessageSegment.Resource(uri, label);
		}
		case "resource": {
			String uri = textOr(block.get("resource"), "uri", "");
			return new MessageSegment.Resource(uri, uri);
		}
		default:
			return new MessageSegment.Text("");
		}
	}

	private static MessageSegment.Image toImageSegment(JsonNode block) {
		String mimeType = textOr(block, "mimeType", "");
		String uri = text(block, "uri");
		String name = uri == null ? null : fileNameOfUri(uri).orElse(null);
		String data = textOr(block, "data", "");
		String src = null;
		if (!data.isEmpty() && data.length() <= MAX_INLINE_PREVIEW_CHARS) {
			String shownType = mimeType.toLowerCase(Locale.ROOT);
			if (SAFE_IMAGE_TYPES.contains(shownType) && isBase64(data)) {
				src = "data:" + shownType + ";base64," + data;
			}
		}
		return new MessageSegment.Image(mimeType, name, src);
	}

	/** Map a content block that appears inside tool-call content to text. */
	public static String contentBlockToText(JsonNode block) {
		String type = text(block, "type");
		if (type == null) {
			return "";
		}
		switch (type) {
		case "text":
			return textOr(block, "text", "");
		case "image":
			return "[image]";
		case "audio":
			return "[audio]";
		case "resource_link":
			return "[resource " + textOr(block, "uri", "") + "]";
		case "resource": {
			JsonNode resource = block.get("resource");
			String embedded = text(resource, "text");
			return embedded != null ? embedded : "[resource " + textOr(resource, "uri", "") + "]";
		}
		default:
			return "";
		}
	}

	/** @param content ACP {@code ToolCallContent[]}; anything else gives an empty list */
	public static List<ToolContentView> toToolContent(JsonNode content) {
		List<ToolContentView> out = new ArrayList<>();
		for (JsonNode item : elements(content)) {
			String type = text(item, "type");
			if (type == null) {
				continue;
			}
			switch (type) {
			case "content":
				out.add(new ToolContentView.Text(contentBlockToText(item.get("content"))));
				break;
			case "diff":
				out.add(new ToolContentView.Diff(textOr(item, "path", ""), text(item, "oldText"), textOr(item, "newText", "")));
				break;
			case "terminal":
				// The reducer only knows the id; the host fills in output and exit code from its terminals.
				out.add(new ToolContentView.Terminal(textOr(item, "terminalId", ""), "", null));
				break;
			default:
				break;
			}
		}
		return List.copyOf(out);
	}

	public static List<ToolLocation> toLocations(JsonNode locations) {
		List<ToolLocation> out = new ArrayList<>();
		for (JsonNode location : elements(locations)) {
			String path = text(location, "path");
			if (path == null) {
				continue;
			}
			JsonNode line = location.get("line");
			out.add(new ToolLocation(path, line != null && line.isIntegralNumber() && line.canConvertToInt() ? line.intValue() : null));
		}
		return List.copyOf(out);
	}

	/** @param meta the {@code _meta} of a tool call or of an update to one */
	public static Optional<String> readFailureReason(JsonNode meta) {
		return Optional.ofNullable(text(isRecord(meta) ? meta.get("kiro") : null, "failureReason"));
	}

	/** @param call an ACP {@code ToolCall} (a {@code tool_call} update, or the one of a permission request) */
	public static ToolCallView toToolCallView(JsonNode call) {
		String toolCallId = textOr(call, "toolCallId", "");
		JsonNode object = isRecord(call) ? call : null;
		return new ToolCallView(toolCallId, textOr(call, "title", toolCallId), toToolKind(field(object, "kind")),
				toToolStatus(field(object, "status")), toToolContent(field(object, "content")),
				toLocations(field(object, "locations")), field(object, "rawInput"), field(object, "rawOutput"), null,
				readFailureReason(field(object, "_meta")).filter(reason -> !reason.isEmpty()).orElse(null));
	}

	/** Only what the update carries replaces what the card has; a {@code null} field means "unchanged". */
	public static ToolCallView mergeToolCallUpdate(ToolCallView existing, JsonNode update) {
		JsonNode object = isRecord(update) ? update : null;
		JsonNode kind = field(object, "kind");
		JsonNode status = field(object, "status");
		String title = text(object, "title");
		JsonNode content = field(object, "content");
		JsonNode locations = field(object, "locations");
		JsonNode rawInput = field(object, "rawInput");
		JsonNode rawOutput = field(object, "rawOutput");
		String failureReason = readFailureReason(field(object, "_meta")).filter(reason -> !reason.isEmpty()).orElse(null);
		return new ToolCallView(existing.toolCallId(), title != null ? title : existing.title(),
				kind != null ? toToolKind(kind) : existing.kind(),
				status != null ? toToolStatus(status, existing.status()) : existing.status(),
				content != null ? toToolContent(content) : existing.content(),
				locations != null ? toLocations(locations) : existing.locations(),
				rawInput != null ? rawInput : existing.rawInput(), rawOutput != null ? rawOutput : existing.rawOutput(),
				existing.pendingPermissionId(), failureReason != null ? failureReason : existing.failureReason());
	}

	public static List<PlanEntryView> toPlanEntries(JsonNode entries) {
		List<PlanEntryView> out = new ArrayList<>();
		for (JsonNode entry : elements(entries)) {
			String content = text(entry, "content");
			if (content == null) {
				continue;
			}
			PlanEntryView.Priority priority = lookup(PLAN_PRIORITIES, text(entry, "priority"));
			PlanEntryView.Status status = lookup(PLAN_STATUSES, text(entry, "status"));
			out.add(new PlanEntryView(content, priority != null ? priority : PlanEntryView.Priority.MEDIUM,
					status != null ? status : PlanEntryView.Status.PENDING));
		}
		return List.copyOf(out);
	}

	/** @return {@code null} when {@code modes} is not an ACP {@code SessionModeState} */
	public static ModesView toModesView(JsonNode modes) {
		String currentModeId = text(modes, "currentModeId");
		if (currentModeId == null) {
			return null;
		}
		List<ModeView> available = new ArrayList<>();
		for (JsonNode mode : elements(modes.get("availableModes"))) {
			String id = text(mode, "id");
			if (id != null) {
				available.add(new ModeView(id, textOr(mode, "name", id), text(mode, "description")));
			}
		}
		return new ModesView(currentModeId, List.copyOf(available));
	}

	/** Flatten select options (grouped or not) into a single list with an optional {@code group} label. */
	public static List<ConfigOptionView.Choice> flattenSelectOptions(JsonNode options) {
		List<ConfigOptionView.Choice> out = new ArrayList<>();
		for (JsonNode entry : elements(options)) {
			if (!isRecord(entry)) {
				continue;
			}
			JsonNode grouped = entry.get("options");
			if (grouped != null && grouped.isArray()) {
				String group = text(entry, "name");
				if (group == null) {
					group = text(entry, "group");
				}
				for (JsonNode option : grouped) {
					String value = text(option, "value");
					if (value != null) {
						out.add(new ConfigOptionView.Choice(value, textOr(option, "name", value), text(option, "description"), group));
					}
				}
			} else {
				String value = text(entry, "value");
				if (value != null) {
					out.add(new ConfigOptionView.Choice(value, textOr(entry, "name", value), text(entry, "description"), null));
				}
			}
		}
		return List.copyOf(out);
	}

	/** @param options ACP {@code SessionConfigOption[]}; anything else gives an empty list */
	public static List<ConfigOptionView> toConfigOptionViews(JsonNode options) {
		return configOptionViews(elements(options));
	}

	/** @param options as in {@link SessionState#configOptions()}; {@code null} gives an empty list */
	public static List<ConfigOptionView> toConfigOptionViews(List<JsonNode> options) {
		return configOptionViews(options == null ? List.of() : options);
	}

	private static List<ConfigOptionView> configOptionViews(Iterable<JsonNode> options) {
		List<ConfigOptionView> out = new ArrayList<>();
		for (JsonNode option : options) {
			String id = text(option, "id");
			if (id == null) {
				continue;
			}
			String name = textOr(option, "name", id);
			String description = text(option, "description");
			String category = text(option, "category");
			String type = text(option, "type");
			JsonNode currentValue = option.get("currentValue");
			if ("boolean".equals(type)) {
				boolean on = currentValue != null && currentValue.isBoolean() && currentValue.booleanValue();
				out.add(new ConfigOptionView(id, name, description, category, ConfigOptionView.Type.BOOLEAN,
						BooleanNode.valueOf(on), null));
			} else if ("select".equals(type)) {
				out.add(new ConfigOptionView(id, name, description, category, ConfigOptionView.Type.SELECT,
						TextNode.valueOf(isString(currentValue) ? currentValue.textValue() : ""),
						flattenSelectOptions(option.get("options"))));
			}
		}
		return List.copyOf(out);
	}

	/** @param commands ACP {@code AvailableCommand[]}; anything else gives an empty list */
	public static List<CommandView> toCommandViews(JsonNode commands) {
		return commandViews(elements(commands));
	}

	/** @param commands as in {@link SessionState#availableCommands()}; {@code null} gives an empty list */
	public static List<CommandView> toCommandViews(List<JsonNode> commands) {
		return commandViews(commands == null ? List.of() : commands);
	}

	private static List<CommandView> commandViews(Iterable<JsonNode> commands) {
		List<CommandView> out = new ArrayList<>();
		for (JsonNode command : commands) {
			String name = text(command, "name");
			if (name != null) {
				out.add(new CommandView(name, textOr(command, "description", ""), text(command.get("input"), "hint")));
			}
		}
		return List.copyOf(out);
	}

	/** @return {@code null} when the session advertises no models */
	public static ModelView toModelView(ModelState models) {
		if (models == null) {
			return null;
		}
		List<ModelView.Model> available = new ArrayList<>();
		for (ModelState.Model model : models.availableModels()) {
			available.add(new ModelView.Model(model.modelId(), model.name(), model.description()));
		}
		return new ModelView(models.currentModelId(), List.copyOf(available));
	}

	// ---------------------------------------------------------------------
	// Reducer
	// ---------------------------------------------------------------------

	/**
	 * Apply one {@code session/update}. Never throws on agent data: an update
	 * that is malformed, of an unknown kind or of a kind the transcript does
	 * not show leaves the state as it is and says why in {@link ApplyResult#ignored()}.
	 */
	public static ApplyResult applyUpdate(TranscriptState state, JsonNode update) {
		String kind = text(update, "sessionUpdate");
		if (kind == null) {
			return ApplyResult.ignored(state, "update without a sessionUpdate kind");
		}
		switch (kind) {
		case "user_message_chunk":
			return applyChunk(state, MessageRole.USER, update);
		case "agent_message_chunk":
			return applyChunk(state, MessageRole.AGENT, update);
		case "agent_thought_chunk":
			return applyChunk(state, MessageRole.THOUGHT, update);

		case "tool_call": {
			if (text(update, "toolCallId") == null) {
				return ApplyResult.ignored(state, "tool_call without a toolCallId");
			}
			return upsertToolCall(state, toToolCallView(update), true);
		}

		case "tool_call_update": {
			String toolCallId = text(update, "toolCallId");
			if (toolCallId == null) {
				return ApplyResult.ignored(state, "tool_call_update without a toolCallId");
			}
			ToolCallView existing = state.toolCalls().get(toolCallId);
			if (existing != null) {
				return upsertToolCall(state, mergeToolCallUpdate(existing, update), false);
			}
			// Update for a call we never saw: synthesize a card so nothing is lost.
			ToolCallView placeholder = new ToolCallView(toolCallId, toolCallId, ToolKind.OTHER, ToolStatus.PENDING, List.of(),
					List.of(), null, null, null, null);
			return upsertToolCall(state, mergeToolCallUpdate(placeholder, update), true);
		}

		case "plan": {
			List<PlanEntryView> plan = toPlanEntries(update.get("entries"));
			return ApplyResult.applied(state.withPlan(plan), new ToWebview.Plan(plan));
		}

		case "current_mode_update": {
			String currentModeId = text(update, "currentModeId");
			if (currentModeId == null) {
				return ApplyResult.ignored(state, "current_mode_update without a currentModeId");
			}
			ModesView modes = new ModesView(currentModeId, state.modes() != null ? state.modes().availableModes() : List.of());
			return ApplyResult.applied(state.withModes(modes), new ToWebview.Modes(modes));
		}

		case "config_option_update": {
			List<ConfigOptionView> options = toConfigOptionViews(update.get("configOptions"));
			return ApplyResult.applied(state.withConfigOptions(options), new ToWebview.ConfigOptions(options));
		}

		case "available_commands_update": {
			List<CommandView> commands = toCommandViews(update.get("availableCommands"));
			return ApplyResult.applied(state.withCommands(commands), new ToWebview.Commands(commands));
		}

		default:
			return ApplyResult.ignored(state, "not a transcript update: " + kind);
		}
	}

	/** Apply several updates in order, concatenating the emits. */
	public static ApplyResult applyUpdates(TranscriptState state, Iterable<? extends JsonNode> updates) {
		TranscriptState current = state;
		List<ToWebview> emits = new ArrayList<>();
		String ignored = null;
		for (JsonNode update : updates) {
			ApplyResult result = applyUpdate(current, update);
			current = result.state();
			emits.addAll(result.emits());
			if (result.ignored() != null) {
				ignored = result.ignored();
			}
		}
		return new ApplyResult(current, emits, ignored);
	}

	private static ApplyResult applyChunk(TranscriptState state, MessageRole role, JsonNode chunk) {
		JsonNode content = chunk.get("content");
		if (text(content, "type") == null) {
			return ApplyResult.ignored(state, "message chunk without a typed content block");
		}
		MessageSegment segment = toMessageSegment(content);
		String messageId = text(chunk, "messageId");
		OpenMessage open = state.openMessage();
		// A chunk without a messageId continues whatever message is open for its role, and so does
		// the first chunk that names one; only two different ids are known to be two messages.
		boolean canAppend = open != null && open.role() == role
				&& (messageId == null || open.messageId() == null || open.messageId().equals(messageId));

		if (canAppend) {
			List<TranscriptItem> items = new ArrayList<>(state.items().size());
			for (TranscriptItem item : state.items()) {
				if (item instanceof TranscriptItem.Message m && m.message().id().equals(open.id())) {
					MessageView message = m.message();
					items.add(new TranscriptItem.Message(new MessageView(message.id(), message.role(),
							appendSegment(message.segments(), segment), message.delivery())));
				} else {
					items.add(item);
				}
			}
			OpenMessage stillOpen = open.messageId() == null && messageId != null
					? new OpenMessage(open.id(), open.role(), messageId)
					: open;
			return ApplyResult.applied(state.withItems(items).withOpenMessage(stillOpen),
					new ToWebview.Chunk(open.id(), role, segment));
		}

		String id = "msg-" + state.nextMessageSeq();
		List<TranscriptItem> items = new ArrayList<>(state.items());
		items.add(new TranscriptItem.Message(new MessageView(id, role, List.of(segment), null)));
		TranscriptState next = new TranscriptState(items, state.toolCalls(), state.plan(), state.modes(), state.configOptions(),
				state.commands(), new OpenMessage(id, role, messageId), state.nextMessageSeq() + 1);
		return ApplyResult.applied(next, new ToWebview.Chunk(id, role, segment));
	}

	/** Consecutive text runs into one segment, so a streamed answer is one piece of markdown. */
	private static List<MessageSegment> appendSegment(List<MessageSegment> segments, MessageSegment segment) {
		List<MessageSegment> out = new ArrayList<>(segments);
		if (segment instanceof MessageSegment.Text added && !out.isEmpty()
				&& out.get(out.size() - 1) instanceof MessageSegment.Text last) {
			out.set(out.size() - 1, new MessageSegment.Text(last.text() + added.text()));
		} else {
			out.add(segment);
		}
		return List.copyOf(out);
	}

	private static ApplyResult upsertToolCall(TranscriptState state, ToolCallView view, boolean closeMessage) {
		List<TranscriptItem> items = state.items();
		if (!state.toolCalls().containsKey(view.toolCallId())) {
			items = new ArrayList<>(items);
			items.add(new TranscriptItem.Tool(view.toolCallId()));
		}
		Map<String, ToolCallView> toolCalls = new LinkedHashMap<>(state.toolCalls());
		toolCalls.put(view.toolCallId(), view);
		TranscriptState next = new TranscriptState(items, toolCalls, state.plan(), state.modes(), state.configOptions(),
				state.commands(), closeMessage ? null : state.openMessage(), state.nextMessageSeq());
		return ApplyResult.applied(next, new ToWebview.ToolCall(view));
	}

	/**
	 * Set (or clear with {@code null}) the delivery badge of the message with
	 * view id {@code messageId} (the id carried by {@code chunk} /
	 * {@code messageDelivery} posts). Unknown ids, and a badge that is already
	 * what is asked for, return the very state passed in.
	 */
	public static TranscriptState setMessageDelivery(TranscriptState state, String messageId, DeliveryState delivery) {
		boolean changed = false;
		List<TranscriptItem> items = new ArrayList<>(state.items().size());
		for (TranscriptItem item : state.items()) {
			if (item instanceof TranscriptItem.Message m && m.message().id().equals(messageId)
					&& m.message().delivery() != delivery) {
				MessageView message = m.message();
				items.add(new TranscriptItem.Message(new MessageView(message.id(), message.role(), message.segments(), delivery)));
				changed = true;
			} else {
				items.add(item);
			}
		}
		return changed ? state.withItems(items) : state;
	}

	/**
	 * Seed the transcript's session-level views from a {@code session/new}-style
	 * snapshot: an object with any of {@code modes}, {@code configOptions} and
	 * {@code availableCommands}. A field that is absent keeps what the
	 * transcript has; one that is present, {@code null} included, replaces it.
	 */
	public static TranscriptState withSessionSnapshot(TranscriptState state, JsonNode snapshot) {
		if (!isRecord(snapshot)) {
			return state;
		}
		return new TranscriptState(state.items(), state.toolCalls(), state.plan(),
				snapshot.has("modes") ? toModesView(snapshot.get("modes")) : state.modes(),
				snapshot.has("configOptions") ? toConfigOptionViews(snapshot.get("configOptions")) : state.configOptions(),
				snapshot.has("availableCommands") ? toCommandViews(snapshot.get("availableCommands")) : state.commands(),
				state.openMessage(), state.nextMessageSeq());
	}

	/**
	 * Replace the transcript's session-level views by those of {@code session}
	 * ({@code session/new}, {@code session/load}, state events). Modes and
	 * config options the agent does not advertise become none.
	 */
	public static TranscriptState withSessionSnapshot(TranscriptState state, SessionState session) {
		return new TranscriptState(state.items(), state.toolCalls(), state.plan(), toModesView(session.modes()),
				toConfigOptionViews(session.configOptions()), toCommandViews(session.availableCommands()), state.openMessage(),
				state.nextMessageSeq());
	}

	/**
	 * Combine the transcript with host-owned fields into a full {@link UiState}
	 * snapshot. The optional fields of {@code UiState} the reference's
	 * controller adds afterwards (usage, last activity, preferences, ...) are left unset.
	 */
	public static UiState snapshotToUiState(TranscriptState state, UiStateExtras extras) {
		return new UiState(extras.connection(), null, extras.sessionId(), extras.sessions(), extras.turnActive(), state.items(),
				state.toolCalls(), state.plan(), extras.permissions(), state.modes(), state.configOptions(), extras.models(),
				state.commands(), extras.chips(), extras.notices(), null, null, null);
	}

	// ---------------------------------------------------------------------
	// JSON and string helpers
	// ---------------------------------------------------------------------

	private static boolean isRecord(JsonNode node) {
		return node != null && node.isObject();
	}

	private static boolean isString(JsonNode node) {
		return node != null && node.isTextual();
	}

	/** The string field {@code name} of {@code node}, or {@code null} when {@code node} is no object or the field no string. */
	private static String text(JsonNode node, String name) {
		JsonNode value = isRecord(node) ? node.get(name) : null;
		return isString(value) ? value.textValue() : null;
	}

	private static String textOr(JsonNode node, String name, String fallback) {
		String value = text(node, name);
		return value != null ? value : fallback;
	}

	/** The field {@code name} of {@code object}, or {@code null} when absent or JSON {@code null}: both mean "not given". */
	private static JsonNode field(JsonNode object, String name) {
		JsonNode value = object == null ? null : object.get(name);
		return value == null || value.isNull() ? null : value;
	}

	/** The elements of a JSON array; nothing for anything else, objects included (they iterate too). */
	private static Iterable<JsonNode> elements(JsonNode node) {
		return node != null && node.isArray() ? node : List.of();
	}

	private static <T> T lookup(Map<String, T> byName, String name) {
		return name == null ? null : byName.get(name);
	}

	/** The enums of the model are named after their wire values, upper-cased. */
	private static <E extends Enum<E>> Map<String, E> byWireName(E[] values) {
		Map<String, E> out = new HashMap<>();
		for (E value : values) {
			out.put(value.name().toLowerCase(Locale.ROOT), value);
		}
		return Map.copyOf(out);
	}

	private static int firstOf(String s, char a, char b) {
		int ia = s.indexOf(a);
		int ib = s.indexOf(b);
		return ia < 0 ? ib : ib < 0 ? ia : Math.min(ia, ib);
	}

	/** Base64 as the page's own guard accepts it: the alphabet, then at most two {@code =}. */
	private static boolean isBase64(String data) {
		int end = data.length();
		for (int padding = 0; padding < 2 && end > 0 && data.charAt(end - 1) == '='; padding++) {
			end--;
		}
		if (end == 0) {
			return false;
		}
		for (int i = 0; i < end; i++) {
			char c = data.charAt(i);
			boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '+' || c == '/';
			if (!ok) {
				return false;
			}
		}
		return true;
	}

	/**
	 * JavaScript's {@code decodeURIComponent}: strict, unlike
	 * {@link java.net.URLDecoder}, which turns {@code +} into a space and
	 * malformed UTF-8 into replacement characters.
	 *
	 * @throws IllegalArgumentException for a {@code %} without two hex digits or escapes that are not UTF-8
	 */
	private static String percentDecode(String s) {
		if (s.indexOf('%') < 0) {
			return s;
		}
		StringBuilder out = new StringBuilder(s.length());
		ByteArrayOutputStream escaped = new ByteArrayOutputStream();
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '%') {
				int high = i + 2 < s.length() ? hexDigit(s.charAt(i + 1)) : -1;
				int low = i + 2 < s.length() ? hexDigit(s.charAt(i + 2)) : -1;
				if (high < 0 || low < 0) {
					throw new IllegalArgumentException("Malformed escape at " + i);
				}
				escaped.write(high << 4 | low);
				i += 2;
			} else {
				flushEscaped(escaped, out);
				out.append(c);
			}
		}
		flushEscaped(escaped, out);
		return out.toString();
	}

	/** ASCII only: Character.digit also takes the digits of other scripts. */
	private static int hexDigit(char c) {
		return c < 0x80 ? Character.digit(c, 16) : -1;
	}

	private static void flushEscaped(ByteArrayOutputStream escaped, StringBuilder out) {
		if (escaped.size() == 0) {
			return;
		}
		try {
			out.append(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(escaped.toByteArray())));
		} catch (CharacterCodingException e) {
			throw new IllegalArgumentException("Escapes are not UTF-8", e);
		}
		escaped.reset();
	}
}
