package io.github.liaoalia.kiroacp.ui.chat;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.URIUtil;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.BrowserFunction;
import org.eclipse.swt.browser.LocationEvent;
import org.eclipse.swt.browser.LocationListener;
import org.eclipse.swt.browser.ProgressListener;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.themes.IThemeManager;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The chat page inside an SWT {@link Browser}.
 *
 * <p>The page is the webview bundle of the VS Code extension, unchanged, plus a
 * small shim ({@code web/bridge.js}). Messages are the JSON protocol of the
 * extension ({@code ToWebview} / {@code FromWebview}): the page calls the
 * {@code __kiroHost} browser function with one JSON string per message, and
 * {@link #post} delivers a message to the page.
 *
 * <p>The browser only ever shows that one local page. SWT installs a browser
 * function on every document the widget loads, so a document from anywhere
 * else would inherit the bridge: navigation away is refused, new windows are
 * refused, and messages flow in either direction only while the chat page is
 * the current document.
 *
 * <p>When the browser cannot be created or the page is not there, the same
 * control shows a plain message with a "Reload" link instead.
 *
 * <p>All methods must be called on the UI thread.
 */
public final class WebChat {

	/** Prefix of shim-level messages, which are handled here and are not part of the chat protocol. */
	static final String HOST_PREFIX = "host:";
	/** Open a link outside the page: {@code url}. */
	static final String OPEN_EXTERNAL = "host:openExternal";
	/** Put {@code text} on the clipboard; answered through {@code __kiroCopied(id, ok)}. */
	static final String COPY = "host:copy";

	private static final ILog LOG = ILog.of(WebChat.class);
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** Where the chat page is on disk. */
	interface PageSource {
		File locate() throws IOException;
	}

	/**
	 * What the chat reaches outside itself for, replaceable in tests.
	 *
	 * @param browsers  creates the browser widget; throws when the platform has none
	 * @param page      locates the page
	 * @param clipboard receives text the user copied in the page; throws when it cannot take it
	 * @param opener    receives a web link the user clicked, only ever {@code http(s)}
	 */
	record Seams(Function<Composite, Browser> browsers, PageSource page, Consumer<String> clipboard,
			Consumer<String> opener) {

		static Seams defaults() {
			// SWT.EDGE selects WebView2 on Windows before Eclipse 4.35, where Internet
			// Explorer was still the default; other platforms ignore the hint.
			return new Seams(parent -> new Browser(parent, SWT.EDGE), WebChat::pageFile, WebChat::copyToClipboard,
					WebChat::launch);
		}

		Seams withBrowsers(Function<Composite, Browser> replacement) {
			return new Seams(replacement, page, clipboard, opener);
		}

		Seams withPage(PageSource replacement) {
			return new Seams(browsers, replacement, clipboard, opener);
		}

		Seams withClipboard(Consumer<String> replacement) {
			return new Seams(browsers, page, replacement, opener);
		}

		Seams withOpener(Consumer<String> replacement) {
			return new Seams(browsers, page, clipboard, replacement);
		}
	}

	private final Composite parent;
	private final Display display;
	private final Composite container;
	private final String instanceId;
	private final Consumer<JsonNode> onMessage;
	private final Seams seams;
	private final AtomicBoolean themeQueued = new AtomicBoolean();

	/** {@code null} while the failure message is shown instead. */
	private Browser browser;
	private Path page;
	/** The current document is the chat page and has run far enough to receive messages. */
	private boolean loaded;
	/** Focus was asked for before the page could take it. */
	private boolean focusPending;

	/**
	 * @param instanceId distinguishes this chat from others showing the same page;
	 *                   the page keeps its persisted UI state (draft, history) under it
	 */
	public WebChat(Composite parent, String instanceId, Consumer<JsonNode> onMessage) {
		this(parent, instanceId, onMessage, Seams.defaults());
	}

	WebChat(Composite parent, String instanceId, Consumer<JsonNode> onMessage, Seams seams) {
		this.parent = parent;
		this.display = parent.getDisplay();
		this.instanceId = instanceId;
		this.onMessage = onMessage;
		this.seams = seams;
		this.container = new Composite(parent, SWT.NONE);
		container.setLayout(new FillLayout());
		followTheme();
		build();
	}

	/** The one control of the chat, whether it shows the page or the failure message. */
	public Control getControl() {
		return container;
	}

	/** The browser widget, or {@code null} while the failure message is shown. */
	Browser browser() {
		return browser;
	}

	/** Throws away the page, or the failure message, and starts again from nothing. */
	public void reload() {
		if (container.isDisposed()) {
			return;
		}
		build();
		container.layout(true, true);
	}

	/** Puts the keyboard focus into the page's input box. */
	public boolean setFocus() {
		if (container.isDisposed()) {
			return false;
		}
		if (browser == null || browser.isDisposed()) {
			return container.setFocus();
		}
		boolean focused = browser.setFocus();
		// The widget having focus does not put the caret anywhere in the document.
		if (loaded) {
			focusInput();
		} else {
			focusPending = true;
		}
		return focused;
	}

	/** Sends one protocol message to the page. Dropped unless the chat page is up. */
	public void post(JsonNode message) {
		if (!loaded || !onChatPage()) {
			return;
		}
		execute("window.__kiroReceive && window.__kiroReceive(" + toScriptLiteral(message) + ");");
	}

	/** Pushes Eclipse's colours and fonts into the page; done again by itself when they change. */
	public void applyTheme() {
		if (!onChatPage()) {
			return;
		}
		ChatTheme.Palette palette = ChatTheme.read(parent);
		ObjectNode vars = MAPPER.createObjectNode();
		for (Map.Entry<String, String> entry : ChatTheme.variables(palette).entrySet()) {
			vars.put(entry.getKey(), entry.getValue());
		}
		execute("window.__kiroTheme && window.__kiroTheme("
				+ (ChatTheme.isDark(palette.background()) ? "\"dark\"" : "\"light\"") + ", " + toScriptLiteral(vars)
				+ ");");
	}

	private void build() {
		clear();
		File file;
		try {
			file = seams.page().locate();
		} catch (IOException e) {
			LOG.error("Kiro chat page could not be located", e);
			showFailure("The Kiro chat page could not be located: " + e.getMessage());
			return;
		}
		if (!ChatPageLocation.isLocal(file.toPath())) {
			// page stays null: nothing is the chat page and the bridge serves no one.
			LOG.error("Kiro chat cannot run from a network location: " + file);
			showFailure("Kiro Chat cannot run from a network location. "
					+ "Install Eclipse and this plugin on a local drive.");
			return;
		}
		if (!file.isFile()) {
			LOG.error("Kiro chat page is missing: " + file);
			showFailure("The Kiro chat page is missing: " + file);
			return;
		}
		Browser created;
		try {
			created = seams.browsers().apply(container);
		} catch (SWTError | RuntimeException e) {
			// SWTError is how SWT reports a missing browser engine (no WebView2 runtime).
			LOG.error("The embedded browser for Kiro chat could not be created", e);
			showFailure("The embedded browser could not be created: " + e.getMessage()
					+ "\nOn Windows, Kiro Chat needs the Microsoft Edge WebView2 Runtime.");
			return;
		}
		wire(created);
		browser = created;
		page = file.toPath();
		String url = file.toURI() + "?view=" + URLEncoder.encode(instanceId, StandardCharsets.UTF_8);
		if (!created.setUrl(url)) {
			LOG.error("The embedded browser refused to load the Kiro chat page: " + file);
			showFailure("The Kiro chat page could not be loaded.");
		}
	}

	/** Back to nothing shown: no browser, so no document the bridge would serve. */
	private void clear() {
		for (Control child : container.getChildren()) {
			child.dispose();
		}
		browser = null;
		page = null;
		loaded = false;
	}

	private void wire(Browser created) {
		new BrowserFunction(created, "__kiroHost") {
			@Override
			public Object function(Object[] arguments) {
				if (arguments.length > 0 && arguments[0] instanceof String json) {
					// Never re-enter the browser from inside its own callback.
					display.asyncExec(() -> {
						// A browser replaced by a reload meanwhile has nothing more to say.
						if (browser == created) {
							dispatch(json);
						}
					});
				}
				return null;
			}
		};

		created.addLocationListener(new LocationListener() {
			@Override
			public void changing(LocationEvent event) {
				if (isChatPage(event.location)) {
					// A (re)load of the page: the new document starts from nothing.
					loaded = false;
				} else if (!isBlank(event.location)) {
					event.doit = false;
					LOG.warn("Refused to navigate the chat page to " + abbreviate(event.location));
				}
			}

			@Override
			public void changed(LocationEvent event) {
				if (event.top && !isChatPage(event.location)) {
					loaded = false;
				}
			}
		});
		// required with no browser supplied cancels the open: the page never gets a second window.
		created.addOpenWindowListener(event -> event.required = true);

		created.addProgressListener(ProgressListener.completedAdapter(event -> {
			// Queued so the script does not run inside the browser's own callback.
			display.asyncExec(() -> {
				if (browser == created && onChatPage()) {
					pageIsUp();
					execute("window.__kiroFlush && window.__kiroFlush();");
				}
			});
		}));
	}

	/** Replaces whatever the control shows with a message and a way to try again. */
	private void showFailure(String message) {
		clear();
		Composite pane = new Composite(container, SWT.NONE);
		pane.setLayout(new GridLayout());
		Label text = new Label(pane, SWT.WRAP);
		text.setText(message);
		text.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
		Link retry = new Link(pane, SWT.NONE);
		retry.setText("<a>Reload</a>");
		// The reload disposes the link, so it must not run inside the link's own event.
		retry.addListener(SWT.Selection, event -> display.asyncExec(this::reload));
		container.layout(true, true);
	}

	/** Re-applies the theme whenever Eclipse's colours or fonts, or the system's, change. */
	private void followTheme() {
		IPropertyChangeListener onChange = event -> queueTheme();
		Listener onSettings = event -> queueTheme();
		JFaceResources.getColorRegistry().addListener(onChange);
		JFaceResources.getFontRegistry().addListener(onChange);
		display.addListener(SWT.Settings, onSettings);
		// Tests run without a workbench.
		IThemeManager themes = PlatformUI.isWorkbenchRunning() ? PlatformUI.getWorkbench().getThemeManager() : null;
		if (themes != null) {
			themes.addPropertyChangeListener(onChange);
		}
		container.addDisposeListener(event -> {
			JFaceResources.getColorRegistry().removeListener(onChange);
			JFaceResources.getFontRegistry().removeListener(onChange);
			if (!display.isDisposed()) {
				display.removeListener(SWT.Settings, onSettings);
			}
			if (themes != null) {
				themes.removePropertyChangeListener(onChange);
			}
		});
	}

	/**
	 * A theme switch changes many registry entries one after another, and the
	 * widgets are restyled around the same time: one re-application, afterwards.
	 */
	private void queueTheme() {
		if (!themeQueued.compareAndSet(false, true)) {
			return;
		}
		try {
			display.asyncExec(() -> {
				themeQueued.set(false);
				if (!container.isDisposed() && loaded) {
					applyTheme();
				}
			});
		} catch (SWTException e) {
			// The display is gone, and the page with it.
			themeQueued.set(false);
		}
	}

	/**
	 * The page runs its script, and posts "ready", before the load-completed
	 * event fires, so whichever comes first marks it as able to receive.
	 */
	private void pageIsUp() {
		if (!loaded) {
			loaded = true;
			applyTheme();
			if (focusPending) {
				focusPending = false;
				focusInput();
			}
		}
	}

	private void focusInput() {
		post(JsonNodeFactory.instance.objectNode().put("type", "focusInput"));
	}

	private void dispatch(String json) {
		// Whatever document called the function, only the chat page is listened to.
		if (!onChatPage()) {
			return;
		}
		pageIsUp();
		try {
			JsonNode message = MAPPER.readTree(json);
			if (message == null || !message.isObject() || !message.path("type").isTextual()) {
				return;
			}
			String type = message.path("type").asText();
			if (OPEN_EXTERNAL.equals(type)) {
				openExternal(message.path("url").asText(""));
			} else if (COPY.equals(type)) {
				copy(message.path("id"), message.path("text"));
			} else if (type.startsWith(HOST_PREFIX)) {
				LOG.warn("Ignored an unknown shim message: " + abbreviate(type));
			} else {
				onMessage.accept(message);
			}
		} catch (JsonProcessingException e) {
			LOG.warn("Ignored a malformed message from the chat page", e);
		} catch (RuntimeException e) {
			LOG.error("Handling a chat page message failed", e);
		}
	}

	/** Links the user clicked in the transcript open in the system browser, web links only. */
	private void openExternal(String url) {
		String lower = url.toLowerCase(Locale.ROOT);
		if (lower.startsWith("https://") || lower.startsWith("http://")) {
			seams.opener().accept(url);
		} else {
			LOG.warn("Refused to open a link that is not http(s): " + abbreviate(url));
		}
	}

	/** Text only; the page is told whether it worked so its button can say so. */
	private void copy(JsonNode id, JsonNode text) {
		boolean ok = false;
		if (text.isTextual() && !text.asText().isEmpty()) {
			try {
				seams.clipboard().accept(text.asText());
				ok = true;
			} catch (SWTError | RuntimeException e) {
				// SWT throws an SWTError while another program holds the clipboard open.
				LOG.warn("Could not copy to the clipboard", e);
			}
		}
		if (id.isTextual()) {
			execute("window.__kiroCopied && window.__kiroCopied(" + toScriptLiteral(id) + ", " + ok + ");");
		}
	}

	private static void copyToClipboard(String text) {
		Clipboard clipboard = new Clipboard(Display.getCurrent());
		try {
			clipboard.setContents(new Object[] { text }, new Transfer[] { TextTransfer.getInstance() });
		} finally {
			clipboard.dispose();
		}
	}

	private static void launch(String url) {
		if (!Program.launch(url)) {
			LOG.warn("Could not open " + abbreviate(url));
		}
	}

	/** There is a browser and its current document is the chat page. */
	private boolean onChatPage() {
		return browser != null && !browser.isDisposed() && isChatPage(browser.getUrl());
	}

	private boolean isChatPage(String location) {
		return ChatPageLocation.matches(page, location);
	}

	private static boolean isBlank(String location) {
		return location == null || location.isEmpty() || "about:blank".equalsIgnoreCase(location);
	}

	private void execute(String script) {
		if (browser != null && !browser.isDisposed() && !browser.execute(script)) {
			LOG.warn("Chat page script did not run: " + abbreviate(script));
		}
	}

	private static File pageFile() throws IOException {
		Bundle bundle = FrameworkUtil.getBundle(WebChat.class);
		URL entry = bundle == null ? null : bundle.getEntry("/web/");
		if (entry == null) {
			throw new IOException("web/ is missing from the plugin (run `npm run build` in webview/)");
		}
		// Resolving the directory extracts all of it when the bundle is a jar.
		// toFileURL returns an unencoded URL; URIUtil.toURI copes with spaces in the path.
		try {
			File dir = URIUtil.toFile(URIUtil.toURI(FileLocator.toFileURL(entry)));
			return new File(dir, "index.html");
		} catch (URISyntaxException e) {
			throw new IOException(e);
		}
	}

	/** JSON is a subset of JavaScript once the two line separators JSON allows raw are escaped. */
	private static String toScriptLiteral(JsonNode node) {
		try {
			return MAPPER.writeValueAsString(node).replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
		} catch (JsonProcessingException e) {
			throw new IllegalStateException(e);
		}
	}

	private static String abbreviate(String text) {
		return text.length() <= 120 ? text : text.substring(0, 120) + "…";
	}
}
