package io.github.liaoalia.kiroacp.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.liaoalia.kiroacp.core.acp.api.AcpException;
import io.github.liaoalia.kiroacp.core.acp.api.KiroAgent;
import io.github.liaoalia.kiroacp.core.model.ConnectionStatus;
import io.github.liaoalia.kiroacp.core.model.FromWebview;
import io.github.liaoalia.kiroacp.core.model.MessageRole;
import io.github.liaoalia.kiroacp.core.model.SessionUsage;
import io.github.liaoalia.kiroacp.core.model.ToWebview;
import io.github.liaoalia.kiroacp.core.session.Harness.Client;
import io.github.liaoalia.kiroacp.core.session.commands.SlashCommands;
import io.github.liaoalia.kiroacp.core.session.commands.UsageTracker;

/**
 * Context and token usage reported by the agent reaches the state and the
 * pages (reference D26; e2e F15), against the fake agent.
 */
class ChatUsageTest {

	@TempDir
	Path dir;

	private static List<SessionUsage> posts(Client view, int mark) {
		return view.page.ofType(ToWebview.Usage.class, mark).stream().map(ToWebview.Usage::usage).toList();
	}

	private static boolean noUsage(Client view) throws Exception {
		JsonNode usage = view.snapshot().path("usage");
		return usage.isNull() || usage.isMissingNode();
	}

	private static String newSession(Harness h, Client view) throws Exception {
		String before = view.sessionId();
		int mark = view.mark();
		view.send(new FromWebview.NewSession());
		view.page.await(ToWebview.Session.class, mark, s -> s.sessionId() != null && !s.sessionId().equals(before), "the new session");
		h.scheduler.flush();
		return view.sessionId();
	}

	@Test
	void usageUpdateContextUsageAndTurnCompletionFillTheSessionsUsage() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			UsageTracker tracker = UsageTracker.install(h.controller);
			Client view = h.openView("view");
			assertTrue(noUsage(view), "no usage before the agent reported any");

			int mark = view.mark();
			assertEquals("end_turn", view.turn("/scenario usage").stopReason());

			JsonNode usage = view.snapshot().get("usage");
			assertEquals(42, usage.get("contextPercent").asInt(), "percentage from Kiro's context_usage (overrides the usage_update ratio)");
			assertEquals(8400, usage.get("contextUsed").asLong(), "tokens in context from usage_update");
			assertEquals(200_000, usage.get("contextSize").asLong(), "context window from usage_update");
			assertEquals(1500, usage.get("tokensSpent").asLong(), "turn_completion summaries summed");
			assertTrue(usage.get("cost").isNull(), "no cost reported");

			List<SessionUsage> posts = posts(view, mark);
			assertEquals(List.of(new SessionUsage(4.0, 8400L, 200_000L, null, null), new SessionUsage(42.0, 8400L, 200_000L, null, null),
					new SessionUsage(42.0, 8400L, 200_000L, 1500L, null)), posts, "one usage post per change");
			assertEquals(posts.get(2), h.ask(() -> tracker.usage(view.sessionId())));

			// A second turn adds its tokens; the context numbers stay, and what did not change is not posted again.
			int second = view.mark();
			view.turn("/scenario usage");
			JsonNode after = view.snapshot().get("usage");
			assertEquals(3000, after.get("tokensSpent").asLong(), "tokens add up across turns");
			assertEquals(42, after.get("contextPercent").asInt(), "context percentage unchanged");
			// 4 % again from the usage_update, 42 % again from the context_usage, then the tokens.
			assertEquals(3, posts(view, second).size(), posts(view, second).toString());

			// A new session starts from nothing; the first one keeps its numbers.
			String first = view.sessionId();
			newSession(h, view);
			assertTrue(noUsage(view), "a fresh session has no usage");
			assertEquals(3000L, h.ask(() -> tracker.usage(first)).tokensSpent());

			// A closed session is forgotten.
			h.get(h.controller.closeSession(first));
			assertNull(h.ask(() -> tracker.usage(first)));

			assertEquals(1, h.logger.lines.stream().filter(line -> line.contains("session_info_update (context_usage)")).count(),
					"one example of each kind is logged per session");
			assertTrue(h.logger.has("session " + first + " session_info_update (turn_completion): {\"sessionUpdate\":\"session_info_update\""),
					h.logger.lines.toString());
		}
	}

	@Test
	void afterATurnWithoutAUsageReportTheContextMethodIsAskedOnceForThePercentage() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			UsageTracker.install(h.controller);
			Client view = h.openView("view");
			// For `/context`; after the view, whose commands the harness waits for.
			SlashCommands.install(h.controller, null);
			int mark = view.mark();
			view.turn("/scenario chat");
			view.page.await(ToWebview.Usage.class, mark, u -> u.usage().contextPercent() != null, "the polled percentage");
			h.scheduler.flush();

			JsonNode usage = view.snapshot().get("usage");
			assertEquals(7, usage.get("contextPercent").asInt(), "the polled percentage");
			assertEquals(200_000, usage.get("contextSize").asLong(), "the polled context window");
			assertTrue(usage.get("tokensSpent").isNull(), "nothing known about tokens spent");
			assertTrue(h.logger.has("session " + view.sessionId() + " _kiro/session/context reply: {"), "the first reply is logged");

			// Known percentage: the next turn does not poll again. `/context` calls the same
			// method and its reply counts the calls so far (the poll, then itself).
			view.turn("/scenario chat");
			int asked = view.mark();
			view.prompt("/context");
			ToWebview.Chunk reply = view.page.await(ToWebview.Chunk.class, asked, chunk -> chunk.role() == MessageRole.AGENT,
					"the reply to /context");
			h.scheduler.flush();
			assertTrue(reply.segment().toString().contains("\"calls\": 2"),
					"the method was called once by the poll and once by /context: " + reply.segment());
			assertEquals(1, posts(view, mark).size(), "one usage post in all: " + posts(view, mark));
		}
	}

	@Test
	void anAgentThatRejectsTheContextMethodIsNotAskedAgainUntilItIsRestarted() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			AtomicInteger asked = new AtomicInteger();
			h.agentWrapper = real -> (KiroAgent) Proxy.newProxyInstance(KiroAgent.class.getClassLoader(), new Class<?>[] { KiroAgent.class },
					(proxy, method, args) -> {
						if (method.getName().equals("callExtension")) {
							asked.incrementAndGet();
							h.signal.changed();
							return CompletableFuture.failedFuture(new AcpException("Method not found"));
						}
						try {
							return method.invoke(real, args);
						} catch (InvocationTargetException e) {
							throw e.getCause();
						}
					});
			UsageTracker.install(h.controller);
			Client view = h.openView("view");

			view.turn("/scenario chat");
			h.await(() -> h.logger.has("_kiro/session/context after the turn failed: Method not found; not polling again"), "the refusal");
			view.turn("/scenario chat");
			h.scheduler.flush();
			assertEquals(1, asked.get(), "not asked again");
			assertTrue(noUsage(view));

			// A new process may be another version: it is asked again.
			int mark = view.mark();
			view.send(new FromWebview.Restart());
			view.page.await(ToWebview.State.class, mark,
					s -> s.state().sessionId() != null && s.state().connection().status() == ConnectionStatus.READY, "a session of the new agent");
			h.awaitSettled(view);
			view.turn("/scenario chat");
			h.await(() -> asked.get() == 2, "the new agent to be asked");
		}
	}
}
