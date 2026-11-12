package io.github.liaoalia.kiroacp.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.liaoalia.kiroacp.core.acp.api.SessionEvent;
import io.github.liaoalia.kiroacp.core.host.Logger;
import io.github.liaoalia.kiroacp.core.model.ToolKind;

/** The parts of the controller that need no agent. */
class SessionPiecesTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Test
	void aSessionIsTitledAfterTheFirstLineOfItsFirstPrompt() {
		assertEquals("/scenario chat", Titles.truncate("/scenario chat"));
		assertEquals("fix the build", Titles.truncate("  fix \t the   build \r\nand then some"));
		assertEquals("", Titles.truncate("\nsecond line only"));
		String forty = "a".repeat(40);
		assertEquals(forty, Titles.truncate(forty));
		assertEquals("a".repeat(39) + "\u2026", Titles.truncate(forty + "b"));
		// The cut never leaves a space before the ellipsis.
		assertEquals("a".repeat(38) + "\u2026", Titles.truncate("a".repeat(38) + " bcd"));
	}

	@Test
	void onlyTheErrorLinesOfTheCliBecomeNotices() {
		assertNull(AgentLog.errorDetail("fake-agent: chat scenario"));
		assertNull(AgentLog.errorDetail("[INFO] all good"));
		assertNull(AgentLog.errorDetail("[ERROR]   "));
		assertEquals("something broke", AgentLog.errorDetail("2026-11-12 [ERROR] something broke"));
		assertEquals("HTTP 407 (Proxy Authorization Required)", AgentLog.errorDetail(
				"[ERROR] [KRS] HTTP 407 for POST https://example.invalid body=<HTML><TITLE>Proxy Authorization Required</TITLE></HTML>"));
		assertEquals("HTTP 503", AgentLog.errorDetail("[ERROR] [KRS] http 503 body={}"));
		assertEquals("request failed", AgentLog.errorDetail("[ERROR] [KRS] request failed body={\"a\":1}"));
		assertEquals(160, AgentLog.errorDetail("[ERROR] " + "x".repeat(500)).length());
	}

	@Test
	void theAgentsLoggerListensToStderrEvenWhenNothingIsTraced() {
		List<String> lines = new ArrayList<>();
		List<String> traced = new ArrayList<>();
		boolean[] tracing = { false };
		Logger host = new Logger() {
			@Override
			public void info(String message) {
			}

			@Override
			public void warn(String message) {
			}

			@Override
			public void error(String message, Throwable cause) {
			}

			@Override
			public boolean traces() {
				return tracing[0];
			}

			@Override
			public void trace(Direction direction, String payload) {
				traced.add(direction + " " + payload);
			}
		};
		AgentLog log = new AgentLog(host, lines::add);
		assertTrue(log.traces(), "or the process would not hand over its stderr");
		log.trace(Logger.Direction.STDERR, "[ERROR] one");
		log.trace(Logger.Direction.RECV, "{}");
		assertEquals(List.of("[ERROR] one"), lines);
		assertEquals(List.of(), traced, "the host's own choice about tracing stands");
		tracing[0] = true;
		log.trace(Logger.Direction.SEND, "{}");
		assertEquals(List.of("SEND {}"), traced);
	}

	@Test
	void toolKindsAreKeptPerSessionAsTheTranscriptWouldHaveThem() throws Exception {
		ToolKindIndex kinds = new ToolKindIndex();
		assertNull(kinds.resolveKind("a", "call-1"));
		kinds.record(update("a", "{\"sessionUpdate\":\"tool_call\",\"toolCallId\":\"call-1\",\"kind\":\"edit\"}"));
		kinds.record(update("b", "{\"sessionUpdate\":\"tool_call\",\"toolCallId\":\"call-1\",\"kind\":\"execute\"}"));
		assertEquals(ToolKind.EDIT, kinds.resolveKind("a", "call-1"));
		assertEquals(ToolKind.EXECUTE, kinds.resolveKind("b", "call-1"), "tool call ids repeat across sessions");
		assertNull(kinds.resolveKind("c", "call-1"));

		// An update that names no kind keeps it; one that names another replaces it.
		kinds.record(update("a", "{\"sessionUpdate\":\"tool_call_update\",\"toolCallId\":\"call-1\",\"status\":\"completed\"}"));
		assertEquals(ToolKind.EDIT, kinds.resolveKind("a", "call-1"));
		kinds.record(update("a", "{\"sessionUpdate\":\"tool_call_update\",\"toolCallId\":\"call-1\",\"kind\":\"delete\"}"));
		assertEquals(ToolKind.DELETE, kinds.resolveKind("a", "call-1"));
		// A new tool_call under the same id is a new card: without a kind it is "other".
		kinds.record(update("a", "{\"sessionUpdate\":\"tool_call\",\"toolCallId\":\"call-1\"}"));
		assertEquals(ToolKind.OTHER, kinds.resolveKind("a", "call-1"));
		// An update for a call never announced makes a card too.
		kinds.record(update("a", "{\"sessionUpdate\":\"tool_call_update\",\"toolCallId\":\"call-2\"}"));
		assertEquals(ToolKind.OTHER, kinds.resolveKind("a", "call-2"));
		// Anything else is not about tool calls.
		kinds.record(update("a", "{\"sessionUpdate\":\"plan\",\"toolCallId\":\"call-3\",\"kind\":\"edit\"}"));
		kinds.record(new SessionEvent.TurnStarted("a"));
		assertNull(kinds.resolveKind("a", "call-3"));

		kinds.forget("a");
		assertNull(kinds.resolveKind("a", "call-1"));
		assertEquals(ToolKind.EXECUTE, kinds.resolveKind("b", "call-1"));
		kinds.clear();
		assertNull(kinds.resolveKind("b", "call-1"));
		assertFalse(kinds.resolveKind(null, null) != null);
	}

	private static SessionEvent update(String sessionId, String json) throws Exception {
		JsonNode update = MAPPER.readTree(json);
		return new SessionEvent.Update(sessionId, update, false);
	}
}
