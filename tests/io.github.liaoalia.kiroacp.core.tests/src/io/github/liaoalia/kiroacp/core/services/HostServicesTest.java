package io.github.liaoalia.kiroacp.core.services;

import static io.github.liaoalia.kiroacp.core.services.Fakes.failure;
import static io.github.liaoalia.kiroacp.core.services.Fakes.get;
import static io.github.liaoalia.kiroacp.core.services.Fakes.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.liaoalia.kiroacp.core.acp.impl.KiroAgents;
import io.github.liaoalia.kiroacp.core.pure.permission.PolicyDecision;
import io.github.liaoalia.kiroacp.core.pure.text.Platform;

class HostServicesTest {

	private static final boolean WINDOWS = Platform.current() == Platform.WIN32;

	/** Answers as told and records what it was asked, on which thread and after which calls of the CLI. */
	private final class FakeExternal implements ExternalActions {
		final List<String> urls = new CopyOnWriteArrayList<>();
		final List<SignIn> signIns = new CopyOnWriteArrayList<>();
		final List<Thread> threads = new CopyOnWriteArrayList<>();
		final List<String> cliCallsAtSignIn = new CopyOnWriteArrayList<>();
		volatile CompletionStage<OpenResult> opening = CompletableFuture.completedFuture(OpenResult.OPENED);
		volatile CompletionStage<Integer> exit = CompletableFuture.completedFuture(0);

		@Override
		public CompletionStage<OpenResult> confirmAndOpenUrl(String host, String url) {
			threads.add(Thread.currentThread());
			urls.add(host + " <- " + url);
			return opening;
		}

		@Override
		public CompletionStage<Integer> runSignIn(SignIn signIn) {
			threads.add(Thread.currentThread());
			cliCallsAtSignIn.addAll(cliCalls());
			signIns.add(signIn);
			return exit;
		}
	}

	@TempDir
	Path dir;

	private final Fakes.RecordingLogger logger = new Fakes.RecordingLogger();
	private final Fakes.RecordingDisplay display = new Fakes.RecordingDisplay();
	private final FakeExternal external = new FakeExternal();
	private PermissionManager permissions;
	private FsService fs;
	private TerminalService terminal;
	private HostServices services;

	@BeforeEach
	void setUp() {
		Fakes.FakeWorkspace workspace = new Fakes.FakeWorkspace(dir);
		permissions = new PermissionManager();
		fs = new FsService(workspace, new DiskTextFiles(), logger);
		terminal = new TerminalService(new Fakes.FakeSettings(), workspace, display, logger);
		services = new HostServices(permissions, fs, terminal, external, logger);
	}

	@AfterEach
	void tearDown() {
		services.close();
		terminal.close();
		fs.close();
		permissions.close();
	}

	/** A stand-in for the CLI that notes its first argument in {@code calls.txt} next to it. */
	private String recordingCli() throws IOException {
		if (WINDOWS) {
			Path script = dir.resolve("kiro cli.cmd");
			Files.write(script, "@echo off\r\necho %~1>> \"%~dp0calls.txt\"\r\n".getBytes(StandardCharsets.US_ASCII));
			return script.toString();
		}
		Path script = dir.resolve("kiro cli");
		Files.write(script, "#!/bin/sh\necho \"$1\" >> \"$(dirname \"$0\")/calls.txt\"\n".getBytes(StandardCharsets.US_ASCII));
		assertTrue(script.toFile().setExecutable(true));
		return script.toString();
	}

	private List<String> cliCalls() {
		Path calls = dir.resolve("calls.txt");
		try {
			return Files.isRegularFile(calls) ? Files.readAllLines(calls).stream().map(String::strip).toList() : List.of();
		} catch (IOException e) {
			return List.of("unreadable: " + e);
		}
	}

	// ---------------------------------------------------------------------
	// Composition
	// ---------------------------------------------------------------------

	@Test
	void everyRequestGoesToItsService() throws Exception {
		Path file = dir.resolve("notes.txt");
		com.fasterxml.jackson.databind.node.ObjectNode path = Fakes.MAPPER.createObjectNode().put("sessionId", "s1").put("path", file.toString());

		assertEquals(json("{}"), get(services.writeTextFile(path.deepCopy().put("content", "one\ntwo\n"))));
		assertEquals("two\n", get(services.readTextFile(path.deepCopy().put("line", 2))).path("content").asText());

		JsonNode created = get(services
				.createTerminal(json("{\"sessionId\":\"s1\",\"command\":\"echo\",\"args\":[\"hello\"]}")));
		assertEquals(0, get(services.waitForTerminalExit(created)).path("exitCode").asInt());
		assertEquals("hello", get(services.terminalOutput(created)).path("output").asText().strip());
		assertEquals(json("{}"), get(services.killTerminal(created)));
		assertEquals(json("{}"), get(services.releaseTerminal(created)));
		assertTrue(terminal.activeIds().isEmpty());

		CompletableFuture<JsonNode> permission = services.requestPermission(json("""
				{"sessionId":"s1","toolCall":{"toolCallId":"call-1","kind":"edit"},
				 "options":[{"optionId":"ok","name":"Allow","kind":"allow_once"}]}"""), new CompletableFuture<>());
		assertFalse(permission.isDone());
		assertEquals("ok", permissions.decideRequest(permissions.pending().get(0).requestId(), PolicyDecision.ALLOW));
		assertEquals("ok", get(permission).path("outcome").path("optionId").asText());
	}

	// ---------------------------------------------------------------------
	// Opening a URL
	// ---------------------------------------------------------------------

	@Test
	void aUrlIsOpenedOnceTheUserAgreed() throws Exception {
		assertNull(get(services.openExternalUrl("https://Auth.Example.com:8443/oauth?state=1")));

		assertEquals(List.of("auth.example.com:8443 <- https://Auth.Example.com:8443/oauth?state=1"), external.urls);
		assertNotEquals(Thread.currentThread(), external.threads.get(0), "the UI is asked off the caller's thread");
		assertTrue(logger.has("info: opened external URL auth.example.com:8443"), logger.lines.toString());
	}

	@Test
	void aDeclinedOrFailedOpeningFailsTheRequest() throws Exception {
		external.opening = CompletableFuture.completedFuture(ExternalActions.OpenResult.DECLINED);
		assertEquals("User declined to open the URL", failure(services.openExternalUrl("https://example.com/")).getMessage());

		external.opening = CompletableFuture.completedFuture(ExternalActions.OpenResult.FAILED);
		assertEquals("The operating system could not open the URL",
				failure(services.openExternalUrl("https://example.com/")).getMessage());

		external.opening = CompletableFuture.completedFuture(null);
		assertEquals("User declined to open the URL", failure(services.openExternalUrl("https://example.com/")).getMessage());

		external.opening = null;
		assertEquals("User declined to open the URL", failure(services.openExternalUrl("https://example.com/")).getMessage());

		external.opening = CompletableFuture.failedFuture(new IllegalStateException("no display"));
		assertEquals("no display", failure(services.openExternalUrl("https://example.com/")).getMessage());
		assertFalse(logger.has("opened external URL"));
	}

	@Test
	void theUserIsShownTheHostOfTheUrl() {
		assertEquals("example.com", HostServices.hostOf("https://example.com/path?x=1#y"));
		assertEquals("example.com", HostServices.hostOf("HTTPS://EXAMPLE.com:443/"));
		assertEquals("example.com", HostServices.hostOf("http://example.com:80"));
		assertEquals("example.com:8080", HostServices.hostOf("http://example.com:8080/"));
		assertEquals("localhost:3000", HostServices.hostOf("http://localhost:3000/callback"));
		assertEquals("[::1]:8080", HostServices.hostOf("http://[::1]:8080/"));
		// What comes before the @ is not where the browser goes.
		assertEquals("evil.example", HostServices.hostOf("https://good.example@evil.example/login"));
		assertEquals("evil.example", HostServices.hostOf("https://good.example:pw@evil.example/"));
		// Nothing that is a host: the whole URL is shown.
		assertEquals("mailto:someone@example.com", HostServices.hostOf("mailto:someone@example.com"));
		assertEquals("file:///etc/passwd", HostServices.hostOf("file:///etc/passwd"));
		assertEquals("not a url", HostServices.hostOf("not a url"));
		assertEquals("https://exa mple.com/", HostServices.hostOf("https://exa mple.com/"));
		assertEquals("", HostServices.hostOf(""));
		assertEquals("Kiro wants to open example.com in your browser.", HostServices.openUrlQuestion("example.com"));
	}

	// ---------------------------------------------------------------------
	// Terminal sign-in
	// ---------------------------------------------------------------------

	@Test
	void anAgentsSignInMethodExtendsTheAgentInvocation() throws Exception {
		String cli = recordingCli();
		JsonNode method = json("""
				{"id":"kiro-login","name":"Kiro login","type":"terminal","args":["--login"],
				 "env":{"KIRO_REGION":"eu","IGNORED":7}}""");

		assertNull(get(services.runTerminalAuth(method, cli, List.of("acp", "--agent-engine=v3"))));

		ExternalActions.SignIn signIn = external.signIns.get(0);
		assertEquals(HostServices.SIGN_IN_TERMINAL_NAME, signIn.title());
		assertEquals(cli, signIn.command());
		assertEquals(List.of("acp", "--agent-engine=v3", "--login"), signIn.args());
		assertEquals(Map.of("KIRO_REGION", "eu"), signIn.env());
		assertEquals(WINDOWS, signIn.launch().viaShell(), "a .cmd launcher runs through cmd.exe");
		if (!WINDOWS) {
			assertEquals(cli, signIn.launch().command());
			assertEquals(signIn.args(), signIn.launch().args());
		}
		assertEquals(List.of(), cliCalls(), "only the fallback signs out first");
		assertNotEquals(Thread.currentThread(), external.threads.get(0));
		assertTrue(logger.has("info: started terminal auth \"Kiro login\" (kiro-login): " + cli + " acp --agent-engine=v3 --login"),
				logger.lines.toString());
		assertTrue(logger.has("info: terminal auth finished (exit 0)"));
	}

	/** Reference D25: signed out means a logout, then one fresh sign-in. */
	@Test
	void theFallbackLoginSignsOutFirst() throws Exception {
		String cli = recordingCli();

		assertNull(get(services.runTerminalAuth(KiroAgents.fallbackLoginMethod(), cli, List.of())));

		assertEquals(List.of("logout"), external.cliCallsAtSignIn, "the logout had ended when the sign-in started");
		assertEquals(List.of("login"), external.signIns.get(0).args());
		assertEquals(Map.of(), external.signIns.get(0).env());
		assertTrue(logger.has("info: signed out before the fresh sign-in"), logger.lines.toString());
	}

	@Test
	void aLogoutThatFailsDoesNotStopTheSignIn() throws Exception {
		String missing = dir.resolve(WINDOWS ? "no-such-cli.exe" : "no-such-cli").toString();

		assertNull(get(services.runTerminalAuth(KiroAgents.fallbackLoginMethod(), missing, List.of())));

		assertEquals(1, external.signIns.size());
		assertTrue(logger.has("warn: logout before sign-in failed (ignored): "), logger.lines.toString());
	}

	@Test
	void aLogoutThatHangsIsStoppedAndTheSignInGoesOn() throws Exception {
		Path script = dir.resolve(WINDOWS ? "slow.cmd" : "slow");
		Files.write(script, (WINDOWS ? "@ping -n 60 127.0.0.1 >nul\r\n" : "#!/bin/sh\nexec sleep 60\n")
				.getBytes(StandardCharsets.US_ASCII));
		assertTrue(WINDOWS || script.toFile().setExecutable(true));
		HostServices impatient = new HostServices(permissions, fs, terminal, external, logger, 500);
		try {
			assertNull(get(impatient.runTerminalAuth(KiroAgents.fallbackLoginMethod(), script.toString(), List.of())));
		} finally {
			impatient.close();
		}

		assertEquals(1, external.signIns.size());
		assertTrue(logger.has("warn: logout before sign-in failed (ignored): timed out"), logger.lines.toString());
		String sleeper = WINDOWS ? "ping" : "sleep";
		Fakes.await(() -> sleepers(sleeper).isEmpty(), "the hanging logout to be gone: " + sleepers(sleeper));
	}

	private static List<ProcessHandle> sleepers(String name) {
		List<ProcessHandle> found = new ArrayList<>();
		ProcessHandle.current().descendants().filter(handle -> handle.info().command()
				.map(command -> command.toLowerCase(Locale.ROOT).contains(name)).orElse(false)).forEach(found::add);
		return found;
	}

	@Test
	void theSignInsExitCodeDecides() throws Exception {
		JsonNode method = json("{\"id\":\"kiro-login\",\"name\":\"Kiro login\",\"type\":\"terminal\"}");

		external.exit = CompletableFuture.completedFuture(3);
		assertEquals("The sign-in command exited with code 3; see the Kiro login terminal.",
				failure(services.runTerminalAuth(method, "kiro-cli", List.of("acp"))).getMessage());

		// The terminal went away without a code, or the user said they are done.
		external.exit = CompletableFuture.completedFuture(null);
		assertNull(get(services.runTerminalAuth(method, "kiro-cli", List.of("acp"))));
		assertTrue(logger.has("info: terminal auth finished (terminal closed)"));

		external.exit = CompletableFuture.failedFuture(new IOException("no terminal"));
		assertEquals("no terminal", failure(services.runTerminalAuth(method, "kiro-cli", List.of("acp"))).getMessage());

		external.exit = null;
		assertEquals("The sign-in terminal could not be started",
				failure(services.runTerminalAuth(method, "kiro-cli", List.of("acp"))).getMessage());

		CompletableFuture<Integer> running = new CompletableFuture<>();
		external.exit = running;
		CompletableFuture<Void> pending = services.runTerminalAuth(method, "kiro-cli", List.of("acp"));
		Fakes.await(() -> external.signIns.size() == 5, "the sign-in to start");
		assertFalse(pending.isDone(), "the sign-in lasts as long as its terminal");
		running.complete(0);
		assertNull(get(pending));
		assertEquals(List.of("acp"), external.signIns.get(4).args());
	}

	@Test
	void theArgumentsAndVariablesOfASignInComeFromTheMethod() {
		assertEquals(List.of("acp", "--login", "7"),
				HostServices.signInArgs(List.of("acp"), json("{\"args\":[\"--login\",7]}")));
		assertEquals(List.of("acp"), HostServices.signInArgs(List.of("acp"), json("{\"args\":null}")));
		assertEquals(List.of(), HostServices.signInArgs(List.of(), json("{}")));
		assertEquals(Map.of("A", "1"), HostServices.signInEnv(json("{\"env\":{\"A\":\"1\",\"B\":null}}")));
		assertEquals(Map.of(), HostServices.signInEnv(json("{\"env\":[{\"name\":\"A\",\"value\":\"1\"}]}")));
	}

	@Test
	void withoutAUiTheAgentIsToldTheseAreNotSupported() {
		HostServices headless = new HostServices(permissions, fs, terminal, null, null);
		try {
			assertNull(headless.openExternalUrl("https://example.com/"));
			assertNull(headless.runTerminalAuth(KiroAgents.fallbackLoginMethod(), "kiro-cli", List.of()));
		} finally {
			headless.close();
		}
	}

	@Test
	void afterCloseTheUiIsNotAskedAnyMore() throws Exception {
		services.close();
		assertEquals("The host services are closed", failure(services.openExternalUrl("https://example.com/")).getMessage());
		assertTrue(external.urls.isEmpty());
	}
}
