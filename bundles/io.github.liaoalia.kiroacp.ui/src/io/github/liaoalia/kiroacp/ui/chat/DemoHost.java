package io.github.liaoalia.kiroacp.ui.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.eclipse.swt.widgets.Display;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Stand-in for the real chat controller: no agent process, canned replies.
 *
 * <p>It exists to prove the page, the bridge and the theme work inside Eclipse.
 * It answers "ready" with a state snapshot and "prompt" with a streamed reply
 * and a tool card. It is replaced by the ACP-backed controller.
 */
final class DemoHost {

	private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
	private static final int CHUNK_DELAY_MS = 35;

	private final Display display;
	private final Consumer<JsonNode> post;
	private final List<Runnable> scheduled = new ArrayList<>();
	private int nextId = 1;
	private boolean turnActive;
	/** The tool call of the running turn that has not reached a final status yet. */
	private String runningToolId;
	private boolean disposed;

	DemoHost(Display display, Consumer<JsonNode> post) {
		this.display = display;
		this.post = post;
	}

	void dispose() {
		disposed = true;
		cancelScheduled();
	}

	void onMessage(JsonNode message) {
		switch (message.path("type").asText()) {
			case "ready" -> post.accept(initialState());
			case "prompt" -> startTurn(message.path("text").asText(""));
			case "cancel" -> endTurn("cancelled");
			default -> {
				// Everything else needs the real controller.
			}
		}
	}

	private ObjectNode initialState() {
		ObjectNode state = JSON.objectNode();
		ObjectNode connection = state.putObject("connection");
		connection.put("status", "ready");
		connection.put("agentName", "Demo host (no agent)");
		connection.put("agentVersion", "0");
		state.put("sessionId", "demo");
		ObjectNode session = state.putArray("sessions").addObject();
		session.put("id", "demo");
		session.put("title", "Demo session");
		session.put("status", "idle");
		session.put("updatedAt", System.currentTimeMillis());
		state.put("turnActive", false);

		ArrayNode items = state.putArray("items");
		items.add(messageItem("user", "Show me what this view can render."));
		items.add(messageItem("agent", """
				This is the chat page of the VS Code extension running inside an **Eclipse view**.

				- Markdown, `inline code` and lists
				- Fenced code with a copy button
				- Tool cards and streaming

				```java
				Browser browser = new Browser(parent, SWT.EDGE);
				browser.setUrl(pageUrl());
				```

				Type a message below: the reply is canned, there is no agent behind this yet."""));

		state.putObject("toolCalls");
		state.putArray("plan");
		state.putArray("permissions");
		state.putNull("modes");
		state.putArray("configOptions");
		state.putNull("models");
		state.putArray("commands");
		state.putArray("chips");
		state.putArray("notices");

		ObjectNode out = JSON.objectNode();
		out.put("type", "state");
		out.set("state", state);
		return out;
	}

	private ObjectNode messageItem(String role, String text) {
		ObjectNode item = JSON.objectNode();
		item.put("type", "message");
		ObjectNode message = item.putObject("message");
		message.put("id", "m" + nextId++);
		message.put("role", role);
		ObjectNode segment = message.putArray("segments").addObject();
		segment.put("type", "text");
		segment.put("text", text);
		return item;
	}

	private void startTurn(String text) {
		if (turnActive || text.isBlank()) {
			return;
		}
		turnActive = true;
		post.accept(chunk("m" + nextId++, "user", text));
		post.accept(turn(true, null));

		String toolId = "t" + nextId++;
		String reply = "You wrote: *" + text.strip().replace("*", "\\*") + "*\n\n"
				+ "A real reply will come from `kiro-cli acp` once the controller is in place. "
				+ "Until then this text is streamed word by word to show how updates arrive.";
		String messageId = "m" + nextId++;

		int at = CHUNK_DELAY_MS;
		schedule(at, () -> {
			runningToolId = toolId;
			post.accept(toolCall(toolId, "in_progress", null));
		});
		at += 600;
		schedule(at, () -> {
			runningToolId = null;
			post.accept(toolCall(toolId, "completed", "3 files read"));
		});
		for (String word : reply.split("(?<= )")) {
			at += CHUNK_DELAY_MS;
			schedule(at, () -> post.accept(chunk(messageId, "agent", word)));
		}
		schedule(at + CHUNK_DELAY_MS, () -> endTurn("end_turn"));
	}

	private void endTurn(String stopReason) {
		if (!turnActive) {
			return;
		}
		turnActive = false;
		cancelScheduled();
		if (runningToolId != null) {
			// Its completion was just unscheduled; a card must not stay "running" after the turn.
			post.accept(toolCall(runningToolId, "cancelled", null));
			runningToolId = null;
		}
		post.accept(turn(false, stopReason));
	}

	private void schedule(int delayMs, Runnable action) {
		Runnable[] holder = new Runnable[1];
		holder[0] = () -> {
			if (scheduled.remove(holder[0]) && !disposed) {
				action.run();
			}
		};
		scheduled.add(holder[0]);
		display.timerExec(delayMs, holder[0]);
	}

	private void cancelScheduled() {
		for (Runnable r : scheduled) {
			if (!display.isDisposed()) {
				display.timerExec(-1, r);
			}
		}
		scheduled.clear();
	}

	private static ObjectNode chunk(String messageId, String role, String text) {
		ObjectNode out = JSON.objectNode();
		out.put("type", "chunk");
		out.put("messageId", messageId);
		out.put("role", role);
		ObjectNode segment = out.putObject("segment");
		segment.put("type", "text");
		segment.put("text", text);
		return out;
	}

	private static ObjectNode turn(boolean active, String stopReason) {
		ObjectNode out = JSON.objectNode();
		out.put("type", "turn");
		out.put("active", active);
		if (stopReason != null) {
			out.put("stopReason", stopReason);
		}
		return out;
	}

	private static ObjectNode toolCall(String id, String status, String resultText) {
		ObjectNode out = JSON.objectNode();
		out.put("type", "toolCall");
		ObjectNode call = out.putObject("call");
		call.put("toolCallId", id);
		call.put("title", "Read workspace files (demo)");
		call.put("kind", "read");
		call.put("status", status);
		ArrayNode content = call.putArray("content");
		if (resultText != null) {
			ObjectNode text = content.addObject();
			text.put("type", "text");
			text.put("text", resultText);
		}
		call.putArray("locations");
		return out;
	}
}
