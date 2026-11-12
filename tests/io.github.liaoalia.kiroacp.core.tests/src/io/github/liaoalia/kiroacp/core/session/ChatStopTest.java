package io.github.liaoalia.kiroacp.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.liaoalia.kiroacp.core.acp.api.SessionEvent;
import io.github.liaoalia.kiroacp.core.host.Logger;
import io.github.liaoalia.kiroacp.core.model.FromWebview;
import io.github.liaoalia.kiroacp.core.model.MessageSegment;
import io.github.liaoalia.kiroacp.core.model.NoticeView;
import io.github.liaoalia.kiroacp.core.model.ToWebview;
import io.github.liaoalia.kiroacp.core.model.ToolContentView;
import io.github.liaoalia.kiroacp.core.session.Harness.Client;

/**
 * Stop (reference D27; e2e F1.3 and F12.8 to F12.10): the agent is told, the
 * session's commands are killed, and a turn the agent does not end is ended
 * here. The watchdog's five seconds pass when the test says so.
 */
class ChatStopTest {

	@TempDir
	Path dir;

	@Test
	void cancelInterruptsASlowTurnWithStopReasonCancelled() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			Client view = h.openView("view");
			int mark = view.mark();
			view.prompt("/scenario cancel");
			h.await(() -> view.agentChunks(mark).size() >= 3, "three streamed chunks");
			assertTrue(view.snapshot().get("turnActive").asBoolean(), "turnActive while streaming");

			view.send(new FromWebview.Cancel());
			ToWebview.Turn end = view.awaitTurnEnd(mark);
			assertEquals("cancelled", end.stopReason());
			assertFalse(view.snapshot().get("turnActive").asBoolean());
			int count = view.agentChunks(mark).size();
			assertTrue(count < 40, "cancel happened before the scenario finished (" + count + " chunks)");
			assertEquals(0, h.scheduler.pending(Turns.CANCEL_GRACE_MS), "the agent confirmed: no watchdog is left waiting");
			assertEquals(0, view.page.ofType(ToWebview.Notice.class, mark).size());

			// The next turn runs, and nothing of the cancelled one arrives during or after it.
			int next = view.mark();
			assertEquals("end_turn", view.turn("/scenario chat").stopReason());
			assertEquals(List.of("The answer ", "is ", "42."), texts(view.agentChunks(next)));
		}
	}

	@Test
	void stopEndsTheTurnHereWhenTheAgentNeverAnswers() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			Client view = h.openView("view");
			String sessionId = view.sessionId();
			int mark = view.mark();
			view.prompt("/scenario hang");
			h.await(() -> !view.agentChunks(mark).isEmpty(), "the stuck agent's first chunk");

			view.send(new FromWebview.Cancel());
			h.await(() -> h.scheduler.pending(Turns.CANCEL_GRACE_MS) == 1, "the watchdog to be armed");
			h.scheduler.flush();
			// A second Stop arms nothing more.
			view.send(new FromWebview.Cancel());
			h.scheduler.flush();
			assertEquals(1, h.scheduler.pending(Turns.CANCEL_GRACE_MS));
			assertTrue(view.snapshot().get("turnActive").asBoolean(), "the agent gets its five seconds");
			assertEquals(0, view.page.ofType(ToWebview.Turn.class, mark).stream().filter(turn -> !turn.active()).count());

			assertEquals(1, h.scheduler.fire(Turns.CANCEL_GRACE_MS));
			ToWebview.Turn end = view.awaitTurnEnd(mark);
			assertEquals("cancelled", end.stopReason());
			ToWebview.Notice notice = view.page.await(ToWebview.Notice.class, mark, n -> true, "the notice");
			assertEquals(NoticeView.Severity.WARNING, notice.notice().severity());
			assertEquals("Kiro did not confirm the stop; the turn was ended here. Its late reply, if any, is ignored.",
					notice.notice().text());
			List<String> types = view.page.types(mark);
			assertTrue(types.indexOf("notice") < types.lastIndexOf("turn"), "the notice explains the stop it precedes: " + types);
			assertFalse(view.snapshot().get("turnActive").asBoolean());

			// What the abandoned turn still streams must not show up, let alone look like a running turn.
			int late = view.mark();
			JsonNode chunk = Harness.json("{\"sessionUpdate\":\"agent_message_chunk\",\"messageId\":\"m1\","
					+ "\"content\":{\"type\":\"text\",\"text\":\"still here\"}}");
			JsonNode metadata = Harness.json("{\"sessionUpdate\":\"current_mode_update\",\"currentModeId\":\"plan\"}");
			h.ask(() -> {
				h.controller.events.handle(new SessionEvent.Update(sessionId, chunk, false));
				h.controller.events.handle(new SessionEvent.Update(sessionId, chunk, false));
				h.controller.events.handle(new SessionEvent.Update(sessionId, metadata, false));
				return null;
			});
			assertEquals(List.of("modes"), view.page.types(late), "only what is not the turn's output gets through");
			assertEquals(1, h.logger.lines.stream().filter(line -> line.contains("dropping late agent_message_chunk")).count(),
					"logged once");

			// The session is usable again: the next prompt runs as a turn of its own.
			int next = view.mark();
			assertEquals("end_turn", view.turn("/scenario chat").stopReason());
			assertEquals(List.of("The answer ", "is ", "42."), texts(view.agentChunks(next)));
		}
	}

	@Test
	void anAnswerThatLandsAfterTheWatchdogEndedTheTurnIsIgnored() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			Client view = h.openView("view");
			int mark = view.mark();
			view.prompt("/scenario hang-late");
			h.await(() -> !view.agentChunks(mark).isEmpty(), "the first chunk");
			view.send(new FromWebview.Cancel());
			h.await(() -> h.scheduler.pending(Turns.CANCEL_GRACE_MS) == 1, "the watchdog to be armed");
			h.scheduler.fire(Turns.CANCEL_GRACE_MS);
			assertEquals("cancelled", view.awaitTurnEnd(mark).stopReason());

			// A new turn runs to its end while the abandoned prompt is still out (the agent answers it after 6.5 s).
			int next = view.mark();
			assertEquals("end_turn", view.turn("/scenario slow").stopReason());
			h.await(() -> h.logger.has("late prompt response"), "the late answer of the abandoned turn");
			h.scheduler.flush();

			List<ToWebview.Turn> ends = view.page.ofType(ToWebview.Turn.class, next).stream().filter(turn -> !turn.active()).toList();
			assertEquals(1, ends.size(), "the next turn ended exactly once: " + ends);
			assertEquals("end_turn", ends.get(0).stopReason(), "with its own stop reason, not the late one");
			assertTrue(String.join("", texts(view.agentChunks(next))).contains("slow 19 "), "the next turn streamed to its end");
			assertFalse(view.snapshot().get("turnActive").asBoolean(), "idle afterwards");
		}
	}

	@Test
	void stopKillsTheSessionsRunningCommandSoTheAgentCanEndTheTurnItself() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			Client view = h.openView("view");
			int mark = view.mark();
			view.prompt("/scenario hang-terminal");
			ToWebview.ToolCall card = view.page.await(ToWebview.ToolCall.class, mark,
					tool -> tool.call().content().stream().anyMatch(ToolContentView.Terminal.class::isInstance), "the card's terminal");
			String terminalId = ((ToolContentView.Terminal) card.call().content().get(0)).terminalId();
			assertEquals(List.of(terminalId), h.controller.terminals().activeIds());

			view.send(new FromWebview.Cancel());
			// The agent's wait returns because its command was killed; it then answers the cancel itself.
			assertEquals("cancelled", view.awaitTurnEnd(mark).stopReason());
			assertEquals(0, view.page.ofType(ToWebview.Notice.class, mark).size(), "no watchdog notice: the agent confirmed the stop");
			assertEquals(0, h.scheduler.pending(Turns.CANCEL_GRACE_MS));
			h.await(() -> h.logger.has("stop: killed running terminal(s) " + terminalId), "the kill to be logged");
			// The card keeps its terminal and resolves it against the terminal that was released meanwhile.
			JsonNode content = view.snapshot().at("/toolCalls/call-service/content/0");
			assertEquals(terminalId, content.path("terminalId").asText());
			assertTrue(content.has("output"), content.toString());
			assertTrue(h.controller.terminals().snapshot(terminalId, view.sessionId()).exited(), "the command has ended");
		}
	}

	@Test
	void anErrorOnTheAgentsStderrIsShownWhereATurnIsWaiting() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			Client busy = h.openView("busy");
			Client idle = h.openView("idle");
			Logger agentLog = h.controller.lifecycle.agentLog;
			String proxy = "[ERROR] [KRS] HTTP 407 for POST https://example.invalid body=<HTML><TITLE>Proxy Authorization Required</TITLE></HTML>";
			int mark = busy.mark();
			int quiet = idle.mark();
			busy.prompt("/scenario cancel");
			h.await(() -> !busy.agentChunks(mark).isEmpty(), "the turn to stream");

			// As the thread reading the agent's stderr would.
			agentLog.trace(Logger.Direction.STDERR, proxy);
			ToWebview.Notice first = busy.page.await(ToWebview.Notice.class, mark, n -> true, "the notice");
			assertEquals(NoticeView.Severity.WARNING, first.notice().severity());
			assertEquals("kiro-cli reported HTTP 407 (Proxy Authorization Required) while talking to its backend. "
					+ "If the turn does not finish, press Esc and send the message again.", first.notice().text());

			// The same error again within the minute says nothing new; another error does.
			agentLog.trace(Logger.Direction.STDERR, proxy);
			agentLog.trace(Logger.Direction.STDERR, "fake-agent: not an error");
			agentLog.trace(Logger.Direction.STDERR, "[ERROR] connection reset");
			h.await(() -> busy.page.ofType(ToWebview.Notice.class, mark).size() == 2, "the second notice");
			assertTrue(busy.page.ofType(ToWebview.Notice.class, mark).get(1).notice().text().startsWith("kiro-cli reported connection reset "));
			h.clock.addAndGet(AgentLifecycle.STDERR_NOTICE_INTERVAL_MS);
			agentLog.trace(Logger.Direction.STDERR, proxy);
			h.await(() -> busy.page.ofType(ToWebview.Notice.class, mark).size() == 3, "the notice a minute later");

			busy.send(new FromWebview.Cancel());
			assertEquals("cancelled", busy.awaitTurnEnd(mark).stopReason());
			assertEquals(List.of(), idle.page.types(quiet), "a session that waits for nothing is told nothing");
		}
	}

	private static List<String> texts(List<ToWebview.Chunk> chunks) {
		return chunks.stream().map(chunk -> ((MessageSegment.Text) chunk.segment()).text()).toList();
	}
}
