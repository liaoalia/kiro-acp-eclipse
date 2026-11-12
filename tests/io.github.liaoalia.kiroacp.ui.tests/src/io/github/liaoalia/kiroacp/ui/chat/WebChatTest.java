package io.github.liaoalia.kiroacp.ui.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
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
 * <p>No shell is ever opened: nothing appears on the desktop and no input is
 * sent to it. The clipboard and the system browser are replaced by lists, so
 * neither is touched. The test thread owns the display and pumps its events.
 */
class WebChatTest {

	private static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(60);
	private static final String TEXTAREA = "document.querySelector('.composer-box textarea')";

	private Display display;
	private boolean ownDisplay;
	private Shell shell;
	private WebChat chat;
	private final List<JsonNode> fromPage = new ArrayList<>();
	private final List<String> copied = new ArrayList<>();
	private final List<String> opened = new ArrayList<>();
	/** Never the real clipboard, never the real system browser. */
	private final WebChat.Seams seams = WebChat.Seams.defaults().withClipboard(copied::add).withOpener(opened::add);

	@BeforeEach
	void createShell() {
		display = Display.getCurrent();
		if (display == null) {
			display = new Display();
			ownDisplay = true;
		}
		shell = newShell();
	}

	@AfterEach
	void close() {
		for (Shell each : display.getShells()) {
			each.dispose();
		}
		if (ownDisplay && !display.isDisposed()) {
			display.dispose();
		}
	}

	@Test
	void thePageSaysReadyAndRendersAPostedState() {
		startAndAwaitReady();

		chat.post(state("Rendered by the page under test."));

		await(() -> pageText().contains("Rendered by the page under test."), "the posted state to appear in the DOM");
		assertEquals(Boolean.TRUE, evaluate("return " + TEXTAREA + " !== null;"));
	}

	@Test
	void aPromptTypedInThePageReachesTheHost() {
		startAndAwaitReady();
		show("Ready for a prompt.");

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

	// --- clipboard ---

	@Test
	void theCopyButtonOfACodeBlockCopiesThroughTheHost() {
		startAndAwaitReady();
		show("Some code:\n\n```js\nlet answer = 42;\n```\n");
		assertCopyingIsTheShims();

		evaluate("document.querySelector('.copy-btn').click(); return true;");

		await(() -> !copied.isEmpty(), "the code to reach the host's clipboard");
		assertEquals(List.of(evaluate("return document.querySelector('.codeblock pre').textContent;")), copied);
		assertTrue(copied.get(0).contains("let answer = 42;"), copied.toString());
		// The page waits for the host's answer before it says so.
		await(() -> "Copied".equals(evaluate("return document.querySelector('.copy-btn .copy-label').textContent;")),
				"the button to say \"Copied\"");
		assertTrue(fromPage.stream().noneMatch(m -> m.path("type").asText().startsWith("host:")),
				"shim messages are not chat protocol: " + fromPage);
	}

	@Test
	void aClipboardThatRefusesIsReportedToThePage() {
		chat = new WebChat(shell, newId(), fromPage::add, seams.withClipboard(text -> {
			throw new SWTError(DND.ERROR_CANNOT_SET_CLIPBOARD);
		}));
		shell.layout();
		await(() -> received("ready") != null, "the page to post \"ready\"");
		assertCopyingIsTheShims();

		// Called directly: the page's own button would go on to try the browser's
		// copy command, and that one writes to the real clipboard.
		evaluate("""
				window.__copyOutcome = 'pending';
				navigator.clipboard.writeText('refused').then(
					function () { window.__copyOutcome = 'resolved'; },
					function () { window.__copyOutcome = 'rejected'; });
				return true;""");

		await(() -> !"pending".equals(evaluate("return window.__copyOutcome;")), "the copy to be answered");
		assertEquals("rejected", evaluate("return window.__copyOutcome;"));
	}

	// --- links ---

	@Test
	void aWebLinkIsHandedToTheHostAndThePageStaysWhereItIs() {
		startAndAwaitReady();
		show("See [the docs](https://example.com/docs/page?a=1&b=two#part) for more.");
		String before = chat.browser().getUrl();

		assertEquals(Boolean.TRUE, evaluate("""
				var link = document.querySelector('a[href^="https://example.com/"]');
				if (!link) return false;
				link.click();
				return true;"""), "the rendered transcript has the link");

		await(() -> !opened.isEmpty(), "the host to be asked to open the link");
		assertEquals(List.of("https://example.com/docs/page?a=1&b=two#part"), opened);
		assertStillTheChatPage(before);
	}

	@Test
	void linksThatAreNotWebLinksGoNowhere() {
		startAndAwaitReady();
		show("A transcript.");
		String before = chat.browser().getUrl();

		evaluate("""
				var targets = ['file:///C:/Windows/win.ini', 'javascript:window.__ran = true', 'ftp://example.com/x',
					'//example.com/protocol-relative', 'other.html'];
				for (var i = 0; i < targets.length; i++) {
					var link = document.createElement('a');
					link.setAttribute('href', targets[i]);
					link.textContent = 'link ' + i;
					document.body.appendChild(link);
					link.click();
				}
				// A page that got past the shim still does not get past the host.
				window.__kiroHost(JSON.stringify({ type: 'host:openExternal', url: 'file:///C:/Windows/win.ini' }));
				window.__kiroHost(JSON.stringify({ type: 'host:openExternal', url: 'javascript:alert(1)' }));
				window.__kiroHost(JSON.stringify({ type: 'host:openExternal', url: ' https://example.com/leading-space' }));
				window.__kiroHost(JSON.stringify({ type: 'host:unknown', url: 'https://example.com/unknown' }));
				// Messages arrive in order, so once this one has, the others have been dealt with.
				var last = document.createElement('a');
				last.setAttribute('href', 'http://example.com/last');
				document.body.appendChild(last);
				last.click();
				return true;""");

		await(() -> !opened.isEmpty(), "the last, web, link to reach the host");
		assertEquals(List.of("http://example.com/last"), opened);
		assertNull(evaluate("return window.__ran === undefined ? null : 'ran';"));
		assertTrue(fromPage.stream().noneMatch(m -> m.path("type").asText().startsWith("host:")), fromPage.toString());
		assertStillTheChatPage(before);
	}

	// --- theme ---

	@Test
	void aLightParentGivesTheLightTheme() {
		shell.setBackground(new Color(250, 250, 250));
		shell.setForeground(new Color(20, 20, 20));
		startAndAwaitReady();

		await(() -> "#fafafa".equals(inlineVariable("--vscode-sideBar-background")), "the theme to be applied");
		assertEquals("vscode-light", evaluate("return document.body.className;"));
		assertEquals("#141414", inlineVariable("--vscode-foreground"));
		// Not set by the host, so the light defaults of theme.css show through.
		assertEquals("#ffffff", computedVariable("--vscode-editor-background"));
	}

	@Test
	void aDarkParentGivesTheDarkTheme() {
		shell.setBackground(new Color(30, 30, 30));
		shell.setForeground(new Color(220, 220, 220));
		startAndAwaitReady();

		await(() -> "#1e1e1e".equals(inlineVariable("--vscode-sideBar-background")), "the theme to be applied");
		assertEquals("vscode-dark", evaluate("return document.body.className;"));
		assertEquals("#dcdcdc", inlineVariable("--vscode-foreground"));
		assertEquals("#1e1e1e", computedVariable("--vscode-editor-background"));
		// The fonts are Eclipse's in either theme.
		assertTrue(inlineVariable("--vscode-font-family").contains(
				JFaceResources.getDefaultFont().getFontData()[0].getName()), inlineVariable("--vscode-font-family"));
		assertTrue(inlineVariable("--vscode-editor-font-size").endsWith("px"));
	}

	@Test
	void theThemeIsAppliedAgainWhenEclipsesColoursChange() {
		shell.setBackground(new Color(250, 250, 250));
		shell.setForeground(new Color(20, 20, 20));
		startAndAwaitReady();
		await(() -> "#fafafa".equals(inlineVariable("--vscode-sideBar-background")), "the light theme to be applied");

		// What a theme switch does: the widgets get new colours and the registry announces a change.
		shell.setBackground(new Color(30, 30, 30));
		shell.setForeground(new Color(220, 220, 220));
		String key = "io.github.liaoalia.kiroacp.tests.tick";
		RGB old = JFaceResources.getColorRegistry().getRGB(key);
		JFaceResources.getColorRegistry().put(key, new RGB(1, 2, 3).equals(old) ? new RGB(3, 2, 1) : new RGB(1, 2, 3));

		await(() -> "vscode-dark".equals(evaluate("return document.body.className;")), "the dark theme to be applied");
		assertEquals("#1e1e1e", inlineVariable("--vscode-sideBar-background"));
		assertEquals("#dcdcdc", inlineVariable("--vscode-foreground"));
	}

	@Test
	void theThemeIsAppliedAgainWhenEclipsesFontsChange() {
		startAndAwaitReady();
		await(() -> !inlineVariable("--vscode-sideBar-background").isEmpty(), "the theme to be applied");

		shell.setBackground(new Color(30, 30, 30));
		String key = "io.github.liaoalia.kiroacp.tests.font";
		int height = JFaceResources.getFontRegistry().hasValueFor(key)
				? JFaceResources.getFontRegistry().getFontData(key)[0].getHeight() + 1
				: 9;
		JFaceResources.getFontRegistry().put(key,
				new FontData[] { new FontData("Arial", height, SWT.NORMAL) });

		await(() -> "vscode-dark".equals(evaluate("return document.body.className;")), "the theme to follow");
	}

	// --- persisted page state ---

	@Test
	void theDraftSurvivesAReloadAndBelongsToOneViewOnly() {
		String id = newId();
		String key = "kiroAcp.webviewState." + id;
		chat = new WebChat(shell, id, fromPage::add, seams);
		shell.layout();
		await(() -> received("ready") != null, "the page to post \"ready\"");
		try {
			evaluate("var input = " + TEXTAREA + ";"
					+ "input.value = 'a draft to keep';"
					+ "input.dispatchEvent(new Event('input', { bubbles: true }));"
					+ "return true;");
			// The page saves its draft a moment after the last keystroke.
			await(() -> String.valueOf(evaluate("return window.localStorage.getItem('" + key + "') || '';"))
					.contains("a draft to keep"), "the draft to be stored under this view's id");

			fromPage.clear();
			chat.reload();
			await(() -> received("ready") != null, "the reloaded page to post \"ready\"");
			assertEquals("a draft to keep", evaluate("return " + TEXTAREA + ".value;"), "same view, new document");

			// Another view: its own browser widget, the same page, another id.
			List<JsonNode> other = new ArrayList<>();
			WebChat stranger = new WebChat(newShell(), newId(), other::add, seams);
			await(() -> !other.isEmpty(), "the other view's page to post \"ready\"");
			assertEquals("", stranger.browser().evaluate("return " + TEXTAREA + ".value;"), "another view's draft");

			// The same view in a browser widget of its own, as after closing and reopening it.
			List<JsonNode> again = new ArrayList<>();
			WebChat reopened = new WebChat(newShell(), id, again::add, seams);
			await(() -> !again.isEmpty(), "the reopened view's page to post \"ready\"");
			assertEquals("a draft to keep", reopened.browser().evaluate("return " + TEXTAREA + ".value;"),
					"same id, another browser widget");
		} finally {
			// The browser's storage outlives the test run.
			evaluate("window.localStorage.removeItem('" + key + "'); return true;");
		}
	}

	// --- failure to show the page ---

	@Test
	void aBrowserThatCannotBeCreatedLeavesAMessageAndReloadTriesAgain() {
		int[] attempts = { 0 };
		WebChat.Seams defaults = seams;
		chat = new WebChat(shell, newId(), fromPage::add, seams.withBrowsers(parent -> {
			if (attempts[0]++ == 0) {
				throw new SWTError(SWT.ERROR_NO_HANDLES, "WebView2 runtime not found");
			}
			return defaults.browsers().apply(parent);
		}));
		shell.layout();

		assertNull(chat.browser());
		assertTrue(failureText().contains("WebView2 runtime not found"), failureText());
		// Nothing to talk to, and nothing throws.
		chat.post(state("dropped"));
		chat.applyTheme();
		chat.setFocus();

		reloadLink().notifyListeners(SWT.Selection, new Event());

		await(() -> received("ready") != null, "the page to load after \"Reload\"");
		assertEquals(2, attempts[0]);
		assertNotNull(chat.browser());
		assertNull(find(chat.getControl(), Link.class), "the message is gone");
		show("Back in business.");
	}

	@Test
	void aPageThatCannotBeLocatedLeavesAMessageAndReloadTriesAgain() {
		int[] attempts = { 0 };
		WebChat.Seams defaults = seams;
		chat = new WebChat(shell, newId(), fromPage::add, seams.withPage(() -> {
			if (attempts[0]++ == 0) {
				throw new IOException("web/ is missing from the plugin");
			}
			return defaults.page().locate();
		}));
		shell.layout();

		assertNull(chat.browser());
		assertTrue(failureText().contains("web/ is missing from the plugin"), failureText());

		reloadLink().notifyListeners(SWT.Selection, new Event());

		await(() -> received("ready") != null, "the page to load after \"Reload\"");
	}

	@Test
	void aPageFileThatIsNotThereLeavesAMessageInsteadOfABrowserError() {
		File nowhere = new File(System.getProperty("java.io.tmpdir"), "kiroacp-no-such-dir-" + System.nanoTime()
				+ File.separator + "index.html");
		chat = new WebChat(shell, newId(), fromPage::add, seams.withPage(() -> nowhere));

		assertNull(chat.browser());
		assertTrue(failureText().contains("missing"), failureText());
		assertNotNull(reloadLink());
	}

	// --- focus ---

	@Test
	void setFocusPutsTheCaretInTheInputBox() {
		startAndAwaitReady();
		show("Ready.");
		evaluate("if (document.activeElement) document.activeElement.blur(); return true;");
		assertEquals(Boolean.FALSE, evaluate("return document.activeElement === " + TEXTAREA + ";"));

		chat.setFocus();

		await(() -> Boolean.TRUE.equals(evaluate("return document.activeElement === " + TEXTAREA + ";")),
				"the input box to be the active element");
	}

	@Test
	void focusAskedForBeforeThePageIsUpIsGivenOnceItIs() {
		// Without the request the page leaves the focus alone: the control for the claim below.
		start();
		await(() -> received("ready") != null, "the page to post \"ready\"");
		assertEquals(Boolean.FALSE, evaluate("return document.activeElement === " + TEXTAREA + ";"));

		List<JsonNode> early = new ArrayList<>();
		WebChat eager = new WebChat(newShell(), newId(), early::add, seams);
		eager.setFocus();

		await(() -> !early.isEmpty() && Boolean.TRUE.equals(
				eager.browser().evaluate("return document.activeElement === " + TEXTAREA + ";")),
				"the input box to be the active element once the page is up");
	}

	// --- helpers ---

	private Shell newShell() {
		Shell created = new Shell(display);
		created.setLayout(new FillLayout());
		created.setSize(480, 640);
		return created;
	}

	private static String newId() {
		return "test-" + System.nanoTime();
	}

	private void start() {
		chat = new WebChat(shell, newId(), fromPage::add, seams);
		shell.layout();
	}

	private void startAndAwaitReady() {
		start();
		await(() -> received("ready") != null, "the page to post \"ready\"");
	}

	/** Posts a state with one agent message and waits until the page has rendered it. */
	private void show(String agentMarkdown) {
		String marker = "marker" + System.nanoTime();
		chat.post(state(agentMarkdown + "\n\n" + marker));
		await(() -> pageText().contains(marker), "the posted state to appear in the DOM");
	}

	/** The page is the same document as before and still does what the host asks. */
	private void assertStillTheChatPage(String urlBefore) {
		assertEquals(urlBefore, chat.browser().getUrl());
		assertTrue(urlBefore.startsWith("file:") && urlBefore.contains("index.html?view=test-"), urlBefore);
		show("Still here after the click.");
	}

	/**
	 * Guards the real clipboard: with the browser's own function in place a
	 * copy in the page would write to it.
	 */
	private void assertCopyingIsTheShims() {
		assertEquals(Boolean.FALSE, evaluate(
				"return Function.prototype.toString.call(navigator.clipboard.writeText).indexOf('[native code]') >= 0;"),
				"navigator.clipboard.writeText must be the shim's");
	}

	private String inlineVariable(String name) {
		return String.valueOf(evaluate("return document.body.style.getPropertyValue('" + name + "').trim();"));
	}

	private String computedVariable(String name) {
		return String.valueOf(
				evaluate("return getComputedStyle(document.body).getPropertyValue('" + name + "').trim();"));
	}

	private String failureText() {
		Label label = find(chat.getControl(), Label.class);
		return label == null ? "" : label.getText();
	}

	private Link reloadLink() {
		Link link = find(chat.getControl(), Link.class);
		assertNotNull(link, "a Reload link");
		assertTrue(link.getText().contains("Reload"), link.getText());
		assertFalse(link.isDisposed());
		return link;
	}

	private static <T extends Control> T find(Control root, Class<T> type) {
		if (type.isInstance(root)) {
			return type.cast(root);
		}
		if (root instanceof Composite composite && !(root instanceof Browser)) {
			for (Control child : composite.getChildren()) {
				T found = find(child, type);
				if (found != null) {
					return found;
				}
			}
		}
		return null;
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
		return chat.browser().evaluate(script);
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
