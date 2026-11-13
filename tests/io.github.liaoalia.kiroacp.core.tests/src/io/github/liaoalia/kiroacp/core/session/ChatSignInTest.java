package io.github.liaoalia.kiroacp.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.liaoalia.kiroacp.core.acp.api.KiroAgent;
import io.github.liaoalia.kiroacp.core.model.ConnectionStatus;
import io.github.liaoalia.kiroacp.core.model.FromWebview;
import io.github.liaoalia.kiroacp.core.model.NoticeView;
import io.github.liaoalia.kiroacp.core.model.ToWebview;
import io.github.liaoalia.kiroacp.core.model.UiJson;
import io.github.liaoalia.kiroacp.core.services.ExternalActions;
import io.github.liaoalia.kiroacp.core.services.HostServices;
import io.github.liaoalia.kiroacp.core.session.Harness.Client;
import io.github.liaoalia.kiroacp.core.session.auth.SignIn;

/**
 * Signing in (reference D25; e2e F14), against the fake agent. The host's
 * sign-in "terminal" is a fake that writes the command down and ends with the
 * exit code the test chose: no terminal window and no CLI is started.
 */
class ChatSignInTest {

	private static final String PAST_1 = "past-1";
	private static final String PAST_2 = "past-2";

	@TempDir
	Path dir;

	@Test
	void theSignInButtonRunsTheMethodAndStartsTheAgentAfresh() throws Exception {
		try (Harness h = Harness.launch(dir, "--auth-required")) {
			SignIn.install(h.controller);
			Client view = h.attach("view", AttachOptions.ownGroup("view"));
			view.send(new FromWebview.Ready());
			view.page.await(ToWebview.Connection.class, 0,
					c -> c.connection().status() == ConnectionStatus.AUTH_REQUIRED, "the sign-in state");
			assertEquals(1, h.launches.size());

			view.send(new FromWebview.Login("kiro-login"));

			// The fake host's sign-in "terminal" exits with 0 at once; the agent is then started again.
			h.await(() -> h.launches.size() == 2, "the agent to be started again after signing in");
			// The agent's own method extends the agent's invocation (reference D25, addendum 0.3.5).
			ExternalActions.SignIn ran = h.external.signIns.get(0);
			assertEquals(HostServices.SIGN_IN_TERMINAL_NAME, ran.title());
			assertEquals("--login", ran.args().get(ran.args().size() - 1), ran.args().toString());
			assertTrue(ran.args().contains("acp"), ran.args().toString());
			assertEquals(Map.of(), ran.env());
			assertFalse(h.logger.has("logout before sign-in"), "only the fallback signs out first: " + h.logger.lines);
			assertFalse(h.logger.has("signed out before"), h.logger.lines.toString());
			assertEquals(1, h.external.signIns.size());
		}
	}

	/** Without an exit code (the user said they are done, or closed the window) the agent is reconnected all the same. */
	@Test
	void aSignInThatEndsWithoutAnExitCodeReconnects() throws Exception {
		try (Harness h = Harness.launch(dir, "--auth-required")) {
			SignIn signIn = SignIn.install(h.controller);
			h.external.signInExit = null;
			Client view = h.attach("view", AttachOptions.ownGroup("view"));
			view.send(new FromWebview.Ready());
			view.page.await(ToWebview.Connection.class, 0,
					c -> c.connection().status() == ConnectionStatus.AUTH_REQUIRED, "the sign-in state");

			// No method named: the agent's terminal method is the one.
			h.get(signIn.login(null));

			assertEquals(2, h.launches.size());
			assertEquals("--login", h.external.signIns.get(0).args().get(h.external.signIns.get(0).args().size() - 1));
		}
	}

	@Test
	void aSignInThatFailsSaysSoAndLeavesTheAgentAlone() throws Exception {
		try (Harness h = Harness.launch(dir, "--auth-required")) {
			SignIn signIn = SignIn.install(h.controller);
			h.external.signInExit = 3;
			Client view = h.attach("view", AttachOptions.ownGroup("view"));
			view.send(new FromWebview.Ready());
			view.page.await(ToWebview.Connection.class, 0,
					c -> c.connection().status() == ConnectionStatus.AUTH_REQUIRED, "the sign-in state");

			int mark = view.mark();
			view.send(new FromWebview.Login("kiro-login"));
			ToWebview.Notice failed = view.page.await(ToWebview.Notice.class, mark, n -> true, "the failure");
			assertEquals(NoticeView.Severity.ERROR, failed.notice().severity());
			assertEquals("Sign-in failed: The sign-in command exited with code 3; see the Kiro login terminal.",
					failed.notice().text());

			int second = view.mark();
			h.get(signIn.login("no-such-method"));
			ToWebview.Notice unknown = view.page.await(ToWebview.Notice.class, second, n -> true, "the refusal");
			assertEquals("Sign-in failed: Unknown auth method 'no-such-method'", unknown.notice().text());

			h.scheduler.flush();
			assertEquals(1, h.launches.size(), "the agent was not replaced");
			assertEquals(1, h.external.signIns.size(), "nothing ran for a method nobody offers");
			assertEquals(ConnectionStatus.AUTH_REQUIRED, h.ask(() -> h.controller.connection().status()));
		}
	}

	/** e2e F14.1. */
	@Test
	void anExpiredTokenOffersTheLoginFallbackWhichSignsOutSignsInAndReconnects() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			SignIn.install(h.controller);
			Client view = h.openView("view");
			JsonNode offered = view.snapshot().at("/connection/authMethods");
			assertTrue(offered.isMissingNode() || offered.isNull(), "no auth methods while signed in: " + offered);
			String expired = view.sessionId();

			int mark = view.mark();
			assertEquals("error", view.turn("/scenario auth-expired").stopReason());
			h.scheduler.flush();
			JsonNode state = view.snapshot();
			assertEquals("auth_required", state.at("/connection/status").asText());
			assertTrue(state.at("/connection/message").asText().toLowerCase().contains("not logged in"),
					"the banner carries the agent's reason: " + state.path("connection"));
			assertEquals(1, state.at("/connection/authMethods").size(), "one login method offered");
			assertEquals("kiro-cli-login", state.at("/connection/authMethods/0/id").asText());
			assertTrue(state.at("/connection/authMethods/0/terminal").asBoolean());
			assertFalse(state.path("turnActive").asBoolean());
			assertEquals(List.of(), state.path("notices").findValuesAsText("text"),
					"no 'Prompt failed' notice next to the sign-in banner");

			// The banner's button, in this very state (no ready agent).
			view.send(new FromWebview.Login("kiro-cli-login"));
			view.page.await(ToWebview.Connection.class, mark,
					c -> c.connection().status() == ConnectionStatus.READY, "the reconnect after signing in");
			h.await(() -> view.sessionId() != null && !view.sessionId().equals(expired), "a session in the view again");
			view.page.await(ToWebview.Notice.class, mark, n -> n.notice().text().startsWith("Could not restore"),
					"the word about the session that is gone");
			h.scheduler.flush();

			// Signed out means a logout, then one fresh `<cli> login` on its own: no `acp`, no token refresh.
			assertEquals(1, h.external.signIns.size());
			ExternalActions.SignIn ran = h.external.signIns.get(0);
			assertEquals(List.of("login"), ran.args());
			assertEquals(HostServices.SIGN_IN_TERMINAL_NAME, ran.title());
			// The CLI of this harness is `node`, which has no `logout`: the attempt is what counts
			// here (HostServicesTest has the logout that works and the order of the two).
			List<String> log = List.copyOf(h.logger.lines);
			int logout = indexOf(log, "logout before sign-in failed (ignored)");
			int started = indexOf(log, "started terminal auth \"Log in to Kiro\" (kiro-cli-login)");
			assertTrue(logout >= 0 && started > logout, "the logout came first: " + log);
			assertEquals(2, h.launches.size());

			// The agent of the fake keeps live sessions in memory only, so the conversation
			// could not be loaded back: the view shows a fresh session and says which one is gone.
			JsonNode after = view.snapshot();
			assertEquals("ready", after.at("/connection/status").asText());
			assertEquals(List.of(view.sessionId()), after.path("sessions").findValuesAsText("id"));
			List<String> notices = after.path("notices").findValuesAsText("text");
			assertEquals(1, notices.size(), notices.toString());
			assertTrue(notices.get(0).startsWith("Could not restore session \"/scenario auth-expired\" (" + expired + "): "),
					notices.toString());
			assertFalse(h.ask(h.controller::sessionIds).contains(expired));
		}
	}

	/** e2e F14.2. */
	@Test
	void signingInAgainBringsBackEverySessionOfAViewTheShownOneInPlaceAndOpensNoExtraOne() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			SignIn signIn = SignIn.install(h.controller);
			Client a = h.openView("a");
			Client b = h.openView("b");
			String emptyA = a.sessionId();
			String lostB = b.sessionId();
			assertNotEquals(emptyA, lostB, "each view has a session of its own");

			// View A: two past sessions next to its empty first session, past-1 shown.
			h.clock.addAndGet(1000);
			assertEquals(PAST_2, h.get(h.controller.openPastSession(PAST_2, "Add retry to fetch", a.handle)));
			h.clock.addAndGet(1000);
			assertEquals(PAST_1, h.get(h.controller.openPastSession(PAST_1, "Investigate flaky CI", a.handle)));
			h.scheduler.flush();
			assertEquals("a", h.ask(() -> h.controller.sessionGroup(PAST_2)));
			assertEquals(PAST_1, a.sessionId());
			List<String> conversationsA = new ArrayList<>(a.snapshot().path("sessions").findValuesAsText("id"));
			assertTrue(conversationsA.remove(emptyA));
			assertEquals(List.of(PAST_1, PAST_2), conversationsA, "the more recent one first");

			// View B: a conversation the new process will not know.
			h.clock.addAndGet(1000);
			assertEquals("end_turn", b.turn("/scenario chat").stopReason());
			h.clock.addAndGet(1000);
			a.handle.focus();
			h.scheduler.flush();
			assertEquals(PAST_1, h.ask(h.controller::focusedSessionId));

			h.get(signIn.login(null));
			h.scheduler.flush();

			assertEquals(ConnectionStatus.READY, h.ask(() -> h.controller.connection().status()));
			assertEquals(2, h.launches.size());
			assertEquals(List.of("login"), h.external.signIns.get(0).args(), "signed in: the fallback is what a login runs");
			assertEquals(PAST_1, a.sessionId(), "view A shows past-1 again");
			JsonNode stateA = a.snapshot();
			assertEquals(conversationsA, stateA.path("sessions").findValuesAsText("id"),
					"view A lists its sessions with a conversation in their old order, not the empty one and no new one");
			for (JsonNode session : stateA.path("sessions")) {
				assertEquals("idle", session.path("status").asText(), session.toString());
			}
			assertTrue(texts(stateA).contains("Why is CI flaky?"), "past-1's conversation is back: " + texts(stateA));
			assertEquals(List.of(), stateA.path("notices").findValuesAsText("text"), "view A shows no restore notice");
			assertEquals(stateA.path("items"), a.page.model().path("items"), "what the page built is the transcript");
			JsonNode hidden = h.ask(() -> UiJson.toJson(new ToWebview.State(h.controller.snapshot(PAST_2))).get("state"));
			assertTrue(texts(hidden).contains("Add a retry to the fetch helper."),
					"past-2 (not shown) is restored too, not just listed: " + texts(hidden));
			List<String> live = h.ask(h.controller::sessionIds);
			assertFalse(live.contains(emptyA), "the empty session was not loaded back");
			assertEquals(PAST_1, h.ask(h.controller::focusedSessionId), "focus is back on the session view A shows");

			JsonNode stateB = b.snapshot();
			assertNotNull(b.sessionId());
			assertNotEquals(lostB, b.sessionId(), "view B shows a fresh session in place of the one that could not be loaded");
			assertEquals(List.of(b.sessionId()), stateB.path("sessions").findValuesAsText("id"), "view B lists only its fresh session");
			List<JsonNode> warnings = new ArrayList<>();
			stateB.path("notices").forEach(warnings::add);
			assertEquals(1, warnings.size(), warnings.toString());
			assertEquals("warning", warnings.get(0).path("severity").asText());
			assertTrue(warnings.get(0).path("text").asText().startsWith("Could not restore session \"/scenario chat\" (" + lostB + "): "),
					"view B names the session it could not restore: " + warnings);
			assertFalse(live.contains(lostB), "the session that could not be loaded is gone");
			assertEquals(3, live.size(), live.toString());
		}
	}

	/** Reference D25 (0.3.11): a tab closed during the restore gets nothing back. */
	@Test
	void aViewClosedWhileTheSessionsComeBackGetsNothingBack() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			SignIn signIn = SignIn.install(h.controller);
			Client a = h.openView("a");
			Client b = h.openView("b");
			assertEquals(PAST_1, h.get(h.controller.openPastSession(PAST_1, null, a.handle)));
			String ofB = b.sessionId();
			assertEquals("end_turn", b.turn("/scenario chat").stopReason());
			a.handle.focus();
			h.scheduler.flush();

			// View B is closed while the first session (the focused one, of view A) is loaded.
			h.agentWrapper = real -> (KiroAgent) Proxy.newProxyInstance(KiroAgent.class.getClassLoader(),
					new Class<?>[] { KiroAgent.class }, (proxy, method, args) -> {
						if (method.getName().equals("loadSession") && PAST_1.equals(args[0])) {
							b.handle.close();
						}
						try {
							return method.invoke(real, args);
						} catch (InvocationTargetException e) {
							throw e.getCause();
						}
					});

			h.get(signIn.login(null));
			h.scheduler.flush();

			assertEquals(List.of(PAST_1), h.ask(h.controller::sessionIds), "nothing for the closed view, not even a fresh session");
			assertTrue(h.logger.has("session " + PAST_1 + " restored"), h.logger.lines.toString());
			assertFalse(h.logger.has("session " + ofB + " restoring"), "no session/load into a group that was closed");
			assertEquals(PAST_1, a.sessionId());
		}
	}

	@Test
	void anAgentThatCannotLoadSessionsGivesFreshOnesAfterTheSignIn() throws Exception {
		try (Harness h = Harness.launch(dir, "--engine", "v2")) {
			SignIn signIn = SignIn.install(h.controller);
			Client view = h.openView("view");
			String before = view.sessionId();
			assertEquals("end_turn", view.turn("/scenario chat").stopReason());

			h.get(signIn.login(null));
			h.scheduler.flush();

			assertTrue(h.logger.has("the agent cannot load sessions: 1 session(s) not restored"), h.logger.lines.toString());
			assertNotNull(view.sessionId());
			assertNotEquals(before, view.sessionId());
			assertEquals(List.of(), view.snapshot().path("notices").findValuesAsText("text"));
			assertEquals(List.of(view.sessionId()), h.ask(h.controller::sessionIds));
		}
	}

	/** A plain restart is not a sign-in: the sessions end with the old process. */
	@Test
	void aRestartWithoutASignInBringsNothingBack() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			Client view = h.openView("view");
			assertEquals(PAST_1, h.get(h.controller.openPastSession(PAST_1, null, view.handle)));

			h.get(h.controller.restart());
			h.scheduler.flush();

			assertFalse(h.ask(h.controller::sessionIds).contains(PAST_1));
			assertNotNull(view.sessionId());
			assertEquals(List.of(view.sessionId()), view.snapshot().path("sessions").findValuesAsText("id"));
		}
	}

	private static int indexOf(List<String> lines, String text) {
		for (int i = 0; i < lines.size(); i++) {
			if (lines.get(i).contains(text)) {
				return i;
			}
		}
		return -1;
	}

	/** The text of every message of the state, in order. */
	private static List<String> texts(JsonNode state) {
		List<String> out = new ArrayList<>();
		for (JsonNode item : state.path("items")) {
			if (item.has("message")) {
				StringBuilder text = new StringBuilder();
				for (JsonNode segment : item.get("message").path("segments")) {
					text.append(segment.path("text").asText(""));
				}
				out.add(text.toString());
			}
		}
		return out;
	}
}
