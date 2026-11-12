package io.github.liaoalia.kiroacp.ui.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.liaoalia.kiroacp.core.acp.process.AgentCommandLine;
import io.github.liaoalia.kiroacp.core.acp.process.Platform;
import io.github.liaoalia.kiroacp.core.services.ExternalActions.OpenResult;
import io.github.liaoalia.kiroacp.core.services.ExternalActions.SignIn;
import io.github.liaoalia.kiroacp.core.services.HostServices;

/**
 * No dialog, no browser and no terminal window: the prompts, the opener and
 * the launcher are fakes, and the sign-in command "ends" when the test writes
 * the file its script would have written.
 */
class EclipseExternalActionsTest {

	private static final long WAIT_SECONDS = 30;
	/** The poll is switched off where a test must see what happens without it. */
	private static final long NEVER = TimeUnit.HOURS.toMillis(1);

	/** The terminal program: records how it was started and ends when the test says so. */
	private static final class FakeLauncher implements EclipseExternalActions.TerminalLauncher {

		final List<SignInTerminal.Plan> plans = new ArrayList<>();
		final List<Map<String, String>> envs = new ArrayList<>();
		final CompletableFuture<Integer> exit = new CompletableFuture<>();
		IOException failure;

		@Override
		public CompletionStage<Integer> start(SignInTerminal.Plan plan, Map<String, String> env) throws IOException {
			plans.add(plan);
			envs.add(env);
			if (failure != null) {
				throw failure;
			}
			return exit;
		}
	}

	private final FakePrompts prompts = new FakePrompts();
	private final List<String> opened = new ArrayList<>();
	private final FakeLauncher launcher = new FakeLauncher();
	private boolean openerWorks = true;

	@AfterEach
	void endSignInsAndRemoveScripts() {
		// Ends what a test left waiting, and with it the thread that looks for the exit code.
		prompts.told.forEach(notice -> notice.acknowledged.complete(null));
		launcher.plans.forEach(SignInTerminal::discard);
	}

	// ---------------------------------------------------------------------
	// URLs
	// ---------------------------------------------------------------------

	@Test
	void aUrlIsOpenedOnlyAfterTheUserAgreed() throws Exception {
		String url = "https://app.kiro.dev/signin?code=ABCD-1234&next=%2Fhome#top";

		CompletableFuture<OpenResult> result = actions(Platform.WINDOWS, NEVER).confirmAndOpenUrl("app.kiro.dev", url)
				.toCompletableFuture();

		assertFalse(result.isDone(), "the caller is not kept waiting for the user");
		assertEquals(List.of(), opened, "nothing is opened before the answer");
		assertEquals(1, prompts.asked.size());
		Prompts.Question question = prompts.asked.get(0).question();
		assertEquals(HostServices.openUrlQuestion("app.kiro.dev"), question.message());
		assertEquals("Kiro wants to open app.kiro.dev in your browser.", question.message());
		assertEquals(url, question.detail(), "the whole URL is in view");
		assertEquals("Open", question.accept());
		assertFalse(question.warning());

		prompts.asked.get(0).answer().complete(Boolean.TRUE);

		assertEquals(OpenResult.OPENED, result.get());
		assertEquals(List.of(url), opened, "the URL as it was given");
	}

	@Test
	void aRefusalOpensNothing() throws Exception {
		for (Boolean answer : new Boolean[] { Boolean.FALSE, null }) {
			CompletableFuture<OpenResult> result = actions(Platform.WINDOWS, NEVER)
					.confirmAndOpenUrl("example.com", "http://example.com/").toCompletableFuture();
			prompts.asked.get(prompts.asked.size() - 1).answer().complete(answer);

			assertEquals(OpenResult.DECLINED, result.get());
		}
		assertEquals(List.of(), opened);
	}

	@Test
	void whatIsNotAWebAddressIsRefusedWithoutAQuestion() throws Exception {
		List<String> urls = new ArrayList<>(List.of("file:///C:/Windows/System32/calc.exe", "file://server/share/run.bat",
				"javascript:alert(1)", "mailto:someone@example.com", "ftp://example.com/file", "vscode://extension/install",
				"C:\\Windows\\System32\\calc.exe", "calc.exe", "example.com", "//example.com/path", "https:///no-host",
				"https://", "http://exa mple.com/", " https://example.com/", "https://example.com/a b", ""));
		urls.add(null);

		for (String url : urls) {
			assertEquals(OpenResult.FAILED,
					actions(Platform.WINDOWS, NEVER).confirmAndOpenUrl("example.com", url).toCompletableFuture().get(), String.valueOf(url));
		}
		assertEquals(List.of(), prompts.asked, "the user is not even asked");
		assertEquals(List.of(), opened);
	}

	@Test
	void httpInAnyCaseIsAWebAddress() {
		assertTrue(EclipseExternalActions.isWebUrl("HTTPS://Example.COM/path?q=1"));
		assertTrue(EclipseExternalActions.isWebUrl("http://localhost:8080/callback"));
		assertTrue(EclipseExternalActions.isWebUrl("http://127.0.0.1:49152/oauth?state=x"));
	}

	@Test
	void aSystemThatCannotOpenTheUrlIsAFailure() throws Exception {
		openerWorks = false;
		CompletableFuture<OpenResult> result = actions(Platform.WINDOWS, NEVER).confirmAndOpenUrl("example.com", "https://example.com/")
				.toCompletableFuture();
		prompts.asked.get(0).answer().complete(Boolean.TRUE);

		assertEquals(OpenResult.FAILED, result.get());
		assertEquals(List.of("https://example.com/"), opened, "it was tried");
	}

	@Test
	void anOpenerThatThrowsIsAFailureToo() throws Exception {
		EclipseExternalActions actions = new EclipseExternalActions(prompts, null, url -> {
			throw new IllegalStateException("no browser");
		}, launcher, Platform.WINDOWS, Map.of(), program -> false, NEVER);
		CompletableFuture<OpenResult> result = actions.confirmAndOpenUrl("example.com", "https://example.com/")
				.toCompletableFuture();
		prompts.asked.get(0).answer().complete(Boolean.TRUE);

		assertEquals(OpenResult.FAILED, result.get());
	}

	// ---------------------------------------------------------------------
	// Sign-in
	// ---------------------------------------------------------------------

	@Test
	void theSignInStartsATerminalOnItsScriptAndTellsTheUser() throws Exception {
		SignIn signIn = signIn(Map.of("KIRO_REGION", "eu"));

		CompletableFuture<Integer> result = actions(Platform.WINDOWS, NEVER).runSignIn(signIn).toCompletableFuture();

		assertFalse(result.isDone());
		assertEquals(1, launcher.plans.size());
		SignInTerminal.Plan plan = launcher.plans.get(0);
		assertEquals(SignInTerminal.windowsCommand("Kiro login", plan.script(), Map.of("ComSpec", "C:\\Windows\\system32\\cmd.exe")),
				plan.argv());
		assertEquals(SignInTerminal.windowsScript(signIn, plan.status()), Files.readString(plan.script()));
		assertEquals(List.of(Map.of("KIRO_REGION", "eu")), launcher.envs, "the method's variables reach the terminal");
		assertEquals(1, prompts.told.size());
		FakePrompts.Told notice = prompts.told.get(0);
		assertEquals("Kiro login", notice.title);
		assertEquals(HostServices.SIGN_IN_HINT, notice.message);
		assertEquals(EclipseExternalActions.SIGN_IN_DONE, notice.done);
		assertEquals(0, notice.dismissals);
	}

	@Test
	void theExitCodeTheScriptLeavesBehindCompletesTheSignIn() throws Exception {
		for (int code : new int[] { 0, 1 }) {
			CompletableFuture<Integer> result = actions(Platform.WINDOWS, 10).runSignIn(signIn(Map.of())).toCompletableFuture();
			SignInTerminal.Plan plan = launcher.plans.get(launcher.plans.size() - 1);
			assertFalse(result.isDone());

			leaveExitCode(plan, code);

			assertEquals(Integer.valueOf(code), result.get(WAIT_SECONDS, TimeUnit.SECONDS));
			assertEquals(1, prompts.told.get(prompts.told.size() - 1).dismissals, "the notice is taken away");
			assertFalse(Files.exists(plan.directory()), "the script has run to its end and is removed");
		}
	}

	@Test
	void theUserSayingTheyAreDoneCompletesTheSignInWithoutACode() throws Exception {
		CompletableFuture<Integer> result = actions(Platform.MAC, NEVER).runSignIn(signIn(Map.of())).toCompletableFuture();
		SignInTerminal.Plan plan = launcher.plans.get(0);
		assertEquals(List.of("open", "-a", "Terminal", plan.script().toString()), plan.argv());

		prompts.told.get(0).acknowledged.complete(null);

		assertTrue(result.isDone());
		assertNull(result.get(), "no exit code: the caller reconnects and sees");
		assertTrue(Files.exists(plan.script()), "the command may still be running from its script");
	}

	@Test
	void anExitCodeThatIsAlreadyThereBeatsTheUsersWord() throws Exception {
		CompletableFuture<Integer> result = actions(Platform.LINUX, NEVER).runSignIn(signIn(Map.of())).toCompletableFuture();
		leaveExitCode(launcher.plans.get(0), 2);

		prompts.told.get(0).acknowledged.complete(null);

		assertEquals(Integer.valueOf(2), result.get());
	}

	@Test
	void aTerminalProgramThatCannotBeStartedFailsTheSignIn() {
		launcher.failure = new IOException("Cannot run program \"cmd.exe\"");

		CompletableFuture<Integer> result = actions(Platform.WINDOWS, NEVER).runSignIn(signIn(Map.of())).toCompletableFuture();

		ExecutionException failure = assertThrows(ExecutionException.class, result::get);
		assertTrue(failure.getCause().getMessage().contains("Cannot run program"), failure.getCause().getMessage());
		assertEquals(List.of(), prompts.told, "nothing to tell the user to finish");
		assertFalse(Files.exists(launcher.plans.get(0).directory()), "the script that never ran is removed");
	}

	@Test
	void aDesktopWithoutATerminalProgramFailsTheSignIn() {
		EclipseExternalActions actions = new EclipseExternalActions(prompts, null, opened::add, launcher, Platform.LINUX,
				Map.of(), program -> false, NEVER);

		CompletableFuture<Integer> result = actions.runSignIn(signIn(Map.of())).toCompletableFuture();

		ExecutionException failure = assertThrows(ExecutionException.class, result::get);
		assertTrue(failure.getCause().getMessage().contains("No terminal program found"), failure.getCause().getMessage());
		assertEquals(List.of(), launcher.plans);
		assertEquals(List.of(), prompts.told);
	}

	@Test
	void aTerminalProgramThatEndsBadlyBeforeTheCommandRanFailsTheSignIn() {
		CompletableFuture<Integer> result = actions(Platform.LINUX, NEVER).runSignIn(signIn(Map.of())).toCompletableFuture();

		launcher.exit.complete(1);

		ExecutionException failure = assertThrows(ExecutionException.class, result::get);
		assertTrue(failure.getCause().getMessage().contains("exit code 1"), failure.getCause().getMessage());
		assertEquals(1, prompts.told.get(0).dismissals);
	}

	@Test
	void aTerminalProgramThatReturnsAtOnceSaysNothingAboutTheSignIn() throws Exception {
		CompletableFuture<Integer> result = actions(Platform.MAC, 10).runSignIn(signIn(Map.of())).toCompletableFuture();

		// "open" is done as soon as Terminal has the script.
		launcher.exit.complete(0);
		assertFalse(result.isDone());
		assertEquals(0, prompts.told.get(0).dismissals);

		leaveExitCode(launcher.plans.get(0), 0);
		assertEquals(Integer.valueOf(0), result.get(WAIT_SECONDS, TimeUnit.SECONDS));
	}

	@Test
	void aTerminalProgramThatEndsAfterTheCommandReportsTheCommandsCode() throws Exception {
		CompletableFuture<Integer> result = actions(Platform.LINUX, NEVER).runSignIn(signIn(Map.of())).toCompletableFuture();
		leaveExitCode(launcher.plans.get(0), 7);

		// A terminal that passes the failure of its program on: the command's own code is the news.
		launcher.exit.complete(7);

		assertEquals(Integer.valueOf(7), result.get());
	}

	// ---------------------------------------------------------------------

	private EclipseExternalActions actions(Platform platform, long pollMillis) {
		Predicate<String> onPath = "xterm"::equals;
		return new EclipseExternalActions(prompts, null, url -> {
			opened.add(url);
			return openerWorks;
		}, launcher, platform, Map.of("ComSpec", "C:\\Windows\\system32\\cmd.exe"), onPath, pollMillis);
	}

	private static SignIn signIn(Map<String, String> env) {
		List<String> args = List.of("login", "--use-device-flow");
		return new SignIn(HostServices.SIGN_IN_TERMINAL_NAME, "kiro-cli", args,
				new AgentCommandLine("kiro-cli", args, false), env);
	}

	/** What the last line of the script does: the file appears complete. */
	private static void leaveExitCode(SignInTerminal.Plan plan, int code) throws IOException {
		Path partial = plan.status().resolveSibling(SignInTerminal.STATUS_FILE + ".tmp");
		Files.writeString(partial, code + System.lineSeparator());
		Files.move(partial, plan.status(), StandardCopyOption.ATOMIC_MOVE);
	}
}
