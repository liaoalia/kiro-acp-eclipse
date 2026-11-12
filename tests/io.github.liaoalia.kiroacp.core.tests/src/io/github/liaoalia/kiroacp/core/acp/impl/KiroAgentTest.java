package io.github.liaoalia.kiroacp.core.acp.impl;

import static io.github.liaoalia.kiroacp.core.acp.impl.AgentHarness.MAPPER;
import static io.github.liaoalia.kiroacp.core.acp.impl.AgentHarness.chunkText;
import static io.github.liaoalia.kiroacp.core.acp.impl.AgentHarness.failure;
import static io.github.liaoalia.kiroacp.core.acp.impl.AgentHarness.get;
import static io.github.liaoalia.kiroacp.core.acp.impl.AgentHarness.kinds;
import static io.github.liaoalia.kiroacp.core.acp.impl.AgentHarness.latestState;
import static io.github.liaoalia.kiroacp.core.acp.impl.AgentHarness.text;
import static io.github.liaoalia.kiroacp.core.acp.impl.AgentHarness.toolUpdates;
import static io.github.liaoalia.kiroacp.core.acp.impl.AgentHarness.updatesOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import io.github.liaoalia.kiroacp.core.acp.api.AcpException;
import io.github.liaoalia.kiroacp.core.acp.api.AgentInfo;
import io.github.liaoalia.kiroacp.core.acp.api.ModelState;
import io.github.liaoalia.kiroacp.core.acp.api.SessionEvent;
import io.github.liaoalia.kiroacp.core.acp.api.SessionListEntry;
import io.github.liaoalia.kiroacp.core.acp.api.SessionListPage;
import io.github.liaoalia.kiroacp.core.acp.api.SessionState;
import io.github.liaoalia.kiroacp.core.acp.process.CliPathResolver;
import io.github.liaoalia.kiroacp.core.acp.process.Platform;
import io.github.liaoalia.kiroacp.core.host.Logger.Direction;
import io.github.liaoalia.kiroacp.core.model.ConnectionStatus;

/**
 * The cases of the reference's {@code test/acp-client.test.ts}, against the
 * same fake agent and recordings, followed by cases for what only this port
 * has: threads, restart, several sessions.
 */
class KiroAgentTest {

	/** A 1×1 PNG. */
	private static final String PNG_1PX = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";

	@TempDir
	Path cwd;

	private final List<AgentHarness> launched = new ArrayList<>();

	private AgentHarness launch(String... agentArgs) {
		AgentHarness harness = AgentHarness.launch(cwd, agentArgs);
		launched.add(harness);
		return harness;
	}

	@AfterEach
	void stopAgents() throws Exception {
		for (AgentHarness harness : launched) {
			harness.close();
		}
	}

	@Test
	void initializeExposesCapabilitiesAgentInfoAndKiroExtensionMethods() throws Exception {
		AgentHarness h = launch("--scenario", "chat");
		assertEquals(ConnectionStatus.STOPPED, h.agent.status());
		assertNull(h.agent.agent());

		AgentInfo info = get(h.agent.start());

		assertEquals(ConnectionStatus.READY, h.agent.status());
		assertSame(info, h.agent.agent());
		assertEquals(1, info.protocolVersion());
		assertEquals("fake-kiro", info.agentInfo().path("name").asText());
		assertTrue(info.acceptsImages());
		assertEquals(List.of("_kiro/session/context", "_kiro/account/getUsage"), info.kiroExtensionMethods());
		assertTrue(info.hasExtension("_kiro/session/context"));
		assertEquals(List.of(), info.authMethods());
		assertEquals(List.of(ConnectionStatus.STARTING, ConnectionStatus.READY), h.statuses());
		assertSame(info, h.changes.get(1).agent());

		List<String> sent = h.logger.traced(Direction.SEND);
		List<String> received = h.logger.traced(Direction.RECV);
		assertEquals(1, sent.size(), sent.toString());
		JsonNode init = MAPPER.readTree(sent.get(0));
		assertEquals("initialize", init.path("method").asText());
		JsonNode capabilities = init.path("params").path("clientCapabilities");
		assertEquals(1, init.path("params").path("protocolVersion").asInt());
		assertEquals(MAPPER.readTree("""
				{"fs":{"readTextFile":true,"writeTextFile":true},"terminal":true,"auth":{"terminal":true},
				 "_meta":{"kiro":{"openExternalUrl":true},"terminal-auth":true}}"""), capabilities);
		assertEquals(MAPPER.readTree("""
				{"name":"kiro-acp-eclipse","title":"Kiro ACP Chat for Eclipse","version":"0.1.0-test"}"""),
				init.path("params").path("clientInfo"));
		assertEquals(1, received.size(), received.toString());
		assertTrue(received.get(0).contains("fake-kiro"));

		assertSame(info, get(h.agent.start()), "start() is idempotent while running");

		get(h.agent.stop());
		assertEquals(ConnectionStatus.STOPPED, h.agent.status());
		assertEquals(ConnectionStatus.STOPPED, h.lastChange().status());
		get(h.agent.stop());
		assertEquals(List.of(ConnectionStatus.STARTING, ConnectionStatus.READY, ConnectionStatus.STOPPED), h.statuses());
	}

	@Test
	void signedOutAgentNewSessionFailureMeansAuthRequiredAndTerminalAuthGoesToTheHost() throws Exception {
		AgentHarness h = launch("--scenario", "chat", "--auth-required");
		AgentInfo info = get(h.agent.start());
		assertEquals(1, info.authMethods().size());
		JsonNode method = info.authMethods().get(0);
		assertEquals("kiro-login", method.path("id").asText());
		assertEquals("terminal", method.path("type").asText());

		AcpException error = failure(h.agent.newSession(cwd, List.of(), null));
		assertEquals(AcpException.AUTH_REQUIRED_CODE, error.code());
		assertTrue(error.isAuthRequired());
		assertEquals(ConnectionStatus.AUTH_REQUIRED, h.agent.status());
		assertEquals(ConnectionStatus.AUTH_REQUIRED, h.lastChange().status());
		assertEquals(1, h.lastChange().agent().authMethods().size());

		get(h.agent.authenticate("kiro-login"));
		assertEquals(1, h.services.terminalAuthRuns.size());
		AgentHarness.TerminalAuthRun advertised = h.services.terminalAuthRuns.get(0);
		assertEquals("kiro-login", advertised.method().path("id").asText());
		assertEquals(CliPathResolver.resolve("node", System.getenv()), advertised.command());
		assertTrue(Path.of(advertised.command()).isAbsolute(), advertised.command());
		// The method's args extend the configured invocation: <cli> acp ... --login.
		assertEquals(h.args, advertised.args());
		assertEquals(MAPPER.readTree("[\"--login\"]"), advertised.method().path("args"));
		assertTrue(failure(h.agent.authenticate("nope")).getMessage().contains("Unknown auth method"));
	}

	@Test
	void authRequiredIsRecognisedByCodeAndByKirosMessages() {
		assertTrue(new AcpException("nope", AcpException.AUTH_REQUIRED_CODE, null, null).isAuthRequired());
		assertTrue(new AcpException(
				"Auth refresh callback failed: Internal error: You are not logged in. Please log in with `kiro-cli login`.",
				-32603, null, null).isAuthRequired());
		assertTrue(new AcpException("authentication required", -32603, null, null).isAuthRequired());
		assertFalse(new AcpException("Internal error: model unavailable", -32603, null, null).isAuthRequired());
		assertFalse(new AcpException("session not found").isAuthRequired());
	}

	@Test
	void expiredTokenAPromptRejectedAsSignedOutMeansAuthRequiredWithTheTerminalLoginFallback() throws Exception {
		AgentHarness h = launch("--scenario", "chat");
		AgentInfo info = get(h.agent.start());
		assertEquals(List.of(), info.authMethods());
		SessionState session = h.newSession();
		assertEquals(ConnectionStatus.READY, h.agent.status());

		AcpException error = failure(h.prompt(session.sessionId(), "/scenario auth-expired"));
		assertTrue(error.getMessage().contains("not logged in"), error.getMessage());
		assertEquals(ConnectionStatus.AUTH_REQUIRED, h.agent.status());
		assertEquals(ConnectionStatus.AUTH_REQUIRED, h.lastChange().status());
		assertTrue(h.lastChange().message().contains("kiro-cli login"), h.lastChange().message());
		assertEquals(List.of(KiroAgents.FALLBACK_LOGIN_METHOD_ID), ids(h.lastChange().agent().authMethods()));
		SessionEvent last = h.events().get(h.events().size() - 1);
		assertEquals(new SessionEvent.TurnFailed(session.sessionId(), error), last);

		// The fallback runs <cliPath> login through the host's terminal auth.
		get(h.agent.authenticate(KiroAgents.FALLBACK_LOGIN_METHOD_ID));
		assertEquals(1, h.services.terminalAuthRuns.size());
		AgentHarness.TerminalAuthRun run = h.services.terminalAuthRuns.get(0);
		assertEquals(KiroAgents.fallbackLoginMethod(), run.method());
		assertEquals("kiro-cli-login", run.method().path("id").asText());
		assertEquals(MAPPER.readTree("[\"login\"]"), run.method().path("args"));
		assertEquals(CliPathResolver.resolve("node", System.getenv()), run.command());
		// The fallback is standalone: <cli> login, not <cli> acp ... login.
		assertEquals(List.of(), run.args());
	}

	@Test
	void refreshOnlyAuthMethodsAuthenticateFallsBackToTheTerminalLoginAndOnlyThatIsOffered() throws Exception {
		AgentHarness h = launch("--scenario", "chat", "--auth-agent-methods");
		AgentInfo info = get(h.agent.start());
		assertEquals(List.of("aws-builder-id", "aws-iam-identity-center"), ids(info.authMethods()));

		// Kiro rejects authenticate as "not logged in": kiro-cli login runs instead.
		get(h.agent.authenticate("aws-builder-id"));
		assertEquals(1, h.services.terminalAuthRuns.size());
		AgentHarness.TerminalAuthRun run = h.services.terminalAuthRuns.get(0);
		assertEquals(KiroAgents.FALLBACK_LOGIN_METHOD_ID, run.method().path("id").asText());
		assertEquals(List.of(), run.args());

		failure(h.agent.newSession(cwd, List.of(), null));
		assertEquals(ConnectionStatus.AUTH_REQUIRED, h.agent.status());
		// Signed out: only the terminal login is offered, not the refresh-only methods.
		assertEquals(List.of(KiroAgents.FALLBACK_LOGIN_METHOD_ID), ids(h.lastChange().agent().authMethods()));
	}

	@Test
	void theTerminalLoginFallbackIsAcceptedEvenWhileSignedIn() throws Exception {
		AgentHarness h = launch("--scenario", "chat");
		get(h.agent.start());

		get(h.agent.authenticate(KiroAgents.FALLBACK_LOGIN_METHOD_ID));

		assertEquals(1, h.services.terminalAuthRuns.size());
		assertEquals(ConnectionStatus.READY, h.agent.status());
	}

	@Test
	void streamedChatEventsArriveInOrderAndTheTurnFinishesWithThePromptResponse() throws Exception {
		AgentHarness h = launch("--scenario", "chat");
		get(h.agent.start());
		SessionState session = h.newSession();
		assertEquals(h.dir, session.cwd());
		assertEquals("vibe", session.modes().path("currentModeId").asText());
		assertEquals(List.of("model", "effortLevel", "autoCompact"), ids(session.configOptions()));
		assertNull(session.models());
		assertEquals(MAPPER.readTree("{\"kiro\":{\"engine\":\"v3\"}}"), session.meta());
		assertEquals(new SessionEvent.State(session), h.events().get(0));

		h.await(() -> commandNames(latestState(h.events())).size() == 2, "commands update");
		assertEquals(List.of("plan", "compact"), commandNames(latestState(h.events())));
		assertEquals(List.of("plan", "compact"), commandNames(h.agent.session(session.sessionId())));

		int before = h.events().size();
		JsonNode response = get(h.prompt(session.sessionId(), "hello"));
		assertEquals("end_turn", response.path("stopReason").asText());
		List<SessionEvent> turn = h.events().subList(before, h.events().size());
		assertEquals(List.of("TurnStarted", "Update", "Update", "Update", "TurnFinished"), kinds(turn));
		assertEquals("The answer is 42.", chunkText(h.events()));
		assertEquals(new SessionEvent.TurnFinished(session.sessionId(), "end_turn"), turn.get(turn.size() - 1));
		for (SessionEvent event : h.events()) {
			if (event instanceof SessionEvent.Update update) {
				assertEquals(session.sessionId(), update.sessionId());
				assertFalse(update.replay());
			}
		}

		h.await(() -> h.logger.hasWarning("fake-agent: chat scenario"), "stderr in the log");
		assertTrue(h.logger.traced(Direction.STDERR).contains("fake-agent: chat scenario"));
		assertTrue(h.logger.traced(Direction.SEND).stream().anyMatch(p -> p.contains("session/prompt")));
		assertTrue(h.logger.traced(Direction.RECV).stream().anyMatch(p -> p.contains("agent_message_chunk")));
	}

	@Test
	void imageBlocksReachTheAgentInOrderAndNoImageHidesTheCapability() throws Exception {
		int bytes = Base64.getDecoder().decode(PNG_1PX).length;
		AgentHarness h = launch("--scenario", "chat");
		get(h.agent.start());
		SessionState session = h.newSession();

		int before = h.events().size();
		JsonNode response = get(h.agent.prompt(session.sessionId(),
				List.of(text("/scenario images what is this?"), image("file:///tmp/my%20shot.png"),
						MAPPER.readTree("{\"type\":\"resource_link\",\"uri\":\"file:///tmp/notes.md\",\"name\":\"notes.md\"}"))));
		assertEquals("end_turn", response.path("stopReason").asText());
		assertEquals(
				"Received 1 image(s): image/png " + bytes + " B my shot.png; blocks: text,image,resource_link.",
				chunkText(h.events().subList(before, h.events().size())));

		before = h.events().size();
		get(h.agent.prompt(session.sessionId(), List.of(image(null), image(null))));
		assertEquals("Received 2 image(s): image/png " + bytes + " B, image/png " + bytes + " B; blocks: image,image.",
				chunkText(h.events().subList(before, h.events().size())));

		AgentHarness noImage = launch("--scenario", "chat", "--no-image");
		AgentInfo info = get(noImage.agent.start());
		assertFalse(info.acceptsImages());
		assertTrue(info.agentCapabilities().path("promptCapabilities").path("image").isMissingNode());
		assertTrue(info.agentCapabilities().path("promptCapabilities").path("embeddedContext").asBoolean());
	}

	@Test
	void refusalStopReasonIsReported() throws Exception {
		AgentHarness h = launch("--scenario", "refusal");
		get(h.agent.start());
		SessionState session = h.newSession();

		JsonNode response = get(h.prompt(session.sessionId(), "do something bad"));

		assertEquals("refusal", response.path("stopReason").asText());
		assertEquals(new SessionEvent.TurnFinished(session.sessionId(), "refusal"), h.events().get(h.events().size() - 1));
	}

	@Test
	void cancelInterruptsAStreamingTurnWithStopReasonCancelled() throws Exception {
		AgentHarness h = launch("--scenario", "cancel");
		get(h.agent.start());
		SessionState session = h.newSession();
		CompletableFuture<JsonNode> pending = h.prompt(session.sessionId(), "go");
		h.await(() -> updatesOf(h.events(), "agent_message_chunk").size() >= 3, "three chunks");

		get(h.agent.cancel(session.sessionId()));

		assertEquals("cancelled", get(pending).path("stopReason").asText());
		assertTrue(updatesOf(h.events(), "agent_message_chunk").size() < 40);
		assertEquals(new SessionEvent.TurnFinished(session.sessionId(), "cancelled"), h.events().get(h.events().size() - 1));
		assertTrue(h.logger.traced(Direction.SEND).stream().anyMatch(p -> p.contains("\"session/cancel\"")));
	}

	@Test
	void cancelResolvesAPendingPermissionRequestAsCancelled() throws Exception {
		AgentHarness h = launch("--scenario", "permission-reject");
		AtomicReference<CompletionStage<Void>> cancelled = new AtomicReference<>();
		CompletableFuture<JsonNode> hostAnswer = new CompletableFuture<>();
		h.services.decidePermission = (request, stage) -> {
			cancelled.set(stage);
			return hostAnswer;
		};
		get(h.agent.start());
		SessionState session = h.newSession();
		CompletableFuture<JsonNode> pending = h.prompt(session.sessionId(), "edit");
		h.await(() -> h.services.methods().contains("session/request_permission"), "permission request");
		assertFalse(cancelled.get().toCompletableFuture().isDone());

		get(h.agent.cancel(session.sessionId()));

		assertTrue(cancelled.get().toCompletableFuture().isDone(), "the host is told at once");
		assertEquals("cancelled", get(pending).path("stopReason").asText());
		JsonNode failed = toolUpdates(h.events(), "call-edit").stream().filter(u -> "failed".equals(u.path("status").asText()))
				.findFirst().orElseThrow();
		assertEquals("cancelled", failed.path("_meta").path("kiro").path("failureReason").asText());
		assertTrue(h.logger.traced(Direction.SEND).stream().anyMatch(p -> p.contains("\"outcome\":\"cancelled\"")));

		// A late answer from the host is ignored: nothing more is written for that request.
		int sentBefore = h.logger.traced(Direction.SEND).size();
		hostAnswer.complete(MAPPER.readTree("{\"outcome\":{\"outcome\":\"selected\",\"optionId\":\"accept\"}}"));
		assertEquals(sentBefore, h.logger.traced(Direction.SEND).size());
		// And the agent, which answers this only after everything written before it, saw no approval.
		get(h.agent.callExtension("_kiro/session/context", MAPPER.createObjectNode().put("sessionId", session.sessionId())));
		assertFalse(h.services.methods().contains("fs/write_text_file"));
	}

	@Test
	void tourFsTerminalAndPermissionRoundTripsThroughClientServices() throws Exception {
		AgentHarness h = launch("--scenario", "tour");
		get(h.agent.start());
		SessionState session = h.newSession();

		JsonNode response = get(h.prompt(session.sessionId(), "tour"));

		assertEquals("end_turn", response.path("stopReason").asText());
		String transcript = chunkText(h.events());
		assertEquals("Hello! I am the fake Kiro agent. Let me look around.All done.", transcript);
		assertEquals("Planning the tour of " + h.dir + ".", chunkText(h.events(), "agent_thought_chunk"));

		assertEquals(List.of("_kiro/openExternalUrl", "fs/read_text_file", "terminal/create", "terminal/output",
				"terminal/wait_for_exit", "terminal/release", "session/request_permission", "fs/write_text_file"),
				h.services.methods());
		assertEquals(List.of("https://example.invalid/oauth"), h.services.openedUrls);

		JsonNode read = h.services.paramsOf("fs/read_text_file");
		assertEquals(h.dir + "/README.md", read.path("path").asText());
		assertEquals(session.sessionId(), read.path("sessionId").asText());
		assertEquals(20, read.path("limit").asInt());

		JsonNode create = h.services.paramsOf("terminal/create");
		assertEquals("echo", create.path("command").asText());
		assertEquals(MAPPER.readTree("[\"hello\"]"), create.path("args"));
		for (String method : List.of("terminal/output", "terminal/wait_for_exit", "terminal/release")) {
			assertEquals("term-1", h.services.paramsOf(method).path("terminalId").asText());
		}
		List<JsonNode> execUpdates = toolUpdates(h.events(), "call-exec");
		JsonNode terminalContent = execUpdates.stream().filter(u -> u.path("content").toString().contains("\"terminal\""))
				.findFirst().orElseThrow();
		assertEquals(MAPPER.readTree("[{\"type\":\"terminal\",\"terminalId\":\"term-1\"}]"), terminalContent.path("content"));
		assertEquals("completed", execUpdates.get(execUpdates.size() - 1).path("status").asText());

		JsonNode permission = h.services.paramsOf("session/request_permission");
		assertEquals("call-edit", permission.path("toolCall").path("toolCallId").asText());
		assertEquals(List.of("allow_once", "allow_always", "reject_once", "reject_always"),
				texts(permission.path("options"), "kind"));
		assertEquals("fs_write", permission.path("_meta").path("kiro").path("consent").path("capability").asText());

		JsonNode write = h.services.paramsOf("fs/write_text_file");
		assertEquals(h.dir + "/hello.txt", write.path("path").asText());
		assertEquals("hello from kiro\n", write.path("content").asText());
		assertEquals(List.of("in_progress", "completed"),
				toolUpdates(h.events(), "call-edit").stream().map(u -> u.path("status").asText()).toList());

		List<JsonNode> plans = updatesOf(h.events(), "plan");
		assertEquals(2, plans.size());
		assertEquals("completed", plans.get(1).path("entries").path(0).path("status").asText());

		List<SessionEvent.ExtensionNotification> extensions = h.events().stream()
				.filter(SessionEvent.ExtensionNotification.class::isInstance)
				.map(SessionEvent.ExtensionNotification.class::cast).toList();
		assertEquals(List.of("_kiro/mcp/status", "session/update"),
				extensions.stream().map(SessionEvent.ExtensionNotification::method).toList());
		assertEquals("kiro_custom_update", extensions.get(1).params().path("update").path("sessionUpdate").asText());
		assertEquals(session.sessionId(), extensions.get(1).params().path("sessionId").asText());
	}

	@Test
	void rejectedPermissionFailsTheToolCallWithFailureReasonDenied() throws Exception {
		AgentHarness h = launch("--scenario", "permission-reject");
		h.services.decidePermission = (request, cancelled) -> CompletableFuture
				.completedFuture(AgentHarness.rejectFirst(request));
		get(h.agent.start());
		SessionState session = h.newSession();

		JsonNode response = get(h.prompt(session.sessionId(), "edit"));

		assertEquals("end_turn", response.path("stopReason").asText());
		assertFalse(h.services.methods().contains("fs/write_text_file"));
		JsonNode failed = toolUpdates(h.events(), "call-edit").stream().filter(u -> "failed".equals(u.path("status").asText()))
				.findFirst().orElseThrow();
		assertEquals("denied", failed.path("_meta").path("kiro").path("failureReason").asText());
		assertEquals("Understood.", chunkText(h.events()));
	}

	@Test
	void aPermissionAnsweredLaterFromAnotherThreadIsDelivered() throws Exception {
		AgentHarness h = launch("--scenario", "permission-reject");
		CompletableFuture<JsonNode> hostAnswer = new CompletableFuture<>();
		h.services.decidePermission = (request, cancelled) -> hostAnswer;
		get(h.agent.start());
		SessionState session = h.newSession();
		CompletableFuture<JsonNode> pending = h.prompt(session.sessionId(), "edit");
		h.await(() -> h.services.methods().contains("session/request_permission"), "permission request");
		assertFalse(pending.isDone(), "the turn waits for the user");

		hostAnswer.complete(AgentHarness.allowFirst(h.services.paramsOf("session/request_permission")));

		assertEquals("end_turn", get(pending).path("stopReason").asText());
		assertTrue(h.services.methods().contains("fs/write_text_file"));
	}

	@Test
	void switchModePermissionApprovingSwitchesTheMode() throws Exception {
		AgentHarness h = launch("--scenario", "switch-mode");
		get(h.agent.start());
		SessionState session = h.newSession();
		get(h.agent.setMode(session.sessionId(), "plan"));
		assertEquals("plan", latestState(h.events()).modes().path("currentModeId").asText());

		JsonNode response = get(h.prompt(session.sessionId(), "make a plan"));

		assertEquals("end_turn", response.path("stopReason").asText());
		JsonNode permission = h.services.paramsOf("session/request_permission");
		assertEquals("switch_mode", permission.path("toolCall").path("kind").asText());
		assertEquals("Approve plan and start implementation", permission.path("toolCall").path("title").asText());
		assertEquals("vibe", latestState(h.events()).modes().path("currentModeId").asText());
		assertTrue(chunkText(h.events()).contains("Starting implementation."));
	}

	@Test
	void anUnknownRequestFromTheAgentIsAnsweredWithMethodNotFound() throws Exception {
		AgentHarness h = launch("--scenario", "bad-request");
		get(h.agent.start());
		SessionState session = h.newSession();

		JsonNode response = get(h.prompt(session.sessionId(), "go"));

		assertEquals("end_turn", response.path("stopReason").asText());
		assertEquals("unknown request rejected with -32601", chunkText(h.events()));
		assertTrue(h.logger.hasWarning("_kiro/unknown/thing"), h.logger.lines.toString());
		assertTrue(h.logger.traced(Direction.SEND).stream().anyMatch(p -> p.contains("-32601")), "error response traced");
	}

	@Test
	void aFailingHostServiceIsAnsweredWithItsErrorCodeOrAnInternalError() throws Exception {
		AgentHarness h = launch("--scenario", "tour");
		h.services.readResult = CompletableFuture
				.failedFuture(new AcpException("no such file", -32002, MAPPER.createObjectNode().put("uri", "x"), null));
		get(h.agent.start());
		SessionState session = h.newSession();
		get(h.prompt(session.sessionId(), "tour"));
		JsonNode coded = errorResponse(h, "-32002");
		assertEquals("no such file", coded.path("error").path("message").asText());
		assertEquals("x", coded.path("error").path("data").path("uri").asText());

		h.services.readResult = CompletableFuture.failedFuture(new IllegalStateException("disk on fire"));
		get(h.prompt(session.sessionId(), "tour"));
		JsonNode internal = errorResponse(h, "-32603");
		assertEquals("Internal error", internal.path("error").path("message").asText());
		assertEquals("disk on fire", internal.path("error").path("data").path("details").asText());
	}

	@Test
	void setModeSetConfigOptionAndExtensionCallsUpdateSessionState() throws Exception {
		AgentHarness h = launch("--scenario", "chat");
		get(h.agent.start());
		SessionState session = h.newSession();
		String id = session.sessionId();

		get(h.agent.setMode(id, "plan"));
		assertEquals("plan", latestState(h.events()).modes().path("currentModeId").asText());
		assertEquals(2, latestState(h.events()).modes().path("availableModes").size());
		assertEquals("plan", h.agent.session(id).modes().path("currentModeId").asText());
		assertEquals(-32602, failure(h.agent.setMode(id, "bogus")).code());

		List<JsonNode> options = get(h.agent.setConfigOption(id, "model", TextNode.valueOf("claude-opus-4")));
		assertEquals("claude-opus-4", option(options, "model").path("currentValue").asText());
		assertEquals("claude-opus-4", option(latestState(h.events()).configOptions(), "model").path("currentValue").asText());

		List<JsonNode> bools = get(h.agent.setConfigOption(id, "autoCompact", BooleanNode.FALSE));
		assertEquals(BooleanNode.FALSE, option(bools, "autoCompact").path("currentValue"));
		String sentBool = h.logger.traced(Direction.SEND).stream()
				.filter(p -> p.contains("session/set_config_option") && p.contains("autoCompact")).findFirst().orElseThrow();
		assertTrue(sentBool.contains("\"type\":\"boolean\""), sentBool);
		String sentText = h.logger.traced(Direction.SEND).stream()
				.filter(p -> p.contains("session/set_config_option") && p.contains("claude-opus-4")).findFirst().orElseThrow();
		assertFalse(sentText.contains("\"type\""), sentText);

		assertTrue(failure(h.agent.setModel(id, "claude-opus-4")).getMessage().contains("did not advertise models"));

		ObjectNode params = MAPPER.createObjectNode().put("sessionId", id);
		JsonNode echoed = get(h.agent.callExtension("_kiro/session/context", params));
		assertTrue(echoed.path("ok").asBoolean());
		assertEquals(params, echoed.path("echoed"));
		assertEquals(-32601, failure(h.agent.callExtension("_kiro/not/advertised", MAPPER.createObjectNode())).code());

		// Prompting with a scenario selector works regardless of the launch scenario.
		assertEquals("refusal", get(h.prompt(id, "/scenario refusal")).path("stopReason").asText());
	}

	@Test
	void cliV2ModelsOnSessionNewSetModelAndItsCommandsNotification() throws Exception {
		AgentHarness h = launch("--scenario", "chat", "--engine", "v2");
		AgentInfo info = get(h.agent.start());
		assertEquals(List.of(), info.kiroExtensionMethods());
		SessionState session = h.newSession();
		assertNull(session.configOptions());
		assertEquals("claude-sonnet-4", session.models().currentModelId());
		assertEquals(List.of("claude-sonnet-4", "claude-opus-4"),
				session.models().availableModels().stream().map(ModelState.Model::modelId).toList());

		h.await(() -> latestState(h.events()).availableCommands().size() == 2, "v2 commands");
		assertEquals(MAPPER.readTree("""
				[{"name":"help","description":"Show help"},
				 {"name":"context","description":"Manage context","input":{"hint":"add <file>"}}]"""),
				MAPPER.valueToTree(latestState(h.events()).availableCommands()));
		List<JsonNode> synthetic = updatesOf(h.events(), "available_commands_update");
		assertEquals(1, synthetic.size());
		assertEquals(MAPPER.valueToTree(latestState(h.events()).availableCommands()), synthetic.get(0).path("availableCommands"));
		assertFalse(h.events().stream().anyMatch(e -> e instanceof SessionEvent.ExtensionNotification n
				&& n.method().equals(KiroAgents.KIRO_V2_COMMANDS_AVAILABLE)));

		get(h.agent.setModel(session.sessionId(), "claude-opus-4"));
		assertEquals("claude-opus-4", latestState(h.events()).models().currentModelId());
		assertEquals(2, latestState(h.events()).models().availableModels().size());
		assertEquals(-32602, failure(h.agent.setModel(session.sessionId(), "nope")).code());
		assertEquals(-32601, failure(h.agent.callExtension("_kiro/session/context", MAPPER.createObjectNode())).code());
	}

	@Test
	void sessionListRejectedWhenNotAdvertisedAndPagedWithACwdFilterWhenItIs() throws Exception {
		AgentHarness v2 = launch("--scenario", "chat", "--engine", "v2");
		AgentInfo v2Info = get(v2.agent.start());
		assertFalse(v2Info.canLoadSessions());
		assertFalse(v2Info.canListSessions());
		assertTrue(failure(v2.agent.listSessions(null, null)).getMessage().contains("did not advertise session/list"));
		assertTrue(failure(v2.agent.loadSession("past-1", cwd, List.of())).getMessage()
				.contains("did not advertise session/load"));
		assertFalse(v2.logger.traced(Direction.SEND).stream().anyMatch(p -> p.contains("session/list")));
		assertNull(v2.agent.session("past-1"));

		AgentHarness h = launch("--scenario", "chat");
		AgentInfo info = get(h.agent.start());
		assertTrue(info.canLoadSessions());
		assertTrue(info.canListSessions());

		// The canned sessions are pinned to the cwd of the first session/new; before that they
		// report the agent's own working directory, which is the same folder here.
		SessionListPage all = get(h.agent.listSessions(null, null));
		assertNull(all.nextCursor());
		assertEquals(List.of("past-1", "past-2"), sessionIds(all));
		assertEquals(List.of("Investigate flaky CI", "Add retry to fetch"),
				all.sessions().stream().map(SessionListEntry::title).toList());
		for (SessionListEntry entry : all.sessions()) {
			assertTrue(entry.updatedAt().matches("^\\d{4}-\\d{2}-\\d{2}T.*"), entry.updatedAt());
		}

		// Live sessions join the list, newest first; the cwd filter matches exactly. Three
		// matches exceed the fake agent's page size (2): the first page carries a cursor.
		SessionState session = h.newSession();
		SessionListPage withLive = get(h.agent.listSessions(cwd, null));
		assertEquals(List.of(session.sessionId(), "past-1"), sessionIds(withLive));
		assertEquals(h.dir, withLive.sessions().get(0).cwd());
		assertNotNull(withLive.nextCursor());
		SessionListPage secondPage = get(h.agent.listSessions(cwd, withLive.nextCursor()));
		assertEquals(List.of("past-2"), sessionIds(secondPage));
		assertNull(secondPage.nextCursor());
		SessionListPage elsewhere = get(h.agent.listSessions(Path.of(h.dir + "-elsewhere"), null));
		assertEquals(List.of(), elsewhere.sessions());
		assertNull(elsewhere.nextCursor());

		List<String> sentLists = h.logger.traced(Direction.SEND).stream().filter(p -> p.contains("\"session/list\"")).toList();
		assertEquals(4, sentLists.size());
		assertEquals(MAPPER.createObjectNode(), MAPPER.readTree(sentLists.get(0)).path("params"));
		JsonNode secondRequest = MAPPER.readTree(sentLists.get(2)).path("params");
		assertEquals(withLive.nextCursor(), secondRequest.path("cursor").asText());
		assertEquals(h.dir, secondRequest.path("cwd").asText());
		assertFalse(sentLists.get(1).contains("\"cursor\""), "the first page is requested without a cursor");
		assertTrue(sentLists.get(3).contains("-elsewhere"));
	}

	@Test
	void sessionLoadReplaysHistoryFlaggedAsReplayThenPromptsStreamLive() throws Exception {
		AgentHarness h = launch("--scenario", "chat");
		get(h.agent.start());
		assertNull(h.agent.session("past-1"));

		SessionState state = get(h.agent.loadSession("past-1", cwd, List.of()));
		assertEquals("past-1", state.sessionId());
		assertEquals(h.dir, state.cwd());
		assertEquals("vibe", state.modes().path("currentModeId").asText());
		assertEquals(List.of("model", "effortLevel", "autoCompact"), ids(state.configOptions()));
		assertNull(state.models());

		String sent = h.logger.traced(Direction.SEND).stream().filter(p -> p.contains("\"session/load\"")).findFirst()
				.orElseThrow();
		assertEquals(MAPPER.createObjectNode().put("sessionId", "past-1").put("cwd", h.dir).set("mcpServers",
				MAPPER.createArrayNode()), MAPPER.readTree(sent).path("params"));

		// Everything before the State event is replayed history, flagged as such.
		List<SessionEvent> events = h.events();
		int stateIndex = kinds(events).indexOf("State");
		assertTrue(stateIndex >= 4, "replayed updates before the state event: " + kinds(events));
		assertEquals(new SessionEvent.State(state), events.get(stateIndex));
		List<SessionEvent> replayed = events.subList(0, stateIndex);
		for (SessionEvent event : replayed) {
			SessionEvent.Update update = (SessionEvent.Update) event;
			assertEquals("past-1", update.sessionId());
			assertTrue(update.replay());
			assertTrue(update.update().path("_meta").path("kiro").path("replay").asBoolean());
		}
		JsonNode user = updatesOf(replayed, "user_message_chunk").get(0);
		assertEquals("u-past-1-1", user.path("messageId").asText());
		assertEquals("Why is CI flaky?", user.path("content").path("text").asText());
		assertEquals("a-past-1-1", updatesOf(replayed, "agent_message_chunk").get(0).path("messageId").asText());
		JsonNode toolCall = updatesOf(replayed, "tool_call").get(0);
		assertEquals("read", toolCall.path("kind").asText());
		assertEquals("completed", toolCall.path("status").asText());
		assertEquals("Let me look at .github/workflows/ci.yml. The test job has no retry and the network step times out.",
				chunkText(events));

		// The commands update after the load is routed to the loaded session and is not a replay.
		h.await(() -> latestState(h.events()).availableCommands().size() == 2, "commands after load");
		SessionEvent.Update commands = h.events().stream().filter(SessionEvent.Update.class::isInstance)
				.map(SessionEvent.Update.class::cast)
				.filter(u -> u.update().path("sessionUpdate").asText().equals("available_commands_update")).findFirst()
				.orElseThrow();
		assertFalse(commands.replay());
		assertEquals(2, h.agent.session("past-1").availableCommands().size());
		assertEquals("vibe", h.agent.session("past-1").modes().path("currentModeId").asText());

		// A prompt on the loaded session streams normally, without the replay flag.
		int before = h.events().size();
		JsonNode response = get(h.prompt("past-1", "and now?"));
		assertEquals("end_turn", response.path("stopReason").asText());
		List<SessionEvent> turn = h.events().subList(before, h.events().size());
		assertEquals(List.of("TurnStarted", "Update", "Update", "Update", "TurnFinished"), kinds(turn));
		for (SessionEvent event : turn) {
			if (event instanceof SessionEvent.Update update) {
				assertEquals("past-1", update.sessionId());
				assertFalse(update.replay());
			}
		}
		assertEquals("The answer is 42.", chunkText(turn));

		// The prompt was appended to the session's history: it is now the newest session.
		SessionListPage listed = get(h.agent.listSessions(null, null));
		assertEquals("past-1", listed.sessions().get(0).sessionId());
		assertEquals("Investigate flaky CI", listed.sessions().get(0).title());
	}

	@Test
	void sessionLoadOfAnUnknownIdFailsAndLeavesNoSessionRegistered() throws Exception {
		AgentHarness h = launch("--scenario", "chat");
		get(h.agent.start());

		assertEquals(-32002, failure(h.agent.loadSession("nope", cwd, List.of())).code());

		assertNull(h.agent.session("nope"));
		assertEquals(List.of(), h.events());
		// Prompting a past session that was never loaded fails at the agent as well.
		failure(h.prompt("past-2", "hi"));
		assertNull(h.agent.session("past-2"));
		assertEquals(List.of("TurnStarted", "TurnFailed"), kinds(h.events()));
	}

	@Test
	void processExitMidTurnFailsThePromptAndReportsAnErrorStatus() throws Exception {
		AgentHarness h = launch("--scenario", "crash");
		get(h.agent.start());
		SessionState session = h.newSession();

		int before = h.events().size();

		AcpException error = failure(h.prompt(session.sessionId(), "crash"));

		h.await(() -> h.agent.status() == ConnectionStatus.ERROR, "error status");
		h.await(() -> h.lastChange().status() == ConnectionStatus.ERROR, "error status reported");
		assertTrue(h.lastChange().message().contains("code 3"), h.lastChange().message());
		assertNull(h.lastChange().agent());
		// What the agent wrote before it died was still delivered, and before the failure.
		assertEquals(List.of("TurnStarted", "Update", "TurnFailed"), kinds(h.events().subList(before, h.events().size())));
		assertEquals("about to crash", chunkText(h.events()));
		assertTrue(h.events().contains(new SessionEvent.TurnFailed(session.sessionId(), error)));
		assertNull(h.agent.session(session.sessionId()));
		assertTrue(failure(h.agent.newSession(cwd, List.of(), null)).getMessage().contains("not running"));
		assertTrue(h.logger.lines.contains(new AgentHarness.Line("error", "kiro-cli exited with code 3")));

		get(h.agent.stop());
		assertEquals(ConnectionStatus.STOPPED, h.agent.status());
	}

	@Test
	void stopTerminatesTheProcessAndAMissingExecutableIsReported() throws Exception {
		AgentHarness h = launch("--scenario", "chat");
		get(h.agent.start());
		SessionState session = h.newSession();
		get(h.agent.stop());
		assertEquals(ConnectionStatus.STOPPED, h.agent.status());
		assertEquals(List.of(ConnectionStatus.STARTING, ConnectionStatus.READY, ConnectionStatus.STOPPED), h.statuses());
		assertEquals("agent stopped", h.lastChange().message());
		assertNull(h.agent.session(session.sessionId()));
		assertTrue(failure(h.prompt(session.sessionId(), "hi")).getMessage().contains("not running"));

		AgentHarness missing = AgentHarness.launchCommand(cwd, "definitely-not-a-real-binary-xyz");
		launched.add(missing);
		failure(missing.agent.start());
		assertEquals(ConnectionStatus.ERROR, missing.agent.status());
		assertTrue(missing.lastChange().message().startsWith("Failed to start definitely-not-a-real-binary-xyz"),
				missing.lastChange().message());
		get(missing.agent.stop());
		assertEquals(ConnectionStatus.STOPPED, missing.agent.status());
	}

	@Test
	void stopFailsAPendingPromptAndWithdrawsAPendingPermission() throws Exception {
		AgentHarness h = launch("--scenario", "permission-reject");
		AtomicReference<CompletionStage<Void>> cancelled = new AtomicReference<>();
		h.services.decidePermission = (request, stage) -> {
			cancelled.set(stage);
			return new CompletableFuture<>();
		};
		get(h.agent.start());
		SessionState session = h.newSession();
		CompletableFuture<JsonNode> pending = h.prompt(session.sessionId(), "edit");
		h.await(() -> cancelled.get() != null, "permission request");

		get(h.agent.stop());

		assertTrue(cancelled.get().toCompletableFuture().isDone());
		assertEquals("agent stopped", failure(pending).getMessage());
		assertEquals(ConnectionStatus.STOPPED, h.lastChange().status());
		assertFalse(h.statuses().contains(ConnectionStatus.ERROR));
	}

	@Test
	void steeringIsInjectedIntoTheRunningTurnOnV3AndUnknownOnV2() throws Exception {
		AgentHarness h = launch("--scenario", "chat");
		get(h.agent.start());
		SessionState session = h.newSession();
		// Not advertised (the real CLI did not advertise it either); the client does not gate on the list.
		assertEquals(-32602, failure(h.agent.callExtension("_session/steer", steer(session, "nothing runs"))).code());

		CompletableFuture<JsonNode> turn = h.prompt(session.sessionId(), "/scenario slow");
		h.await(() -> chunkText(h.events()).contains("slow 1 "), "the slow turn to stream");
		JsonNode steered = get(h.agent.callExtension("_session/steer", steer(session, "hi there")));
		assertEquals(MAPPER.createObjectNode(), steered);
		h.await(() -> chunkText(h.events()).contains("Steered: hi there"), "the steered answer");
		List<SessionEvent> soFar = h.events();
		assertTrue(updatesOf(soFar, "session_info_update").stream()
				.anyMatch(u -> u.path("_meta").path("kiro").path("kind").asText().equals("steering_injected")));
		// Judged on the events up to the steered chunk, so it does not matter how far the turn got meanwhile.
		int steeredAt = indexOfChunk(soFar, "Steered: hi there");
		assertFalse(kinds(soFar.subList(0, steeredAt)).contains("TurnFinished"), "the steered answer arrived inside the turn");
		assertEquals("end_turn", get(turn).path("stopReason").asText());

		AgentHarness v2 = launch("--scenario", "chat", "--engine", "v2");
		get(v2.agent.start());
		SessionState v2Session = v2.newSession();
		assertEquals(-32601, failure(v2.agent.callExtension("_session/steer", steer(v2Session, "x"))).code());
	}

	/** DECISIONS D2: nothing the agent wrote before the response is still on its way when the future completes. */
	@RepeatedTest(5)
	void everyUpdateOfATurnIsDeliveredBeforeThePromptCompletes() throws Exception {
		AgentHarness h = launch("--scenario", "slow");
		get(h.agent.start());
		SessionState session = h.newSession();
		int before = h.events().size();

		// The dependent runs when the future completes (or at once if it already has), so what it
		// sees is what had been delivered by then.
		List<SessionEvent> seen = get(h.prompt(session.sessionId(), "go").thenApply(response -> h.events()));

		List<String> expected = new ArrayList<>(List.of("TurnStarted"));
		for (int i = 0; i < 20; i++) {
			expected.add("Update");
		}
		expected.add("TurnFinished");
		assertEquals(expected, kinds(seen.subList(before, seen.size())));
	}

	@Test
	void listenersForWireEventsRunOnOneThreadAndNewSessionStateComesBeforeItsFuture() throws Exception {
		AgentHarness h = launch("--scenario", "chat");
		List<String> threads = new java.util.concurrent.CopyOnWriteArrayList<>();
		h.agent.onSessionEvent(event -> {
			if (!(event instanceof SessionEvent.TurnStarted)) {
				threads.add(Thread.currentThread().getName());
			}
		});
		get(h.agent.start());

		List<SessionEvent> atCompletion = get(h.agent.newSession(cwd, List.of(), null).thenApply(state -> h.events()));
		assertEquals("State", kinds(atCompletion).get(0));
		SessionState session = latestState(atCompletion);
		get(h.prompt(session.sessionId(), "hello"));

		assertEquals(1, threads.stream().distinct().count(), threads.toString());
		assertTrue(threads.get(0).startsWith("acp-reader-"), threads.get(0));
	}

	@Test
	void aRemovedListenerHearsNothingMoreAndAThrowingOneDoesNotBreakTheOthers() throws Exception {
		AgentHarness h = launch("--scenario", "chat");
		List<SessionEvent> heard = new java.util.concurrent.CopyOnWriteArrayList<>();
		h.agent.onSessionEvent(event -> {
			throw new IllegalStateException("listener bug");
		});
		io.github.liaoalia.kiroacp.core.host.Subscription subscription = h.agent.onSessionEvent(heard::add);
		get(h.agent.start());
		SessionState session = h.newSession();
		// A response is handled after everything before it has been through every listener.
		get(h.agent.callExtension("_kiro/session/context", MAPPER.createObjectNode().put("sessionId", session.sessionId())));
		assertEquals(List.of("State", "Update", "State"), kinds(heard));
		subscription.close();
		subscription.close();

		assertEquals("end_turn", get(h.prompt(session.sessionId(), "hello")).path("stopReason").asText());

		assertEquals(3, heard.size());
		assertEquals("The answer is 42.", chunkText(h.events()));
		assertTrue(h.logger.lines.stream().anyMatch(l -> l.level().equals("error") && l.message().contains("listener bug")));
	}

	@Test
	void oneProcessHostsSeveralSessionsWhoseTurnsOverlap() throws Exception {
		AgentHarness h = launch("--scenario", "slow");
		get(h.agent.start());
		SessionState first = h.newSession();
		SessionState second = h.newSession();
		assertNotEquals(first.sessionId(), second.sessionId());

		CompletableFuture<JsonNode> firstTurn = h.prompt(first.sessionId(), "go");
		CompletableFuture<JsonNode> secondTurn = h.prompt(second.sessionId(), "/scenario chat");
		assertEquals("end_turn", get(firstTurn).path("stopReason").asText());
		assertEquals("end_turn", get(secondTurn).path("stopReason").asText());

		assertEquals("The answer is 42.", chunkText(of(h.events(), second.sessionId())));
		assertEquals(20, updatesOf(of(h.events(), first.sessionId()), "agent_message_chunk").size());
		assertEquals(2, h.agent.session(first.sessionId()).availableCommands().size());
		assertEquals(2, h.agent.session(second.sessionId()).availableCommands().size());
		assertEquals(1, h.logger.lines.stream().filter(l -> l.message().startsWith("Starting agent:")).count());
	}

	@Test
	void startAfterStopAndAfterACrashRunsANewProcess() throws Exception {
		AgentHarness h = launch("--scenario", "chat");
		AgentInfo firstInfo = get(h.agent.start());
		SessionState firstSession = h.newSession();
		get(h.agent.stop());

		AgentInfo secondInfo = get(h.agent.start());
		assertNotSame(firstInfo, secondInfo);
		assertEquals(ConnectionStatus.READY, h.agent.status());
		assertNull(h.agent.session(firstSession.sessionId()), "sessions die with their process");
		SessionState session = h.newSession();
		assertEquals("end_turn", get(h.prompt(session.sessionId(), "hello")).path("stopReason").asText());

		// A crash, then a start without a stop in between.
		failure(h.prompt(session.sessionId(), "/scenario crash"));
		h.await(() -> h.agent.status() == ConnectionStatus.ERROR, "error status");
		get(h.agent.start());
		assertEquals(ConnectionStatus.READY, h.agent.status());
		SessionState afterCrash = h.newSession();
		assertEquals("end_turn", get(h.prompt(afterCrash.sessionId(), "hello")).path("stopReason").asText());

		assertEquals(List.of(ConnectionStatus.STARTING, ConnectionStatus.READY, ConnectionStatus.STOPPED,
				ConnectionStatus.STARTING, ConnectionStatus.READY, ConnectionStatus.ERROR, ConnectionStatus.STARTING,
				ConnectionStatus.READY), h.statuses());
		// The agent of the previous run is still reported while the next one starts, as in the reference.
		assertSame(firstInfo, h.changes.get(3).agent());
	}

	@Test
	void startCalledTwiceAtOnceStartsOneProcessAndStartRightAfterStopWaitsForIt() throws Exception {
		AgentHarness h = launch("--scenario", "chat");
		CompletableFuture<AgentInfo> first = h.agent.start();
		CompletableFuture<AgentInfo> second = h.agent.start();
		assertSame(get(first), get(second));
		assertEquals(1, h.logger.lines.stream().filter(l -> l.message().startsWith("Starting agent:")).count());

		CompletableFuture<Void> stopped = h.agent.stop();
		CompletableFuture<AgentInfo> restarted = h.agent.start();
		get(stopped);
		get(restarted);
		assertEquals(ConnectionStatus.READY, h.agent.status());
		assertEquals(List.of(ConnectionStatus.STARTING, ConnectionStatus.READY, ConnectionStatus.STOPPED,
				ConnectionStatus.STARTING, ConnectionStatus.READY), h.statuses());
	}

	@Test
	void requestsBeforeStartFailAsNotRunningAndCancelIsHarmless() throws Exception {
		AgentHarness h = launch("--scenario", "chat");

		assertTrue(failure(h.agent.newSession(cwd, List.of(), null)).getMessage().contains("not running"));
		assertTrue(failure(h.prompt("s", "hi")).getMessage().contains("not running"));
		assertTrue(failure(h.agent.setMode("s", "plan")).getMessage().contains("not running"));
		assertTrue(failure(h.agent.callExtension("_kiro/x", null)).getMessage().contains("not running"));
		assertTrue(failure(h.agent.authenticate("kiro-login")).getMessage().contains("not initialized"));
		get(h.agent.cancel("s"));
		assertEquals(List.of(), h.events());
		assertEquals(List.of(), h.changes);
	}

	@Test
	void aCmdWrapperIsStartedThroughTheShellAndStoppedWithTheAgentBehindIt() throws Exception {
		org.junit.jupiter.api.Assumptions.assumeTrue(Platform.current() == Platform.WINDOWS, "cmd.exe is Windows only");
		Path tools = java.nio.file.Files.createDirectories(cwd.resolve("my tools"));
		Path wrapper = tools.resolve("kiro-cli.cmd");
		java.nio.file.Files.writeString(wrapper, "@node \"" + AgentHarness.fakeAgentScript() + "\" %*\r\n");
		AgentHarness h = AgentHarness.launchCommand(cwd, wrapper.toString(), "acp", "--scenario", "chat");
		launched.add(h);

		AgentInfo info = get(h.agent.start());
		assertEquals("fake-kiro", info.agentInfo().path("name").asText());
		SessionState session = h.newSession();
		assertEquals("end_turn", get(h.prompt(session.sessionId(), "hello")).path("stopReason").asText());
		assertEquals("The answer is 42.", chunkText(h.events()));

		get(h.agent.stop());
		assertEquals(ConnectionStatus.STOPPED, h.agent.status());
	}

	private static JsonNode errorResponse(AgentHarness h, String code) throws Exception {
		String line = h.logger.traced(Direction.SEND).stream()
				.filter(p -> p.contains("\"error\"") && p.contains("\"code\":" + code)).findFirst().orElseThrow();
		return MAPPER.readTree(line);
	}

	private static ObjectNode steer(SessionState session, String text) {
		ObjectNode params = MAPPER.createObjectNode().put("sessionId", session.sessionId());
		params.putArray("prompt").add(text(text));
		return params;
	}

	private static int indexOfChunk(List<SessionEvent> events, String text) {
		for (int i = 0; i < events.size(); i++) {
			if (events.get(i) instanceof SessionEvent.Update update
					&& update.update().path("content").path("text").asText().contains(text)) {
				return i;
			}
		}
		throw new AssertionError("no chunk with " + text);
	}

	private static List<SessionEvent> of(List<SessionEvent> events, String sessionId) {
		return events.stream().filter(e -> e instanceof SessionEvent.Update update && update.sessionId().equals(sessionId))
				.toList();
	}

	private static JsonNode image(String uri) {
		ObjectNode block = MAPPER.createObjectNode().put("type", "image").put("mimeType", "image/png").put("data", PNG_1PX);
		if (uri != null) {
			block.put("uri", uri);
		}
		return block;
	}

	private static List<String> ids(List<JsonNode> nodes) {
		return nodes.stream().map(node -> node.path("id").asText()).toList();
	}

	private static List<String> texts(JsonNode array, String field) {
		List<String> out = new ArrayList<>();
		array.forEach(node -> out.add(node.path(field).asText()));
		return out;
	}

	private static List<String> commandNames(SessionState state) {
		return state.availableCommands().stream().map(command -> command.path("name").asText()).toList();
	}

	private static List<String> sessionIds(SessionListPage page) {
		return page.sessions().stream().map(SessionListEntry::sessionId).toList();
	}

	private static JsonNode option(List<JsonNode> options, String id) {
		return options.stream().filter(o -> o.path("id").asText().equals(id)).findFirst().orElseThrow();
	}
}
