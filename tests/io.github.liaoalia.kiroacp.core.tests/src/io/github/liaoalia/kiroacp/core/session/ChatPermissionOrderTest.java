package io.github.liaoalia.kiroacp.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.liaoalia.kiroacp.core.acp.api.SessionEvent;
import io.github.liaoalia.kiroacp.core.model.FromWebview;
import io.github.liaoalia.kiroacp.core.model.PermissionView;
import io.github.liaoalia.kiroacp.core.model.SessionStatus;
import io.github.liaoalia.kiroacp.core.model.ToWebview;
import io.github.liaoalia.kiroacp.core.model.ToolCallView;
import io.github.liaoalia.kiroacp.core.model.UiState;
import io.github.liaoalia.kiroacp.core.session.Harness.Client;

/**
 * A permission card never overtakes the tool call that preceded its request
 * on the wire, however far the controller thread is behind the agent.
 *
 * <p>The fake agent asks for one permission at a time, so the test thread
 * stands in for the connection's reader thread: it hands the controller the
 * agent's updates and requests exactly as {@code AgentLifecycle} and the ACP
 * layer do, while the controller thread is kept busy.
 */
class ChatPermissionOrderTest {

	@TempDir
	Path dir;

	/** Keeps the controller thread busy until closed; what is queued meanwhile runs afterwards, in order. */
	private static final class Busy implements AutoCloseable {
		private final CountDownLatch release = new CountDownLatch(1);

		Busy(Harness h) throws InterruptedException {
			CountDownLatch entered = new CountDownLatch(1);
			h.scheduler.execute(() -> {
				entered.countDown();
				try {
					release.await(Hosts.SECONDS, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			});
			assertTrue(entered.await(Hosts.SECONDS, TimeUnit.SECONDS), "the controller thread to be taken");
		}

		@Override
		public void close() {
			release.countDown();
		}
	}

	/** The reader thread's part of a {@code tool_call} update: its kind is recorded at once, the rest is queued. */
	private static void toolCall(Harness h, String sessionId, String toolCallId) {
		SessionEvent event = new SessionEvent.Update(sessionId, Harness.json("{\"sessionUpdate\":\"tool_call\",\"toolCallId\":\""
				+ toolCallId + "\",\"title\":\"Edit " + toolCallId + "\",\"kind\":\"edit\",\"status\":\"pending\"}"), false);
		h.controller.kinds.record(event);
		h.controller.async.later(() -> h.controller.events.handle(event));
	}

	/** The reader thread's part of {@code session/request_permission}: the request is pending when this returns. */
	private static CompletableFuture<JsonNode> request(Harness h, String sessionId, String toolCallId) {
		return h.controller.services.requestPermission(Harness.json("{\"sessionId\":\"" + sessionId + "\",\"toolCall\":{\"toolCallId\":\""
				+ toolCallId + "\",\"title\":\"Edit " + toolCallId + "\"},\"options\":["
				+ "{\"optionId\":\"accept\",\"name\":\"Allow\",\"kind\":\"allow_once\"},"
				+ "{\"optionId\":\"reject\",\"name\":\"Reject\",\"kind\":\"reject_once\"}]}"), null);
	}

	/** Tool cards and permission cards as the page got them: {@code tool <id>} and {@code permission <tool call id>}. */
	private static List<String> cards(Client view, int mark) {
		List<String> out = new ArrayList<>();
		for (ToWebview message : view.page.since(mark)) {
			if (message instanceof ToWebview.ToolCall tool) {
				out.add("tool " + tool.call().toolCallId());
			} else if (message instanceof ToWebview.Permission permission) {
				out.add("permission " + permission.request().toolCallId());
			} else if (message instanceof ToWebview.PermissionResolved resolved) {
				out.add("resolved " + resolved.requestId());
			}
		}
		return out;
	}

	/** Records what the features hear, with whether the transcript already had the tool call a request is about. */
	private static List<String> listen(Harness h) {
		List<String> heard = new CopyOnWriteArrayList<>();
		h.controller.hooks().addListener(new ChatHooks.SessionListener() {
			@Override
			public void toolCallChanged(String sessionId, ToolCallView call) {
				heard.add("tool " + call.toolCallId());
			}

			@Override
			public void permissionRequested(String sessionId, PermissionView request) {
				boolean known = h.controller.transcript(sessionId).toolCalls().containsKey(request.toolCallId());
				heard.add("permission " + request.toolCallId() + (known ? "" : " BEFORE ITS TOOL CALL"));
			}
		});
		return heard;
	}

	@Test
	void requestsThatArriveBackToBackAreShownEachAfterItsOwnToolCall() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			Client view = h.openView("view");
			String sessionId = view.sessionId();
			List<String> heard = listen(h);
			int mark = view.mark();

			CompletableFuture<JsonNode> first;
			CompletableFuture<JsonNode> second;
			try (Busy busy = new Busy(h)) {
				toolCall(h, sessionId, "call-1");
				first = request(h, sessionId, "call-1");
				toolCall(h, sessionId, "call-2");
				second = request(h, sessionId, "call-2");
				assertEquals(2, h.controller.permissions().size(), "both wait for an answer before the controller saw either");
			}
			h.scheduler.flush();

			List<String> expected = List.of("tool call-1", "permission call-1", "tool call-2", "permission call-2");
			assertEquals(expected, cards(view, mark));
			assertEquals(expected, heard);
			assertEquals(2, view.snapshot().path("permissions").size());

			// Both are answered from the page as usual.
			for (ToWebview.Permission shown : view.page.ofType(ToWebview.Permission.class, mark)) {
				view.send(new FromWebview.PermissionResponse(shown.request().requestId(), "reject"));
			}
			assertEquals("reject", h.get(first).at("/outcome/optionId").asText());
			assertEquals("reject", h.get(second).at("/outcome/optionId").asText());
			h.scheduler.flush();
			assertEquals(2, view.page.ofType(ToWebview.PermissionResolved.class, mark).size());
			assertEquals(0, view.snapshot().path("permissions").size());
		}
	}

	@Test
	void aSyncQueuedByAnAnswerDoesNotShowTheRequestThatArrivedBehindIt() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			Client view = h.openView("view");
			String sessionId = view.sessionId();
			List<String> heard = listen(h);
			int mark = view.mark();
			toolCall(h, sessionId, "call-1");
			CompletableFuture<JsonNode> first = request(h, sessionId, "call-1");
			String firstId = view.page.await(ToWebview.Permission.class, mark, p -> true, "the first card").request().requestId();
			h.scheduler.flush();

			// The controller answers the first request, which queues a sync, and is then busy;
			// the next tool call and its request arrive behind that sync.
			CountDownLatch answered = new CountDownLatch(1);
			CountDownLatch release = new CountDownLatch(1);
			h.scheduler.execute(() -> {
				h.controller.permissions().resolve(firstId, "accept");
				answered.countDown();
				try {
					release.await(Hosts.SECONDS, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			});
			assertTrue(answered.await(Hosts.SECONDS, TimeUnit.SECONDS));
			assertEquals("accept", h.get(first).at("/outcome/optionId").asText());
			toolCall(h, sessionId, "call-2");
			CompletableFuture<JsonNode> second = request(h, sessionId, "call-2");
			release.countDown();
			h.scheduler.flush();

			assertEquals(List.of("tool call-1", "permission call-1", "resolved " + firstId, "tool call-2", "permission call-2"),
					cards(view, mark));
			assertEquals(List.of("tool call-1", "permission call-1", "tool call-2", "permission call-2"), heard);
			h.controller.permissions().cancelAll(sessionId);
			assertEquals("cancelled", h.get(second).at("/outcome/outcome").asText());
		}
	}

	@Test
	void withoutAPageTheIdeIsAskedForOneOnlyAfterTheToolCall() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			h.get(h.controller.start());
			String sessionId = h.ask(h.controller::focusedSessionId);
			// The tool calls go into the same list as what the IDE is asked to do.
			h.controller.hooks().addListener(new ChatHooks.SessionListener() {
				@Override
				public void toolCallChanged(String session, ToolCallView call) {
					h.actions.calls.add("tool " + call.toolCallId());
				}
			});

			try (Busy busy = new Busy(h)) {
				toolCall(h, sessionId, "call-1");
				request(h, sessionId, "call-1");
				toolCall(h, sessionId, "call-2");
				request(h, sessionId, "call-2");
			}
			h.scheduler.flush();

			assertEquals(List.of("tool call-1", "revealSession " + sessionId, "tool call-2", "revealSession " + sessionId),
					h.actions.calls.stream().filter(call -> call.startsWith("tool ") || call.startsWith("revealSession")).toList());
			h.controller.permissions().cancelAll(sessionId);
		}
	}

	@Test
	void aStateBuiltBeforeARequestIsAnnouncedLeavesItToItsCard() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			Client view = h.openView("view");
			String sessionId = view.sessionId();
			int mark = view.mark();

			// The controller is busy while the tool call and its request arrive, and before it
			// gets to either, a page asks for its state (as on `ready` or a session switch).
			CountDownLatch entered = new CountDownLatch(1);
			CountDownLatch release = new CountDownLatch(1);
			CompletableFuture<UiState> early = new CompletableFuture<>();
			h.scheduler.execute(() -> {
				entered.countDown();
				try {
					release.await(Hosts.SECONDS, TimeUnit.SECONDS);
					early.complete(h.controller.snapshot(sessionId));
				} catch (InterruptedException | RuntimeException e) {
					early.completeExceptionally(e);
				}
			});
			assertTrue(entered.await(Hosts.SECONDS, TimeUnit.SECONDS));
			toolCall(h, sessionId, "call-1");
			request(h, sessionId, "call-1");
			assertEquals(1, h.controller.permissions().size());
			release.countDown();

			UiState state = h.get(early);
			assertEquals(List.of(), state.permissions(), "not announced yet: the state says nothing of it");
			assertEquals(List.of(), List.copyOf(state.toolCalls().keySet()));
			assertEquals(SessionStatus.IDLE, state.sessions().get(0).status(), "nor does the session list");
			h.scheduler.flush();

			// The card then comes once, behind its tool call, and every later state has it.
			assertEquals(List.of("tool call-1", "permission call-1"), cards(view, mark));
			assertEquals(1, view.snapshot().path("permissions").size());
			assertEquals("needs_input", view.snapshot().at("/sessions/0/status").asText());
			String requestId = view.page.last(ToWebview.Permission.class).request().requestId();

			// A page that shows the session from now on has the card in its state and is not sent it again.
			Client late = h.attach("late", AttachOptions.shared());
			late.send(new FromWebview.Ready());
			ToWebview.State shown = late.page.await(ToWebview.State.class, 0, s -> sessionId.equals(s.state().sessionId()), "the state");
			assertEquals(List.of(requestId), shown.state().permissions().stream().map(PermissionView::requestId).toList());
			h.scheduler.flush();
			assertEquals(0, late.page.ofType(ToWebview.Permission.class).size());

			// A sync that was queued with a request that is answered by now does not bring its card back.
			int answered = view.mark();
			view.send(new FromWebview.PermissionResponse(requestId, "reject"));
			view.page.await(ToWebview.PermissionResolved.class, answered, p -> true, "the card to go");
			h.scheduler.flush();
			h.ask(() -> {
				h.controller.cards.sync(List.of(requestId));
				return null;
			});
			assertEquals(List.of("resolved " + requestId), cards(view, answered));
			assertEquals(0, view.snapshot().path("permissions").size());
		}
	}
}
