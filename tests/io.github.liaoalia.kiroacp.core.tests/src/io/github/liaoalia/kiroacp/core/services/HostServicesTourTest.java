package io.github.liaoalia.kiroacp.core.services;

import static io.github.liaoalia.kiroacp.core.services.Fakes.get;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.liaoalia.kiroacp.core.acp.FakeAgent;
import io.github.liaoalia.kiroacp.core.acp.api.AgentProcessOptions;
import io.github.liaoalia.kiroacp.core.acp.api.KiroAgent;
import io.github.liaoalia.kiroacp.core.acp.api.SessionEvent;
import io.github.liaoalia.kiroacp.core.acp.api.SessionState;
import io.github.liaoalia.kiroacp.core.acp.impl.KiroAgents;
import io.github.liaoalia.kiroacp.core.model.PermissionView;
import io.github.liaoalia.kiroacp.core.model.ToolKind;
import io.github.liaoalia.kiroacp.core.pure.output.TerminalPresentation;
import io.github.liaoalia.kiroacp.core.pure.permission.PermissionPolicies;
import io.github.liaoalia.kiroacp.core.pure.permission.PolicyDecision;
import io.github.liaoalia.kiroacp.core.services.Fakes.RecordingDisplay.RecordingConsole;
import io.github.liaoalia.kiroacp.core.services.TerminalService.TerminalSnapshot;

/**
 * The reference's fake agent against the real services, over a real
 * connection: its {@code tour} reads a file, runs a command, asks for a
 * permission, writes a file and has a URL opened (reference F6.1 and F7 at
 * this level; the chat page and the editors are not part of it).
 */
class HostServicesTourTest {

	@TempDir
	Path dir;

	@Test
	void theFakeAgentsTourRunsOnTheRealServices() throws Exception {
		Files.write(dir.resolve("README.md"), "# Tour\nsecond line\n".getBytes(StandardCharsets.UTF_8));
		Fakes.RecordingLogger logger = new Fakes.RecordingLogger();
		Fakes.FakeWorkspace workspace = new Fakes.FakeWorkspace(dir);
		Fakes.RecordingDisplay display = new Fakes.RecordingDisplay();
		// What a controller keeps for PermissionManager.ToolCallKinds: filled on the reader
		// thread, which delivers a tool call before the permission request about it.
		Map<String, ToolKind> kinds = new ConcurrentHashMap<>();
		List<PermissionView> asked = new CopyOnWriteArrayList<>();
		List<String> urls = new CopyOnWriteArrayList<>();
		List<FsService.FsWriteEvent> written = new CopyOnWriteArrayList<>();

		PermissionManager permissions = new PermissionManager((sessionId, toolCallId) -> kinds.get(sessionId + "/" + toolCallId),
				logger);
		// The user, allowing whatever is asked.
		permissions.onDidChange(() -> {
			for (PermissionView view : permissions.pending()) {
				asked.add(view);
				permissions.decideRequest(view.requestId(), PolicyDecision.ALLOW);
			}
		});
		FsService fs = new FsService(workspace, new DiskTextFiles(), logger);
		fs.onDidWrite(written::add);
		TerminalService terminal = new TerminalService(new Fakes.FakeSettings(), workspace, display, logger);
		ExternalActions external = new ExternalActions() {
			@Override
			public CompletionStage<OpenResult> confirmAndOpenUrl(String host, String url) {
				urls.add(host + " <- " + url);
				return CompletableFuture.completedFuture(OpenResult.OPENED);
			}

			@Override
			public CompletionStage<Integer> runSignIn(SignIn signIn) {
				return CompletableFuture.completedFuture(0);
			}
		};
		HostServices services = new HostServices(permissions, fs, terminal, external, logger);
		String script = FakeAgent.fixtures().resolve("fake-agent").resolve("agent.mjs").toString();
		KiroAgent agent = KiroAgents.create(new AgentProcessOptions("node", List.of(script, "acp", "--scenario", "tour"), dir,
				System.getenv(), logger, "0.1.0-test"), services);
		agent.onSessionEvent(event -> {
			if (event instanceof SessionEvent.Update update && update.update().path("toolCallId").isTextual()) {
				ToolKind kind = PermissionPolicies.toolKindOf(update.update().get("kind"));
				if (kind != null) {
					kinds.put(update.sessionId() + "/" + update.update().get("toolCallId").asText(), kind);
				}
			}
		});
		try {
			get(agent.start());
			SessionState session = get(agent.newSession(dir, List.of(), null));
			String sessionId = session.sessionId();

			JsonNode response = get(agent.prompt(sessionId,
					List.of(Fakes.MAPPER.createObjectNode().put("type", "text").put("text", "tour"))));

			assertEquals("end_turn", response.path("stopReason").asText(), logger.lines.toString());
			assertEquals(List.of("example.invalid <- https://example.invalid/oauth"), urls);

			// The permission was asked once, about the edit, and allowed.
			assertEquals(1, asked.size(), asked.toString());
			assertEquals("call-edit", asked.get(0).toolCallId());
			assertEquals(ToolKind.EDIT, asked.get(0).kind());
			assertEquals(0, permissions.size());

			// The write happened, inside the workspace, without a question.
			Path hello = dir.resolve("hello.txt");
			assertEquals("hello from kiro\n", new String(Files.readAllBytes(hello), StandardCharsets.UTF_8));
			assertEquals(1, written.size());
			assertEquals("hello from kiro\n", written.get(0).content());

			// The command ran in the session's console and was released.
			assertTrue(terminal.activeIds().isEmpty(), "the agent released its terminal");
			RecordingConsole console = display.only();
			assertEquals(TerminalPresentation.sharedTerminalName(sessionId), console.target.name());
			assertTrue(console.text().contains("hello\r\n") && console.text().contains("[exit code 0]"), console.text());
			String exited = console.events().stream().filter(event -> event.startsWith("exited ")).findFirst().orElseThrow();
			String terminalId = exited.split(" ")[1];
			assertTrue(terminalId.matches("term-[a-z0-9]+-\\d+"), terminalId);
			TerminalSnapshot snapshot = terminal.snapshot(terminalId, sessionId);
			assertTrue(snapshot.output().contains("hello"), snapshot.toString());
			assertEquals(0, snapshot.exitCode());
			assertNull(terminal.snapshot(terminalId, "some-other-session"));
			assertTrue(terminal.showTerminal(terminalId, sessionId));
		} finally {
			get(agent.stop());
			services.close();
			terminal.close();
			fs.close();
			permissions.close();
		}
	}
}
