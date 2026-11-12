package io.github.liaoalia.kiroacp.ui.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The real page in a real browser widget, driven through its DOM.
 *
 * <p>The shell is never opened: nothing appears on the desktop and no input is
 * sent to it. The test thread owns the display and pumps its events.
 */
class WebChatTest {

	private static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(60);

	private Display display;
	private boolean ownDisplay;
	private Shell shell;
	private WebChat chat;
	private final List<JsonNode> fromPage = new ArrayList<>();

	@BeforeEach
	void open() {
		display = Display.getCurrent();
		if (display == null) {
			display = new Display();
			ownDisplay = true;
		}
		shell = new Shell(display);
		shell.setLayout(new FillLayout());
		shell.setSize(480, 640);
		chat = new WebChat(shell, "test-" + System.nanoTime(), fromPage::add);
		shell.layout();
	}

	@AfterEach
	void close() {
		if (shell != null && !shell.isDisposed()) {
			shell.dispose();
		}
		if (ownDisplay && display != null && !display.isDisposed()) {
			display.dispose();
		}
	}

	@Test
	void thePageSaysReadyAndRendersAPostedState() {
		await(() -> received("ready") != null, "the page to post \"ready\"");

		chat.post(state("Rendered by the page under test."));

		await(() -> pageText().contains("Rendered by the page under test."), "the posted state to appear in the DOM");
		assertEquals(Boolean.TRUE, evaluate("return document.querySelector('.composer-box textarea') !== null;"));
	}

	@Test
	void aPromptTypedInThePageReachesTheHost() {
		await(() -> received("ready") != null, "the page to post \"ready\"");
		chat.post(state("Ready for a prompt."));
		await(() -> pageText().contains("Ready for a prompt."), "the posted state to appear in the DOM");

		evaluate("""
				var input = document.querySelector('.composer-box textarea');
				input.value = 'hello from the DOM';
				input.dispatchEvent(new Event('input', { bubbles: true }));
				document.querySelector('.send-btn:not(.is-stop)').click();
				return true;""");

		await(() -> received("prompt") != null, "the prompt to reach the host");
		JsonNode prompt = received("prompt");
		assertEquals("hello from the DOM", prompt.path("text").asText());
		assertTrue(prompt.path("chips").isArray(), prompt.toString());
	}

	private JsonNode received(String type) {
		for (JsonNode message : fromPage) {
			if (type.equals(message.path("type").asText())) {
				return message;
			}
		}
		return null;
	}

	private String pageText() {
		Object text = evaluate("return document.body ? document.body.innerText : '';");
		return text instanceof String s ? s : "";
	}

	private Object evaluate(String script) {
		return ((Browser) chat.getControl()).evaluate(script);
	}

	private void await(BooleanSupplier condition, String what) {
		long deadline = System.nanoTime() + TIMEOUT_NANOS;
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > deadline) {
				fail("Timed out waiting for " + what + "; messages from the page: " + fromPage);
			}
			if (!display.readAndDispatch()) {
				// Wake up again even when the browser has nothing to report.
				display.timerExec(50, () -> {
				});
				display.sleep();
			}
		}
	}

	/** A minimal {@code state} message: one connected session with one agent message. */
	private static ObjectNode state(String agentText) {
		JsonNodeFactory json = JsonNodeFactory.instance;
		ObjectNode state = json.objectNode();
		ObjectNode connection = state.putObject("connection");
		connection.put("status", "ready");
		connection.put("agentName", "Test host");
		connection.put("agentVersion", "0");
		state.put("sessionId", "s1");
		ObjectNode session = state.putArray("sessions").addObject();
		session.put("id", "s1");
		session.put("title", "Test session");
		session.put("status", "idle");
		session.put("updatedAt", 0);
		state.put("turnActive", false);
		ObjectNode item = state.putArray("items").addObject();
		item.put("type", "message");
		ObjectNode message = item.putObject("message");
		message.put("id", "m1");
		message.put("role", "agent");
		ObjectNode segment = message.putArray("segments").addObject();
		segment.put("type", "text");
		segment.put("text", agentText);
		state.putObject("toolCalls");
		state.putArray("plan");
		state.putArray("permissions");
		state.putNull("modes");
		state.putArray("configOptions");
		state.putNull("models");
		state.putArray("commands");
		state.putArray("chips");
		state.putArray("notices");

		ObjectNode out = json.objectNode();
		out.put("type", "state");
		out.set("state", state);
		return out;
	}
}
