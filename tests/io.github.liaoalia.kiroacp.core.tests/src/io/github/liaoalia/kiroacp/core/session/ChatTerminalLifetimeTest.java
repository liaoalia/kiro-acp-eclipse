package io.github.liaoalia.kiroacp.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.liaoalia.kiroacp.core.acp.api.KiroAgent;
import io.github.liaoalia.kiroacp.core.model.ConnectionStatus;
import io.github.liaoalia.kiroacp.core.model.FromWebview;
import io.github.liaoalia.kiroacp.core.model.ToWebview;
import io.github.liaoalia.kiroacp.core.model.ToolContentView;
import io.github.liaoalia.kiroacp.core.services.TerminalService;
import io.github.liaoalia.kiroacp.core.services.TerminalService.TerminalSnapshot;
import io.github.liaoalia.kiroacp.core.session.Harness.Client;

/**
 * The commands the agent starts are processes of the IDE, not of the agent:
 * whenever a session or the agent goes away, the controller has to end them.
 * Each test starts a command that never ends and looks at the real
 * processes.
 */
class ChatTerminalLifetimeTest {

	@TempDir
	Path dir;

	/** A command that runs and what the operating system runs for it. */
	private record Running(String terminalId, List<ProcessHandle> processes) {

		/** Fails unless every process of the command has ended. */
		void awaitGone(String when) throws Exception {
			try {
				CompletableFuture.allOf(processes.stream().map(ProcessHandle::onExit).toArray(CompletableFuture[]::new))
						.get(Hosts.SECONDS, TimeUnit.SECONDS);
			} catch (TimeoutException e) {
				fail("the command still runs " + when + ": "
						+ processes.stream().filter(ProcessHandle::isAlive).map(ProcessHandle::pid).toList());
			}
		}

		boolean alive() {
			return processes.stream().anyMatch(ProcessHandle::isAlive);
		}
	}

	private static Set<ProcessHandle> descendants() {
		return ProcessHandle.current().descendants().collect(Collectors.toSet());
	}

	private static Running startedSince(Set<ProcessHandle> before, String terminalId) {
		List<ProcessHandle> started = descendants().stream().filter(process -> !before.contains(process)).toList();
		assertTrue(started.stream().anyMatch(ProcessHandle::isAlive), "the command runs as a process of its own: " + started);
		return new Running(terminalId, started);
	}

	/** The turn of {@code hang-terminal}: a tool that waits for a command that never ends. */
	private static Running startService(Harness h, Client view) throws Exception {
		Set<ProcessHandle> before = descendants();
		int mark = view.mark();
		view.prompt("/scenario hang-terminal");
		ToWebview.ToolCall card = view.page.await(ToWebview.ToolCall.class, mark,
				tool -> tool.call().content().stream().anyMatch(ToolContentView.Terminal.class::isInstance), "the card's terminal");
		String terminalId = ((ToolContentView.Terminal) card.call().content().get(0)).terminalId();
		assertEquals(List.of(terminalId), h.controller.terminals().activeIds());
		return startedSince(before, terminalId);
	}

	/** A command started for a session outside any turn, as the agent would ask for it. */
	private static Running startCommand(Harness h, String sessionId) throws Exception {
		Set<ProcessHandle> before = descendants();
		ObjectNode request = Harness.MAPPER.createObjectNode().put("sessionId", sessionId).put("command", "node");
		request.putArray("args").add("-e").add("setTimeout(() => {}, 600000)");
		JsonNode created = h.get(h.controller.services.createTerminal(request));
		return startedSince(before, created.path("terminalId").asText());
	}

	private static JsonNode exitOf(Harness h, String terminalId) throws Exception {
		return h.get(h.controller.terminals().waitForTerminalExit(Harness.MAPPER.createObjectNode().put("terminalId", terminalId)));
	}

	@Test
	void restartingTheAgentEndsTheCommandsOfItsSessionsAndForgetsTheirTerminals() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			Client view = h.openView("view");
			Running service = startService(h, view);

			h.get(h.controller.restart());

			service.awaitGone("after the restart");
			TerminalService terminals = h.controller.terminals();
			assertEquals(List.of(), terminals.activeIds(), "the terminals went with the sessions");
			assertNull(terminals.snapshot(service.terminalId()));
			assertEquals(2, h.launches.size());
			h.awaitSettled(view);
		}
	}

	@Test
	void aCrashOfTheAgentEndsTheCommandsButTheirCardsCanStillBeRead() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			Client view = h.openView("view");
			Client other = h.openView("other");
			String sessionId = view.sessionId();
			Running service = startService(h, view);

			int mark = other.mark();
			other.prompt("/scenario crash");
			other.page.await(ToWebview.Connection.class, mark, c -> c.connection().status() == ConnectionStatus.ERROR,
					"the connection to fail");

			service.awaitGone("after the agent crashed");
			h.await(() -> h.logger.has("the agent is gone: killed running terminal(s) " + service.terminalId()), "the kill to be logged");
			// The session and its card are still shown: the terminal is kept, as one that ended.
			exitOf(h, service.terminalId());
			TerminalService terminals = h.controller.terminals();
			assertEquals(List.of(service.terminalId()), terminals.activeIds());
			TerminalSnapshot snapshot = terminals.snapshot(service.terminalId(), sessionId);
			assertNotNull(snapshot);
			assertTrue(snapshot.exited(), "the command has ended");
			h.scheduler.flush();
			assertEquals(sessionId, view.sessionId());
			JsonNode content = view.snapshot().at("/toolCalls/call-service/content/0");
			assertEquals(service.terminalId(), content.path("terminalId").asText());
			assertTrue(content.has("output"), content.toString());
		}
	}

	@Test
	void stoppingTheAgentOnPurposeEndsTheCommandsToo() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			Client view = h.openView("view");
			String sessionId = view.sessionId();
			Running service = startService(h, view);

			h.get(h.controller.shutdownAgent());

			service.awaitGone("after the agent was stopped");
			exitOf(h, service.terminalId());
			assertEquals(List.of(service.terminalId()), h.controller.terminals().activeIds(), "the sessions stay, and so do their terminals");
			assertTrue(h.controller.terminals().snapshot(service.terminalId(), sessionId).exited());
			h.await(() -> h.logger.has("agent shutdown: killed running terminal(s) " + service.terminalId()), "the kill to be logged");
		}
	}

	@Test
	void closingASessionInTheMiddleOfATurnEndsItsCommand() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			Client view = h.openView("view");
			String sessionId = view.sessionId();
			Running service = startService(h, view);

			int mark = view.mark();
			view.send(new FromWebview.CloseSession(sessionId));

			service.awaitGone("after its session was closed");
			h.await(() -> h.logger.has("session " + sessionId + " closed"), "the close to be logged");
			assertEquals(List.of(), h.controller.terminals().activeIds(), "a closed session keeps no terminal");
			assertNull(h.controller.terminals().snapshot(service.terminalId()));
			// The view goes on with a session of its own.
			view.page.await(ToWebview.Session.class, mark, s -> s.sessionId() != null && !sessionId.equals(s.sessionId()),
					"the replacement");
			h.awaitSettled(view);
			assertEquals("end_turn", view.turn("/scenario chat").stopReason());
		}
	}

	@Test
	void closingAnIdleSessionEndsItsCommandAndLeavesTheOtherSessionsCommandsAlone() throws Exception {
		Running survivor;
		try (Harness h = Harness.launch(dir)) {
			Client closing = h.openView("closing");
			Client staying = h.openView("staying");
			String sessionId = closing.sessionId();
			Running command = startCommand(h, sessionId);
			survivor = startCommand(h, staying.sessionId());
			assertFalse(h.ask(() -> h.controller.turnActive(sessionId)), "no turn is running");

			h.get(h.controller.closeSession(sessionId));

			command.awaitGone("after its session was closed");
			TerminalService terminals = h.controller.terminals();
			assertEquals(List.of(survivor.terminalId()), terminals.activeIds());
			assertNull(terminals.snapshot(command.terminalId()));
			assertFalse(terminals.snapshot(survivor.terminalId(), staying.sessionId()).exited());
			assertTrue(survivor.alive(), "the command of the other session runs on");
		}
		// The harness shut the controller down.
		survivor.awaitGone("after the shutdown");
	}

	@Test
	void shutdownEndsTheCommandsWithoutWaitingForTheAgentToStop() throws Exception {
		try (Harness h = Harness.launch(dir)) {
			CompletableFuture<Void> mayStop = new CompletableFuture<>();
			h.agentWrapper = agent -> stoppingOnlyAfter(mayStop, agent);
			Client view = h.openView("view");
			Running service = startService(h, view);

			CompletableFuture<Void> down = h.controller.shutdown();
			try {
				service.awaitGone("while the agent is still being stopped");
				assertFalse(down.isDone(), "the agent has not stopped yet");
				assertEquals(List.of(), h.controller.terminals().activeIds());
				// And nothing can be started in the time the agent has left.
				ObjectNode request = Harness.MAPPER.createObjectNode().put("sessionId", "any").put("command", "echo late");
				CompletableFuture<JsonNode> late = h.controller.services.createTerminal(request);
				assertEquals("The terminal service is closed",
						late.handle((created, error) -> Async.message(error)).get(Hosts.SECONDS, TimeUnit.SECONDS));
			} finally {
				mayStop.complete(null);
			}
			h.get(down);
		}
	}

	/** The agent, except that it only starts to stop once {@code mayStop} completes: an agent that is slow to go. */
	private static KiroAgent stoppingOnlyAfter(CompletableFuture<Void> mayStop, KiroAgent agent) {
		return (KiroAgent) Proxy.newProxyInstance(KiroAgent.class.getClassLoader(), new Class<?>[] { KiroAgent.class },
				(proxy, method, args) -> {
					if (method.getName().equals("stop")) {
						return mayStop.thenCompose(allowed -> agent.stop());
					}
					try {
						return method.invoke(agent, args);
					} catch (InvocationTargetException e) {
						throw e.getCause();
					}
				});
	}
}
