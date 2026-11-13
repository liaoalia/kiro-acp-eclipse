package io.github.liaoalia.kiroacp.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.liaoalia.kiroacp.core.acp.api.AcpException;
import io.github.liaoalia.kiroacp.core.acp.api.KiroAgent;
import io.github.liaoalia.kiroacp.core.model.ContextChip;
import io.github.liaoalia.kiroacp.core.model.DeliveryState;
import io.github.liaoalia.kiroacp.core.model.FromWebview;
import io.github.liaoalia.kiroacp.core.model.ImageAttachment;
import io.github.liaoalia.kiroacp.core.model.MessageRole;
import io.github.liaoalia.kiroacp.core.model.MessageSegment;
import io.github.liaoalia.kiroacp.core.model.ToWebview;
import io.github.liaoalia.kiroacp.core.services.DiskTextFiles;
import io.github.liaoalia.kiroacp.core.session.Harness.Client;
import io.github.liaoalia.kiroacp.core.session.context.ChatContext;
import io.github.liaoalia.kiroacp.core.session.steering.Steering;

/**
 * Prompts typed while a turn runs, against the reference's fake agent
 * (reference D22): its e2e tests F12.1 to F12.3 and F12.5 to F12.7, and
 * F16.8 and F16.9 (images with a steered and with a queued prompt), at the
 * level of the page protocol. The fake agent's {@code slow} turn streams for
 * two seconds, {@code slow-steer} refuses to steer only after 1.5 s.
 *
 * <p>{@code /clear} is not a command here yet: F12.6 and F12.7 replace the
 * session through {@link ChatController#replaceSession}, which is what the
 * command does. F12.4 ({@code /cancel} during a turn) belongs to the slash
 * commands; F12.8 to F12.10 are in {@link ChatStopTest}.
 */
class ChatSteeringTest {

	private static final String STEER = Steering.STEER_METHOD;
	/** A 1x1 PNG: the image sent to the agent. */
	private static final String PNG_1PX = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";
	private static final int PNG_BYTES = Base64.getDecoder().decode(PNG_1PX).length;
	/** Stands in for the page's thumbnail: other bytes than the image, so the echo tells which it shows. */
	private static final String PREVIEW = "data:image/webp;base64,UklGRhoAAABXRUJQVlA4TA0AAAAvAAAAEAcQERGIiP4HAA==";

	@TempDir
	Path dir;

	/** What the controller sent as a {@code session/prompt}. */
	private record Sent(String sessionId, List<JsonNode> blocks) {

		String text() {
			return blocks.isEmpty() ? "" : blocks.get(0).path("text").asText("");
		}
	}

	/** The harness with steering installed, and a look at what goes to the agent. */
	private final class Fixture implements AutoCloseable {
		final Harness h;
		final Steering steering;
		final List<Sent> prompts = new CopyOnWriteArrayList<>();
		/** How many {@code session/prompt} requests were answered or failed. */
		final AtomicInteger settled = new AtomicInteger();
		final List<String> extensions = new CopyOnWriteArrayList<>();
		/** {@code session/new} waits for this one when a test puts it here. */
		volatile CompletableFuture<Void> newSessionGate;
		final AtomicInteger newSessionsAsked = new AtomicInteger();
		volatile boolean failNewSession;

		Fixture(String... agentArgs) {
			h = Harness.launch(dir, agentArgs);
			h.agentWrapper = agent -> (KiroAgent) Proxy.newProxyInstance(KiroAgent.class.getClassLoader(),
					new Class<?>[] { KiroAgent.class }, (proxy, method, args) -> {
						try {
							switch (method.getName()) {
							case "prompt": {
								@SuppressWarnings("unchecked")
								List<JsonNode> blocks = (List<JsonNode>) args[1];
								prompts.add(new Sent((String) args[0], List.copyOf(blocks)));
								return invoke(method, agent, args).whenComplete((done, error) -> {
									settled.incrementAndGet();
									h.signal.changed();
								});
							}
							case "callExtension":
								extensions.add((String) args[0]);
								break;
							case "newSession": {
								newSessionsAsked.incrementAndGet();
								h.signal.changed();
								if (failNewSession) {
									return CompletableFuture.failedFuture(new AcpException("no session today"));
								}
								CompletableFuture<Void> gate = newSessionGate;
								if (gate != null) {
									return gate.thenCompose(open -> invoke(method, agent, args));
								}
								break;
							}
							default:
								break;
							}
							return method.invoke(agent, args);
						} catch (InvocationTargetException e) {
							throw e.getCause();
						}
					});
			steering = Steering.install(h.controller);
		}

		/** As the reference's test hook: the session's prompts during a turn queue without asking the agent. */
		void queueOnly(Client view) throws Exception {
			String sessionId = view.sessionId();
			h.ask(() -> {
				steering.setSteerUnsupported(sessionId, true);
				return null;
			});
		}

		/** Starts the two-second turn and returns once two of its chunks have streamed. */
		void startSlowTurn(Client view, int mark, String scenario) throws Exception {
			view.prompt("/scenario " + scenario);
			h.await(() -> view.agentChunks(mark).size() >= 2, "two streamed chunks of the slow turn");
			assertTrue(view.snapshot().get("turnActive").asBoolean(), "turnActive while the slow turn streams");
		}

		long steers() {
			return extensions.stream().filter(STEER::equals).count();
		}

		@Override
		public void close() throws Exception {
			h.close();
		}
	}

	@SuppressWarnings("unchecked")
	private static CompletableFuture<Object> invoke(Method method, KiroAgent agent, Object[] args) {
		try {
			return (CompletableFuture<Object>) method.invoke(agent, args);
		} catch (InvocationTargetException e) {
			return CompletableFuture.failedFuture(e.getCause());
		} catch (ReflectiveOperationException e) {
			return CompletableFuture.failedFuture(e);
		}
	}

	// ---------------------------------------------------------------------
	// What the page got
	// ---------------------------------------------------------------------

	/** Waits for the echo of a user message and gives its view id. */
	private static String echoed(Client view, int mark, String text) throws InterruptedException {
		return view.page.await(ToWebview.Chunk.class, mark, chunk -> chunk.role() == MessageRole.USER
				&& chunk.segment() instanceof MessageSegment.Text shown && shown.text().equals(text), "the echo of '" + text + "'")
				.messageId();
	}

	private static List<DeliveryState> deliveries(Client view, int mark, String messageId) {
		List<DeliveryState> out = new ArrayList<>();
		for (ToWebview.MessageDelivery badge : view.page.ofType(ToWebview.MessageDelivery.class, mark)) {
			if (badge.messageId().equals(messageId)) {
				out.add(badge.delivery());
			}
		}
		return out;
	}

	private static void awaitDelivery(Client view, int mark, String messageId, DeliveryState delivery) throws InterruptedException {
		view.page.await(ToWebview.MessageDelivery.class, mark,
				badge -> badge.messageId().equals(messageId) && badge.delivery() == delivery,
				"the badge '" + delivery + "' of " + messageId);
	}

	private static List<ToWebview.Turn> turns(Client view, int mark, boolean active) {
		return view.page.ofType(ToWebview.Turn.class, mark).stream().filter(turn -> turn.active() == active).toList();
	}

	private void awaitTurnEnds(Fixture f, Client view, int mark, int count) throws Exception {
		f.h.await(() -> turns(view, mark, false).size() >= count, count + " ended turn(s)");
		f.h.scheduler.flush();
	}

	private static List<String> notices(Client view, int mark) {
		return view.page.ofType(ToWebview.Notice.class, mark).stream().map(notice -> notice.notice().text()).toList();
	}

	private static List<JsonNode> messages(Client view, String role) throws Exception {
		List<JsonNode> out = new ArrayList<>();
		for (JsonNode item : view.snapshot().get("items")) {
			if ("message".equals(item.path("type").asText()) && role.equals(item.path("message").path("role").asText())) {
				out.add(item.get("message"));
			}
		}
		return out;
	}

	private static String textOf(JsonNode message) {
		StringBuilder text = new StringBuilder();
		for (JsonNode segment : message.get("segments")) {
			if ("text".equals(segment.path("type").asText())) {
				text.append(segment.get("text").asText());
			}
		}
		return text.toString();
	}

	private static List<JsonNode> userMessages(Client view, String text) throws Exception {
		return messages(view, "user").stream().filter(message -> textOf(message).equals(text)).toList();
	}

	/** The badge the state of a page attached now would show for the one user message with that text. */
	private static String badge(Client view, String text) throws Exception {
		List<JsonNode> found = userMessages(view, text);
		assertEquals(1, found.size(), "'" + text + "' is in the transcript once");
		return found.get(0).path("delivery").asText(null);
	}

	private static long answers(Client view) throws Exception {
		return messages(view, "agent").stream().filter(message -> textOf(message).equals("The answer is 42.")).count();
	}

	private static int indexOf(Client view, int mark, java.util.function.Predicate<ToWebview> what) {
		List<ToWebview> all = view.page.since(mark);
		for (int i = 0; i < all.size(); i++) {
			if (what.test(all.get(i))) {
				return i;
			}
		}
		return -1;
	}

	private static boolean agentText(ToWebview message, String part) {
		return message instanceof ToWebview.Chunk chunk && chunk.role() == MessageRole.AGENT
				&& chunk.segment() instanceof MessageSegment.Text shown && shown.text().contains(part);
	}

	private static ImageAttachment pasted(String id, String data) {
		return new ImageAttachment(id, "Pasted image " + id + ".png", "image/png", data, PNG_BYTES, null, PREVIEW);
	}

	// ---------------------------------------------------------------------
	// Steering
	// ---------------------------------------------------------------------

	@Test
	void aPromptTypedDuringATurnIsSteeredAndTheBadgeClearsOnceTheAgentInjectsIt() throws Exception {
		try (Fixture f = new Fixture()) {
			Client view = f.h.openView("view");
			assertTrue(view.snapshot().get("acceptsPromptsDuringTurn").asBoolean(), "the page may offer its composer during a turn");
			int mark = view.mark();
			f.startSlowTurn(view, mark, "slow");

			view.prompt("steer me");
			String steered = echoed(view, mark, "steer me");
			// The fake agent injects the prompt inside the running turn, then the badge clears.
			view.page.await(ToWebview.Chunk.class, mark, chunk -> agentText(chunk, "Steered: steer me"), "the steered answer");
			awaitDelivery(view, mark, steered, null);
			assertEquals(Arrays.asList(DeliveryState.STEERING, null), deliveries(view, mark, steered));
			int echoAt = indexOf(view, mark, m -> m instanceof ToWebview.Chunk chunk && chunk.messageId().equals(steered));
			int badgeAt = indexOf(view, mark, m -> m instanceof ToWebview.MessageDelivery);
			assertTrue(echoAt < badgeAt, "echoed at once, then badged");
			assertNull(badge(view, "steer me"), "the transcript carries no badge after the injection");
			int injectedAt = indexOf(view, mark, m -> agentText(m, "Steered:"));
			int endedAt = indexOf(view, mark, m -> m instanceof ToWebview.Turn turn && !turn.active());
			assertTrue(endedAt < 0 || injectedAt < endedAt, "the steered answer arrived inside the running turn");

			assertEquals("end_turn", view.awaitTurnEnd(mark).stopReason(), "the steered turn still ends normally");
			assertFalse(view.snapshot().get("turnActive").asBoolean());
			assertEquals(1, turns(view, mark, true).size(), "nothing but the slow turn ran");
			assertEquals(List.of("/scenario slow"), f.prompts.stream().map(Sent::text).toList(), "the steered text was no prompt of its own");
			assertEquals(List.of(STEER), f.extensions);
			assertTrue(f.h.logger.has("steer " + view.sessionId() + ": " + STEER + " {\"sessionId\":\"" + view.sessionId()
					+ "\",\"prompt\":[{\"type\":\"text\",\"text\":\"steer me\"}]}"), "what was sent is in the log");
			assertTrue(f.h.logger.has("steer " + view.sessionId() + ": accepted {}"));
			assertTrue(f.h.logger.has("steer " + view.sessionId() + ": steering_injected"));
			assertEquals(List.of(), notices(view, mark));
			assertEquals(view.snapshot(), view.page.model(), "the page arrived at the state it would get for ready");
		}
	}

	@Test
	void aSteerRefusedAfterTheTurnWasCancelledIsSentAsTheNextTurn() throws Exception {
		try (Fixture f = new Fixture()) {
			Client view = f.h.openView("view");
			int mark = view.mark();
			// `slow-steer`: the fake agent refuses `_session/steer` only after 1.5 s.
			f.startSlowTurn(view, mark, "slow-steer");

			view.prompt("/scenario chat late steer");
			String steered = echoed(view, mark, "/scenario chat late steer");
			awaitDelivery(view, mark, steered, DeliveryState.STEERING);
			assertEquals("steering", badge(view, "/scenario chat late steer"), "the badge while the steer request is in flight");

			// Stop the turn before the agent answers the steer.
			view.send(new FromWebview.Cancel());
			assertEquals("cancelled", view.awaitTurnEnd(mark).stopReason());
			assertFalse(view.snapshot().get("turnActive").asBoolean(), "no turn runs: the message is still with the agent, unanswered");

			// The late refusal arrives: the message was typed and never delivered, so it goes out as a prompt of its own.
			awaitTurnEnds(f, view, mark, 2);
			assertTrue(f.h.logger.has("steer " + view.sessionId() + ": failed (-32602)"), f.h.logger.lines.toString());
			assertTrue(f.h.logger.has("the turn ended before the refusal; sending as a prompt"));
			assertEquals(1, answers(view), "the re-sent message was answered");
			assertEquals(2, turns(view, mark, true).size(), "the cancelled turn and the re-sent one");
			assertEquals("end_turn", turns(view, mark, false).get(1).stopReason());
			assertEquals(Arrays.asList(DeliveryState.STEERING, null, DeliveryState.QUEUED, null), deliveries(view, mark, steered),
					"cleared with the turn, queued by the refusal, cleared when sent");
			assertNull(badge(view, "/scenario chat late steer"), "echoed once, and no badge is left");
			assertEquals(List.of(), notices(view, mark));
			assertEquals(List.of("/scenario slow-steer", "/scenario chat late steer"), f.prompts.stream().map(Sent::text).toList());

			// A late refusal says nothing about steering support: the next prompt during a turn is steered again.
			int again = view.mark();
			f.startSlowTurn(view, again, "slow");
			view.prompt("steer again");
			awaitDelivery(view, again, echoed(view, again, "steer again"), DeliveryState.STEERING);
			f.h.scheduler.flush();
			assertEquals(2, f.steers(), "steering is still attempted");
			assertEquals("end_turn", view.awaitTurnEnd(again).stopReason());
			assertEquals(view.snapshot(), view.page.model());
		}
	}

	@Test
	void anAgentThatCannotSteerGetsThePromptsOfThatSessionAsTurnsOfTheirOwn() throws Exception {
		// The V2 engine does not know `_session/steer` (-32601).
		try (Fixture f = new Fixture("--engine", "v2")) {
			Client view = f.h.openView("view");
			int mark = view.mark();
			f.startSlowTurn(view, mark, "slow");

			view.prompt("/scenario chat first");
			String first = echoed(view, mark, "/scenario chat first");
			awaitDelivery(view, mark, first, DeliveryState.QUEUED);
			assertEquals(List.of(DeliveryState.STEERING, DeliveryState.QUEUED), deliveries(view, mark, first),
					"tried as a steer, queued when the agent refused");
			assertTrue(f.h.logger.has("steer " + view.sessionId() + ": failed (-32601)"), f.h.logger.lines.toString());

			// The session remembers the refusal: the next one queues without asking.
			view.prompt("/scenario chat second");
			String second = echoed(view, mark, "/scenario chat second");
			awaitDelivery(view, mark, second, DeliveryState.QUEUED);
			assertEquals(List.of(DeliveryState.QUEUED), deliveries(view, mark, second));
			assertEquals(1, f.steers(), "the agent was asked once");
			assertTrue(view.snapshot().get("turnActive").asBoolean(), "the slow turn is still running");

			awaitTurnEnds(f, view, mark, 3);
			assertEquals(2, answers(view));
			assertEquals(List.of("/scenario slow", "/scenario chat first", "/scenario chat second"),
					f.prompts.stream().map(Sent::text).toList());
			assertEquals(view.snapshot(), view.page.model());

			// Another session of the same agent finds out for itself.
			Client other = f.h.openView("other");
			int otherMark = other.mark();
			f.startSlowTurn(other, otherMark, "slow");
			other.prompt("/scenario chat third");
			String third = echoed(other, otherMark, "/scenario chat third");
			awaitDelivery(other, otherMark, third, DeliveryState.QUEUED);
			assertEquals(2, f.steers(), "what one session learned is not assumed of another");
			awaitTurnEnds(f, other, otherMark, 2);
		}
	}

	// ---------------------------------------------------------------------
	// The queue
	// ---------------------------------------------------------------------

	@Test
	void withoutSteeringPromptsDuringATurnQueueAndAreSentOnePerTurnAfterItEnds() throws Exception {
		try (Fixture f = new Fixture()) {
			Client view = f.h.openView("view");
			f.queueOnly(view);
			int mark = view.mark();
			f.startSlowTurn(view, mark, "slow");

			view.prompt("/scenario chat first");
			view.prompt("/scenario chat second");
			String first = echoed(view, mark, "/scenario chat first");
			String second = echoed(view, mark, "/scenario chat second");
			awaitDelivery(view, mark, second, DeliveryState.QUEUED);
			assertEquals(List.of(DeliveryState.QUEUED), deliveries(view, mark, first));
			// What a page attached or switched to the session now would be told.
			JsonNode during = view.snapshot();
			assertTrue(during.get("turnActive").asBoolean(), "the slow turn is still running");
			assertTrue(during.get("acceptsPromptsDuringTurn").asBoolean());
			assertEquals("queued", badge(view, "/scenario chat first"));
			assertEquals("queued", badge(view, "/scenario chat second"));
			assertEquals(1, f.prompts.size(), "nothing but the slow turn's prompt went to the agent");

			// The first queued prompt starts the next turn by itself: badge cleared, no second echo.
			awaitTurnEnds(f, view, mark, 3);
			assertEquals(2, answers(view), "both were answered");
			assertEquals(Arrays.asList(DeliveryState.QUEUED, null), deliveries(view, mark, first));
			assertEquals(Arrays.asList(DeliveryState.QUEUED, null), deliveries(view, mark, second));
			assertNull(badge(view, "/scenario chat first"), "echoed once, no badge once the queue drained");
			assertNull(badge(view, "/scenario chat second"));
			assertTrue(turns(view, mark, false).stream().allMatch(turn -> "end_turn".equals(turn.stopReason())),
					"three turns ended normally: " + turns(view, mark, false));
			assertEquals(3, turns(view, mark, true).size());

			// In order, one per turn: the second keeps its badge until the first one's turn is over.
			int firstCleared = indexOf(view, mark, m -> m.equals(new ToWebview.MessageDelivery(first, null)));
			int secondCleared = indexOf(view, mark, m -> m.equals(new ToWebview.MessageDelivery(second, null)));
			List<Integer> ends = new ArrayList<>();
			List<ToWebview> all = view.page.since(mark);
			for (int i = 0; i < all.size(); i++) {
				if (all.get(i) instanceof ToWebview.Turn turn && !turn.active()) {
					ends.add(i);
				}
			}
			assertTrue(ends.get(0) < firstCleared && firstCleared < ends.get(1) && ends.get(1) < secondCleared
					&& secondCleared < ends.get(2), "slow, first, second: " + view.page.types(mark));
			assertEquals(List.of("/scenario slow", "/scenario chat first", "/scenario chat second"),
					f.prompts.stream().map(Sent::text).toList());
			// The answer of a queued prompt is a message of its own, not the continuation of the answer before it.
			List<JsonNode> items = new ArrayList<>();
			view.snapshot().get("items").forEach(items::add);
			assertEquals(List.of("The answer is 42.", "The answer is 42."),
					items.subList(items.size() - 2, items.size()).stream().map(item -> textOf(item.get("message"))).toList());
			assertEquals(0, f.steers(), "the agent was never asked to steer");
			assertEquals(List.of(), notices(view, mark));
			assertEquals(view.snapshot(), view.page.model());
		}
	}

	@Test
	void cancellingTheTurnKeepsTheQueue() throws Exception {
		try (Fixture f = new Fixture()) {
			Client view = f.h.openView("view");
			f.queueOnly(view);
			int mark = view.mark();
			f.startSlowTurn(view, mark, "slow");
			view.prompt("/scenario chat later");
			String queued = echoed(view, mark, "/scenario chat later");
			awaitDelivery(view, mark, queued, DeliveryState.QUEUED);

			view.send(new FromWebview.Cancel());
			// Stop ends the current answer only: the queued prompt goes out as the next turn.
			awaitTurnEnds(f, view, mark, 2);
			assertEquals(List.of("cancelled", "end_turn"), turns(view, mark, false).stream().map(ToWebview.Turn::stopReason).toList());
			assertEquals(Arrays.asList(DeliveryState.QUEUED, null), deliveries(view, mark, queued));
			assertEquals(List.of(), notices(view, mark), "nothing was discarded");
			assertEquals(1, answers(view), "the queued prompt was answered");
			assertNull(badge(view, "/scenario chat later"));
			assertEquals(2, turns(view, mark, true).size(), "the cancelled turn and the queued one");
			assertFalse(view.snapshot().get("turnActive").asBoolean());
			assertEquals(0, f.h.scheduler.pending(Turns.CANCEL_GRACE_MS), "the agent confirmed the stop");
		}
	}

	@Test
	void aTurnThatLosesItsAgentDropsTheQueueAndSaysSo() throws Exception {
		try (Fixture f = new Fixture()) {
			Client view = f.h.openView("view");
			f.queueOnly(view);
			int mark = view.mark();
			f.startSlowTurn(view, mark, "slow");
			view.prompt("/scenario chat first");
			view.prompt("/scenario chat second");
			String first = echoed(view, mark, "/scenario chat first");
			String second = echoed(view, mark, "/scenario chat second");
			awaitDelivery(view, mark, second, DeliveryState.QUEUED);

			f.h.get(f.h.controller.shutdownAgent());
			assertEquals("error", view.awaitTurnEnd(mark).stopReason());
			view.page.await(ToWebview.Notice.class, mark, notice -> notice.notice().text().contains("discarded"), "the notice");
			f.h.scheduler.flush();
			assertTrue(notices(view, mark).contains("2 queued messages discarded."), notices(view, mark).toString());
			assertEquals(Arrays.asList(DeliveryState.QUEUED, null), deliveries(view, mark, first));
			assertEquals(Arrays.asList(DeliveryState.QUEUED, null), deliveries(view, mark, second));
			assertNull(badge(view, "/scenario chat first"));
			assertNull(badge(view, "/scenario chat second"));
			assertEquals(1, f.prompts.size(), "what waited for the end of the turn was not sent");
			assertEquals(1, turns(view, mark, true).size());
		}
	}

	// ---------------------------------------------------------------------
	// Sessions that go away
	// ---------------------------------------------------------------------

	@Test
	void closingASessionDiscardsItsQueueBeforeItsTurnIsCancelled() throws Exception {
		try (Fixture f = new Fixture()) {
			Client view = f.h.openView("view");
			String sessionId = view.sessionId();
			f.queueOnly(view);
			int mark = view.mark();
			f.startSlowTurn(view, mark, "slow");
			view.prompt("/scenario chat later");
			String queued = echoed(view, mark, "/scenario chat later");
			awaitDelivery(view, mark, queued, DeliveryState.QUEUED);

			f.h.get(f.h.controller.closeSession(sessionId));
			// Said while the page still showed the session, before it moved on to a new one.
			assertEquals(List.of("1 queued message discarded."), notices(view, mark));
			assertEquals(Arrays.asList(DeliveryState.QUEUED, null), deliveries(view, mark, queued));
			int noticeAt = indexOf(view, mark, m -> m instanceof ToWebview.Notice);
			int movedAt = indexOf(view, mark, m -> m instanceof ToWebview.Session);
			assertTrue(noticeAt >= 0 && noticeAt < movedAt, view.page.types(mark).toString());
			assertNotEquals(sessionId, view.sessionId());

			// The cancelled turn ends after the session was forgotten: its end must not send what was queued.
			f.h.await(() -> f.settled.get() == 1, "the cancelled turn's answer");
			f.h.scheduler.flush();
			assertEquals(1, f.prompts.size(), "the queued prompt never became a session/prompt");
			assertEquals(1, turns(view, mark, true).size(), "no turn started for the discarded prompt");
			assertFalse(view.snapshot().get("turnActive").asBoolean(), "the new session is idle");
			assertEquals(0, view.snapshot().get("items").size());
		}
	}

	@Test
	void aSteerRefusedForASessionClosedMeanwhileIsDropped() throws Exception {
		try (Fixture f = new Fixture()) {
			Client view = f.h.openView("view");
			String sessionId = view.sessionId();
			int mark = view.mark();
			f.startSlowTurn(view, mark, "slow-steer");
			view.prompt("/scenario chat late steer");
			awaitDelivery(view, mark, echoed(view, mark, "/scenario chat late steer"), DeliveryState.STEERING);

			f.h.get(f.h.controller.closeSession(sessionId));
			f.h.await(() -> f.h.logger.has("steer " + sessionId + ": failed"), "the late refusal");
			f.h.await(() -> f.settled.get() == 1, "the cancelled turn's answer");
			f.h.scheduler.flush();

			assertEquals(1, f.prompts.size(), "nothing was sent for the session that is gone");
			assertEquals(List.of(), notices(view, mark), "and nothing is said about it");
			assertEquals(1, turns(view, mark, true).size());
			assertFalse(f.h.ask(() -> f.h.controller.sessionIds().contains(sessionId)));
		}
	}

	@Test
	void aSteerRefusedWhileItsSessionIsBeingReplacedIsDropped() throws Exception {
		try (Fixture f = new Fixture()) {
			Client view = f.h.openView("view");
			String sessionId = view.sessionId();
			int mark = view.mark();
			f.startSlowTurn(view, mark, "slow-steer");
			view.prompt("/scenario chat late steer");
			String steered = echoed(view, mark, "/scenario chat late steer");
			awaitDelivery(view, mark, steered, DeliveryState.STEERING);

			// The replacement is held up, so the refusal finds the session still there, on its way out.
			CompletableFuture<Void> gate = new CompletableFuture<>();
			f.newSessionGate = gate;
			CompletableFuture<String> replaced = f.h.controller.replaceSession(sessionId, view.handle);
			f.h.await(() -> f.h.logger.has("steer " + sessionId + ": failed"), "the late refusal");
			f.h.await(() -> f.settled.get() == 1, "the cancelled turn's answer");
			f.h.scheduler.flush();
			assertEquals(sessionId, view.sessionId(), "the session is still shown");
			assertFalse(deliveries(view, mark, steered).contains(DeliveryState.QUEUED), "the refused message is not queued");
			assertNull(badge(view, "/scenario chat late steer"), "its badge is gone");
			assertEquals(1, f.prompts.size(), "and nothing was sent for it");
			assertEquals(1, turns(view, mark, true).size());
			assertEquals(List.of(), notices(view, mark));

			gate.complete(null);
			assertNotNull(f.h.get(replaced));
			f.h.scheduler.flush();
			assertEquals(1, f.prompts.size());
			assertFalse(f.h.ask(() -> f.h.controller.sessionIds().contains(sessionId)));
		}
	}

	@Test
	void replacingASessionDuringATurnDiscardsTheQueueAndNeverStartsATurnOnIt() throws Exception {
		try (Fixture f = new Fixture()) {
			Client view = f.h.openView("view");
			String sessionId = view.sessionId();
			f.queueOnly(view);
			int mark = view.mark();
			f.startSlowTurn(view, mark, "slow");
			view.prompt("/scenario chat later");
			String queued = echoed(view, mark, "/scenario chat later");
			awaitDelivery(view, mark, queued, DeliveryState.QUEUED);

			String replacement = f.h.get(f.h.controller.replaceSession(sessionId, view.handle));
			assertNotNull(replacement);
			assertNotEquals(sessionId, replacement);
			assertEquals(replacement, view.sessionId(), "the page shows the new session");
			assertEquals(List.of(replacement), f.h.ask(f.h.controller::sessionIds), "the replaced session is gone");
			assertEquals(replacement, f.h.ask(f.h.controller::focusedSessionId));
			assertEquals(List.of("1 queued message discarded."), notices(view, mark));
			assertEquals(Arrays.asList(DeliveryState.QUEUED, null), deliveries(view, mark, queued));
			// Discarded before the cancel: the end of the cancelled turn, whenever it came, found nothing to send.
			int discardedAt = indexOf(view, mark, m -> m.equals(new ToWebview.MessageDelivery(queued, null)));
			int endedAt = indexOf(view, mark, m -> m instanceof ToWebview.Turn turn && !turn.active());
			assertTrue(endedAt < 0 || discardedAt < endedAt, view.page.types(mark).toString());

			f.h.await(() -> f.settled.get() == 1, "the cancelled turn's answer");
			f.h.scheduler.flush();
			assertEquals(1, f.prompts.size(), "the queued prompt never became a session/prompt");
			assertEquals(1, turns(view, mark, true).size(), "only the slow turn ever started");
			assertFalse(view.page.since(mark).stream().anyMatch(m -> agentText(m, "42")), "the discarded prompt was never answered");
			JsonNode state = view.snapshot();
			assertFalse(state.get("turnActive").asBoolean(), "the new session is idle");
			assertEquals(0, state.get("items").size(), "and empty");
			assertEquals(0, f.h.scheduler.pending(Turns.CANCEL_GRACE_MS), "no Stop watchdog outlives the session");

			// The new session takes prompts as any other.
			assertEquals("end_turn", view.turn("/scenario chat").stopReason());
			assertEquals(replacement, f.prompts.get(1).sessionId());
		}
	}

	@Test
	void aPromptTypedWhileItsSessionIsBeingReplacedGoesToTheNewSession() throws Exception {
		try (Fixture f = new Fixture()) {
			Client view = f.h.openView("view");
			String before = view.sessionId();
			int mark = view.mark();
			int asked = f.newSessionsAsked.get();
			CompletableFuture<Void> gate = new CompletableFuture<>();
			f.newSessionGate = gate;

			CompletableFuture<String> replaced = f.h.controller.replaceSession(before, view.handle);
			f.h.await(() -> f.newSessionsAsked.get() == asked + 1, "session/new for the replacement");
			// The page still shows the session that is on its way out: what is typed now is meant for the next one.
			view.prompt("/scenario chat");
			f.h.scheduler.flush();
			assertEquals(before, view.sessionId());
			assertEquals(List.of(), f.prompts, "nothing is sent to a session that is being replaced");
			assertEquals(List.of(), view.page.ofType(ToWebview.Chunk.class, mark), "nor echoed in it");
			assertFalse(f.h.ask(() -> f.h.controller.sessionOpen(before)));

			gate.complete(null);
			String replacement = f.h.get(replaced);
			assertNotNull(replacement);
			assertEquals("end_turn", view.awaitTurnEnd(mark).stopReason());
			assertEquals(replacement, view.sessionId(), "the page shows the replacement");
			assertEquals(List.of(replacement), f.h.ask(f.h.controller::sessionIds), "the replaced session is gone");
			assertEquals(List.of(new Sent(replacement, List.of(Harness.json("{\"type\":\"text\",\"text\":\"/scenario chat\"}")))),
					f.prompts, "the question went to the new session, once");
			assertEquals(List.of("/scenario chat"), messages(view, "user").stream().map(ChatSteeringTest::textOf).toList(),
					"the new session holds just the typed question");
			assertEquals(1, answers(view), "and its answer");
			assertEquals(List.of(), notices(view, mark));
			assertEquals(view.snapshot(), view.page.model());
		}
	}

	@Test
	void aPromptStillBeingPutTogetherWhenItsSessionIsReplacedGoesToTheNewSession() throws Exception {
		try (Fixture f = new Fixture()) {
			Client view = f.h.openView("view");
			String before = view.sessionId();
			int mark = view.mark();
			// A composer that takes its time, as one that reads the files of the chips does.
			CompletableFuture<Void> read = new CompletableFuture<>();
			AtomicInteger composing = new AtomicInteger();
			f.h.controller.hooks().setPromptComposer((request, agent) -> {
				composing.incrementAndGet();
				f.h.signal.changed();
				return read.thenApply(done -> List.of(Harness.json("{\"type\":\"text\",\"text\":\"" + request.text() + "\"}")));
			});

			view.prompt("/scenario chat");
			f.h.await(() -> composing.get() == 1, "the prompt to be put together");
			String replacement = f.h.get(f.h.controller.replaceSession(before, view.handle));
			assertNotNull(replacement);
			read.complete(null);

			assertEquals("end_turn", view.awaitTurnEnd(mark).stopReason());
			assertEquals(List.of(new Sent(replacement, List.of(Harness.json("{\"type\":\"text\",\"text\":\"/scenario chat\"}")))),
					f.prompts, "nothing went to the session that was replaced meanwhile");
			assertEquals(replacement, view.sessionId());
			assertEquals(List.of("/scenario chat"), messages(view, "user").stream().map(ChatSteeringTest::textOf).toList());
			assertEquals(1, answers(view));
			assertEquals(List.of(), notices(view, mark));
		}
	}

	@Test
	void aSessionThatCouldNotBeReplacedStaysUsable() throws Exception {
		try (Fixture f = new Fixture()) {
			Client view = f.h.openView("view");
			String sessionId = view.sessionId();
			f.queueOnly(view);
			int mark = view.mark();
			f.startSlowTurn(view, mark, "slow");
			view.prompt("/scenario chat later");
			awaitDelivery(view, mark, echoed(view, mark, "/scenario chat later"), DeliveryState.QUEUED);

			f.failNewSession = true;
			assertNull(f.h.get(f.h.controller.replaceSession(sessionId, view.handle)), "no session could be created");
			f.failNewSession = false;
			assertEquals(sessionId, view.sessionId(), "the page keeps its session");
			assertTrue(f.h.ask(() -> f.h.controller.sessionOpen(sessionId)), "which takes prompts again");
			assertEquals("cancelled", view.awaitTurnEnd(mark).stopReason(), "its turn was cancelled all the same");
			List<String> said = notices(view, mark);
			assertTrue(said.contains("1 queued message discarded."), said.toString());
			assertTrue(said.stream().anyMatch(text -> text.startsWith("Could not create a session: ")), said.toString());
			assertFalse(said.stream().anyMatch(text -> text.contains("closing")), said.toString());

			int next = view.mark();
			assertEquals("end_turn", view.turn("/scenario chat").stopReason());
			assertEquals(1, answers(view));
			assertEquals(List.of("/scenario slow", "/scenario chat"), f.prompts.stream().map(Sent::text).toList(),
					"the discarded prompt stayed discarded");
			assertEquals(List.of(), notices(view, next));
		}
	}

	@Test
	void aSessionWhosePageIsClosedWhileItIsBeingReplacedDoesNotLinger() throws Exception {
		try (Fixture f = new Fixture()) {
			Client view = f.h.openView("view");
			String sessionId = view.sessionId();
			assertEquals("end_turn", view.turn("/scenario chat").stopReason());
			int asked = f.newSessionsAsked.get();
			CompletableFuture<Void> gate = new CompletableFuture<>();
			f.newSessionGate = gate;
			CompletableFuture<String> replaced = f.h.controller.replaceSession(sessionId, view.handle);
			f.h.await(() -> f.newSessionsAsked.get() == asked + 1, "session/new for the replacement");

			// Closing the page ends the sessions of its group, but leaves alone one that is on its way out already.
			view.handle.close();
			f.h.scheduler.flush();
			gate.complete(null);

			assertNull(f.h.get(replaced), "nobody is left to show a replacement");
			f.h.scheduler.flush();
			List<String> groups = f.h.ask(() -> f.h.controller.sessionIds().stream().map(f.h.controller::sessionGroup).toList());
			assertFalse(groups.contains("view"), "no session is left in the group of the closed page: " + groups);
			assertFalse(f.h.ask(() -> f.h.controller.sessionIds().contains(sessionId)));
		}
	}

	// ---------------------------------------------------------------------
	// Chips and images (F16.8, F16.9)
	// ---------------------------------------------------------------------

	private ChatContext context(Fixture f) {
		return ChatContext.install(f.h.controller,
				new ChatContext.Host(f.h.host.workspace(), f.h.settings, f.h.scheduler, new DiskTextFiles(), f.h.logger, null, null, null));
	}

	@Test
	void imagesTypedDuringATurnAreSteeredIntoItWithTheirText() throws Exception {
		try (Fixture f = new Fixture(); ChatContext context = context(f)) {
			Objects.requireNonNull(context);
			Client view = f.h.openView("view");
			int mark = view.mark();
			f.startSlowTurn(view, mark, "slow");

			view.send(new FromWebview.Prompt("look at this", List.of(), List.of(pasted("s", PNG_1PX))));
			String steered = echoed(view, mark, "look at this");
			awaitDelivery(view, mark, steered, DeliveryState.STEERING);
			assertTrue(view.page.ofType(ToWebview.Chunk.class, mark).stream().anyMatch(chunk -> chunk.messageId().equals(steered)
					&& chunk.segment() instanceof MessageSegment.Image image && PREVIEW.equals(image.src())),
					"the echo shows the thumbnail");
			view.page.await(ToWebview.Chunk.class, mark,
					chunk -> agentText(chunk, "Steered: look at this [1 image(s): image/png " + PNG_BYTES + " B]"),
					"the agent's steered answer naming the image");
			assertEquals("end_turn", view.awaitTurnEnd(mark).stopReason());

			// The log says what was sent, without the image itself.
			assertTrue(f.h.logger.has("{\"type\":\"image\",\"mimeType\":\"image/png\",\"data\":\"<" + PNG_BYTES + " B of base64>\"}"),
					f.h.logger.lines.toString());
			assertFalse(f.h.logger.has(PNG_1PX), "no image data in the log");
			assertEquals(view.snapshot(), view.page.model());
		}
	}

	@Test
	void aQueuedPromptKeepsItsImagesAndChipsAndARefusedImageIsNotQueued() throws Exception {
		Path readme = Files.write(dir.resolve("README.md"), "# Sample\n".getBytes(StandardCharsets.UTF_8));
		try (Fixture f = new Fixture(); ChatContext context = context(f)) {
			Client view = f.h.openView("view");
			f.queueOnly(view);
			int mark = view.mark();
			f.startSlowTurn(view, mark, "slow");
			ContextChip chip = f.h.ask(() -> context.addFileChip(readme));

			// The checks every prompt goes through come first, turn or no turn: nothing of a refused message is shown or held.
			String junk = Base64.getEncoder().encodeToString("hello, not a png".getBytes(StandardCharsets.UTF_8));
			view.send(new FromWebview.Prompt("not this one", List.of(chip), List.of(pasted("junk", junk))));
			view.page.await(ToWebview.Notice.class, mark, notice -> true, "the refusal");
			f.h.scheduler.flush();
			assertEquals(List.of("Pasted image junk.png was not attached: it is not a PNG, JPEG, GIF or WebP image. The message was not sent."),
					notices(view, mark));
			assertEquals(List.of(), view.page.ofType(ToWebview.MessageDelivery.class, mark));
			assertEquals(List.of(chip), f.h.ask(context::chips), "the chip of the refused message is still attached");

			view.send(new FromWebview.Prompt("/scenario images queued", List.of(chip), List.of(pasted("q", PNG_1PX))));
			String queued = echoed(view, mark, "/scenario images queued");
			awaitDelivery(view, mark, queued, DeliveryState.QUEUED);
			f.h.scheduler.flush();
			List<MessageSegment> echo = view.page.ofType(ToWebview.Chunk.class, mark).stream()
					.filter(chunk -> chunk.messageId().equals(queued)).map(ToWebview.Chunk::segment).toList();
			assertEquals(3, echo.size(), echo.toString());
			assertTrue(echo.get(1) instanceof MessageSegment.Image image && PREVIEW.equals(image.src()), "the thumbnail: " + echo);
			assertTrue(echo.get(2) instanceof MessageSegment.Resource, "the chip: " + echo);
			assertEquals(List.of(), f.h.ask(context::chips), "queued means sent, as far as the composer goes: the chip is used up");
			assertEquals(1, f.prompts.size(), "held until the slow turn ends");

			awaitTurnEnds(f, view, mark, 2);
			assertEquals(List.of("text", "image", "resource_link"),
					f.prompts.get(1).blocks().stream().map(block -> block.path("type").asText()).toList());
			assertEquals(Harness.json("{\"type\":\"image\",\"mimeType\":\"image/png\",\"data\":\"" + PNG_1PX + "\"}"),
					f.prompts.get(1).blocks().get(1), "the image itself, not the thumbnail");
			List<JsonNode> agent = messages(view, "agent");
			assertEquals("Received 1 image(s): image/png " + PNG_BYTES + " B; blocks: text,image,resource_link.",
					textOf(agent.get(agent.size() - 1)), "the queued prompt's own turn, with its image");
			assertNull(badge(view, "/scenario images queued"));
			assertEquals(view.snapshot(), view.page.model());
		}
	}
}
