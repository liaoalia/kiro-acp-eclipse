package io.github.liaoalia.kiroacp.core.acp.impl;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.liaoalia.kiroacp.core.acp.FakeAgent;
import io.github.liaoalia.kiroacp.core.acp.api.AcpException;
import io.github.liaoalia.kiroacp.core.acp.api.AgentProcessOptions;
import io.github.liaoalia.kiroacp.core.acp.api.ClientServices;
import io.github.liaoalia.kiroacp.core.acp.api.ConnectionStateChange;
import io.github.liaoalia.kiroacp.core.acp.api.KiroAgent;
import io.github.liaoalia.kiroacp.core.acp.api.SessionEvent;
import io.github.liaoalia.kiroacp.core.acp.api.SessionState;
import io.github.liaoalia.kiroacp.core.host.Logger;
import io.github.liaoalia.kiroacp.core.model.ConnectionStatus;

/**
 * A {@link KiroAgent} running the reference's fake agent, with everything it
 * reports recorded. The counterpart of the reference's {@code test/harness.ts}.
 *
 * <p>Nothing here sleeps to let something happen: every recorder wakes
 * {@link #await}, which re-checks its condition.
 */
final class AgentHarness implements AutoCloseable {

	static final ObjectMapper MAPPER = new ObjectMapper();
	static final long SECONDS = 30;
	static final String CLIENT_VERSION = "0.1.0-test";

	final Path cwd;
	/** The session cwd as it goes over the wire. */
	final String dir;
	final List<String> args;
	final RecordingLogger logger = new RecordingLogger();
	final Services services = new Services();
	final List<ConnectionStateChange> changes = new CopyOnWriteArrayList<>();
	final KiroAgent agent;
	private final List<SessionEvent> events = new CopyOnWriteArrayList<>();
	private final Object monitor = new Object();

	private AgentHarness(String command, List<String> args, Path cwd) {
		this.cwd = cwd;
		this.dir = cwd.toString();
		this.args = List.copyOf(args);
		this.agent = KiroAgents.create(new AgentProcessOptions(command, args, cwd, System.getenv(), logger, CLIENT_VERSION),
				services);
		agent.onSessionEvent(event -> {
			events.add(event);
			changed();
		});
		agent.onConnectionChange(change -> {
			changes.add(change);
			changed();
		});
	}

	/** @param agentArgs what follows {@code agent.mjs acp} */
	static AgentHarness launch(Path cwd, String... agentArgs) {
		List<String> args = new ArrayList<>(List.of(fakeAgentScript(), "acp"));
		args.addAll(List.of(agentArgs));
		return new AgentHarness("node", args, cwd);
	}

	static AgentHarness launchCommand(Path cwd, String command, String... args) {
		return new AgentHarness(command, List.of(args), cwd);
	}

	static String fakeAgentScript() {
		return FakeAgent.fixtures().resolve("fake-agent").resolve("agent.mjs").toString();
	}

	@Override
	public void close() throws Exception {
		get(agent.stop());
	}

	List<SessionEvent> events() {
		return List.copyOf(events);
	}

	List<ConnectionStatus> statuses() {
		return changes.stream().map(ConnectionStateChange::status).toList();
	}

	ConnectionStateChange lastChange() {
		return changes.get(changes.size() - 1);
	}

	/**
	 * A new session, once the fake agent has announced its commands. It does that a few
	 * milliseconds after answering, so a test that went on at once would find that update
	 * anywhere among the events of its first turn.
	 */
	SessionState newSession() throws Exception {
		SessionState session = get(agent.newSession(cwd, List.of(), null));
		await(() -> !agent.session(session.sessionId()).availableCommands().isEmpty(), "the commands of the new session");
		return session;
	}

	CompletableFuture<JsonNode> prompt(String sessionId, String text) {
		return agent.prompt(sessionId, List.of(text(text)));
	}

	static JsonNode text(String text) {
		ObjectNode block = MAPPER.createObjectNode();
		block.put("type", "text");
		block.put("text", text);
		return block;
	}

	private void changed() {
		synchronized (monitor) {
			monitor.notifyAll();
		}
	}

	/** Waits until the condition holds; it is re-checked whenever something was recorded. */
	void await(BooleanSupplier condition, String label) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SECONDS);
		synchronized (monitor) {
			while (!condition.getAsBoolean()) {
				long left = deadline - System.nanoTime();
				if (left <= 0) {
					fail("Timed out waiting for " + label + "; events: " + events + "; log: " + logger.lines);
				}
				TimeUnit.NANOSECONDS.timedWait(monitor, left);
			}
		}
	}

	static <T> T get(CompletableFuture<T> future) throws Exception {
		return future.get(SECONDS, TimeUnit.SECONDS);
	}

	/** The {@link AcpException} a future fails with. */
	static AcpException failure(CompletableFuture<?> future) {
		ExecutionException failure = assertThrows(ExecutionException.class, () -> future.get(SECONDS, TimeUnit.SECONDS));
		return assertInstanceOf(AcpException.class, failure.getCause());
	}

	static List<JsonNode> updatesOf(List<SessionEvent> events, String kind) {
		List<JsonNode> out = new ArrayList<>();
		for (SessionEvent event : events) {
			if (event instanceof SessionEvent.Update update
					&& (kind == null || kind.equals(update.update().path("sessionUpdate").asText()))) {
				out.add(update.update());
			}
		}
		return out;
	}

	static String chunkText(List<SessionEvent> events) {
		return chunkText(events, "agent_message_chunk");
	}

	static String chunkText(List<SessionEvent> events, String kind) {
		StringBuilder text = new StringBuilder();
		for (JsonNode update : updatesOf(events, kind)) {
			if ("text".equals(update.path("content").path("type").asText())) {
				text.append(update.path("content").path("text").asText());
			}
		}
		return text.toString();
	}

	static List<JsonNode> toolUpdates(List<SessionEvent> events, String toolCallId) {
		return updatesOf(events, "tool_call_update").stream().filter(u -> toolCallId.equals(u.path("toolCallId").asText()))
				.toList();
	}

	static SessionState latestState(List<SessionEvent> events) {
		SessionState latest = null;
		for (SessionEvent event : events) {
			if (event instanceof SessionEvent.State state) {
				latest = state.session();
			}
		}
		return latest;
	}

	/** The simple class names of the events, e.g. {@code TurnStarted}. */
	static List<String> kinds(List<SessionEvent> events) {
		return events.stream().map(event -> event.getClass().getSimpleName()).toList();
	}

	static JsonNode select(JsonNode request, String... kinds) {
		for (JsonNode option : request.path("options")) {
			if (List.of(kinds).contains(option.path("kind").asText())) {
				ObjectNode response = MAPPER.createObjectNode();
				response.putObject("outcome").put("outcome", "selected").put("optionId", option.path("optionId").asText());
				return response;
			}
		}
		ObjectNode response = MAPPER.createObjectNode();
		response.putObject("outcome").put("outcome", "cancelled");
		return response;
	}

	static JsonNode allowFirst(JsonNode request) {
		return select(request, "allow_once", "allow_always");
	}

	static JsonNode rejectFirst(JsonNode request) {
		return select(request, "reject_once", "reject_always");
	}

	record Call(String method, JsonNode params) {
	}

	record TerminalAuthRun(JsonNode method, String command, List<String> args) {
	}

	record Line(String level, String message) {
	}

	record Trace(Logger.Direction direction, String payload) {
	}

	final class RecordingLogger implements Logger {

		final List<Line> lines = new CopyOnWriteArrayList<>();
		final List<Trace> traces = new CopyOnWriteArrayList<>();

		@Override
		public void info(String message) {
			add("info", message);
		}

		@Override
		public void warn(String message) {
			add("warn", message);
		}

		@Override
		public void error(String message, Throwable cause) {
			add("error", message);
		}

		private void add(String level, String message) {
			lines.add(new Line(level, message));
			changed();
		}

		@Override
		public boolean traces() {
			return true;
		}

		@Override
		public void trace(Direction direction, String payload) {
			traces.add(new Trace(direction, payload));
			changed();
		}

		List<String> traced(Direction direction) {
			return traces.stream().filter(t -> t.direction() == direction).map(Trace::payload).toList();
		}

		boolean hasWarning(String part) {
			return lines.stream().anyMatch(l -> l.level().equals("warn") && l.message().contains(part));
		}
	}

	/** Answers every request at once, like the reference's mock, and records it. */
	final class Services implements ClientServices {

		final List<Call> calls = new CopyOnWriteArrayList<>();
		final List<String> openedUrls = new CopyOnWriteArrayList<>();
		final List<TerminalAuthRun> terminalAuthRuns = new CopyOnWriteArrayList<>();
		/** Replace to control permission answers; the default picks the first allow option. */
		volatile BiFunction<JsonNode, CompletionStage<Void>, CompletableFuture<JsonNode>> decidePermission = (request,
				cancelled) -> CompletableFuture.completedFuture(allowFirst(request));
		/** Replace to make reading a file fail. */
		volatile CompletableFuture<JsonNode> readResult;

		private void record(String method, JsonNode params) {
			calls.add(new Call(method, params));
			changed();
		}

		List<String> methods() {
			return calls.stream().map(Call::method).toList();
		}

		JsonNode paramsOf(String method) {
			return calls.stream().filter(c -> c.method().equals(method)).findFirst().orElseThrow().params();
		}

		@Override
		public CompletableFuture<JsonNode> requestPermission(JsonNode request, CompletionStage<Void> cancelled) {
			// Recorded, and waiting tests woken, only once the decider has seen the request.
			CompletableFuture<JsonNode> answer = decidePermission.apply(request, cancelled);
			record("session/request_permission", request);
			return answer;
		}

		@Override
		public CompletableFuture<JsonNode> readTextFile(JsonNode request) {
			record("fs/read_text_file", request);
			if (readResult != null) {
				return readResult;
			}
			return done(MAPPER.createObjectNode().put("content", "fake contents of " + request.path("path").asText()));
		}

		@Override
		public CompletableFuture<JsonNode> writeTextFile(JsonNode request) {
			record("fs/write_text_file", request);
			return done(MAPPER.createObjectNode());
		}

		@Override
		public CompletableFuture<JsonNode> createTerminal(JsonNode request) {
			record("terminal/create", request);
			return done(MAPPER.createObjectNode().put("terminalId", "term-1"));
		}

		@Override
		public CompletableFuture<JsonNode> terminalOutput(JsonNode request) {
			record("terminal/output", request);
			ObjectNode output = MAPPER.createObjectNode().put("output", "hello\n").put("truncated", false);
			output.putObject("exitStatus").put("exitCode", 0).putNull("signal");
			return done(output);
		}

		@Override
		public CompletableFuture<JsonNode> waitForTerminalExit(JsonNode request) {
			record("terminal/wait_for_exit", request);
			return done(MAPPER.createObjectNode().put("exitCode", 0).putNull("signal"));
		}

		@Override
		public CompletableFuture<JsonNode> killTerminal(JsonNode request) {
			record("terminal/kill", request);
			return done(MAPPER.createObjectNode());
		}

		@Override
		public CompletableFuture<JsonNode> releaseTerminal(JsonNode request) {
			record("terminal/release", request);
			return done(MAPPER.createObjectNode());
		}

		@Override
		public CompletableFuture<Void> openExternalUrl(String url) {
			record("_kiro/openExternalUrl", MAPPER.createObjectNode().put("url", url));
			openedUrls.add(url);
			return CompletableFuture.completedFuture(null);
		}

		@Override
		public CompletableFuture<Void> runTerminalAuth(JsonNode method, String command, List<String> args) {
			terminalAuthRuns.add(new TerminalAuthRun(method, command, List.copyOf(args)));
			return CompletableFuture.completedFuture(null);
		}

		private CompletableFuture<JsonNode> done(JsonNode result) {
			return CompletableFuture.completedFuture(result);
		}
	}
}
