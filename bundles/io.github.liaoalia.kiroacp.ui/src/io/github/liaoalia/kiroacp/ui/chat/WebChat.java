package io.github.liaoalia.kiroacp.ui.chat;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Consumer;

import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.URIUtil;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.BrowserFunction;
import org.eclipse.swt.browser.LocationEvent;
import org.eclipse.swt.browser.LocationListener;
import org.eclipse.swt.browser.ProgressListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * <p>All methods must be called on the UI thread.
 */
public final class WebChat {

	/** Shim-level message (not part of the chat protocol): open a link outside the page. */
	static final String OPEN_EXTERNAL = "host:openExternal";

	private static final ILog LOG = ILog.of(WebChat.class);
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final Browser browser;
	private final Consumer<JsonNode> onMessage;
	private Path page;
	/** The current document is the chat page and has run far enough to receive messages. */
	private boolean loaded;

	/**
	 * @param instanceId distinguishes this chat from others showing the same page;
	 *                   the page keeps its persisted UI state (draft, history) under it
	 */
	public WebChat(Composite parent, String instanceId, Consumer<JsonNode> onMessage) {
		this.onMessage = onMessage;
		// SWT.EDGE selects WebView2 on Windows before Eclipse 4.35, where Internet
		// Explorer was still the default; other platforms ignore the hint.
		this.browser = new Browser(parent, SWT.EDGE);

		new BrowserFunction(browser, "__kiroHost") {
			@Override
			public Object function(Object[] arguments) {
				if (arguments.length > 0 && arguments[0] instanceof String json) {
					// Never re-enter the browser from inside its own callback.
					Display display = browser.getDisplay();
					display.asyncExec(() -> dispatch(json));
				}
				return null;
			}
		};

		browser.addLocationListener(new LocationListener() {
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
		browser.addOpenWindowListener(event -> event.required = true);

		browser.addProgressListener(ProgressListener.completedAdapter(event -> {
			// Queued so the script does not run inside the browser's own callback.
			browser.getDisplay().asyncExec(() -> {
				if (!browser.isDisposed() && isChatPage(browser.getUrl())) {
					pageIsUp();
					execute("window.__kiroFlush && window.__kiroFlush();");
				}
			});
		}));

		try {
			File file = pageFile();
			if (ChatPageLocation.isLocal(file.toPath())) {
				page = file.toPath();
				browser.setUrl(file.toURI() + "?view=" + URLEncoder.encode(instanceId, StandardCharsets.UTF_8));
			} else {
				// page stays null: nothing is the chat page and the bridge serves no one.
				LOG.error("Kiro chat cannot run from a network location: " + file);
				browser.setText("<html><body><p>Kiro Chat cannot run from a network location. "
						+ "Install Eclipse and this plugin on a local drive.</p></body></html>");
			}
		} catch (IOException e) {
			LOG.error("Kiro chat page could not be located", e);
			browser.setText("<html><body><p>Kiro chat page could not be loaded.</p></body></html>");
		}
	}

	public Control getControl() {
		return browser;
	}

	public boolean setFocus() {
		return !browser.isDisposed() && browser.setFocus();
	}

	/** Sends one protocol message to the page. Dropped unless the chat page is up. */
	public void post(JsonNode message) {
		if (!loaded || browser.isDisposed() || !isChatPage(browser.getUrl())) {
			return;
		}
		execute("window.__kiroReceive && window.__kiroReceive(" + toScriptLiteral(message) + ");");
	}

	/** Pushes Eclipse's colours and fonts into the page; call again when the theme changes. */
	public void applyTheme() {
		if (browser.isDisposed() || !isChatPage(browser.getUrl())) {
			return;
		}
		Composite parent = browser.getParent();
		Color background = parent.getBackground();
		Color foreground = parent.getForeground();
		boolean dark = luminance(background.getRGB()) < 0.5;

		ObjectNode vars = MAPPER.createObjectNode();
		vars.put("--vscode-sideBar-background", css(background.getRGB()));
		vars.put("--vscode-foreground", css(foreground.getRGB()));
		FontData ui = first(JFaceResources.getDefaultFont().getFontData());
		if (ui != null) {
			vars.put("--vscode-font-family", "\"" + cssName(ui.getName()) + "\", system-ui, sans-serif");
			vars.put("--vscode-font-size", px(ui) + "px");
		}
		FontData mono = first(JFaceResources.getTextFont().getFontData());
		if (mono != null) {
			vars.put("--vscode-editor-font-family", "\"" + cssName(mono.getName()) + "\", monospace");
			vars.put("--vscode-editor-font-size", px(mono) + "px");
		}
		execute("window.__kiroTheme && window.__kiroTheme(" + (dark ? "\"dark\"" : "\"light\"") + ", "
				+ toScriptLiteral(vars) + ");");
	}

	/**
	 * The page runs its script, and posts "ready", before the load-completed
	 * event fires, so whichever comes first marks it as able to receive.
	 */
	private void pageIsUp() {
		if (!loaded) {
			loaded = true;
			applyTheme();
		}
	}

	private void dispatch(String json) {
		// Whatever document called the function, only the chat page is listened to.
		if (browser.isDisposed() || !isChatPage(browser.getUrl())) {
			return;
		}
		pageIsUp();
		try {
			JsonNode message = MAPPER.readTree(json);
			if (message == null || !message.isObject() || !message.path("type").isTextual()) {
				return;
			}
			if (OPEN_EXTERNAL.equals(message.path("type").asText())) {
				openExternal(message.path("url").asText(""));
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
	private static void openExternal(String url) {
		String lower = url.toLowerCase(Locale.ROOT);
		if (lower.startsWith("https://") || lower.startsWith("http://")) {
			if (!Program.launch(url)) {
				LOG.warn("Could not open " + abbreviate(url));
			}
		} else {
			LOG.warn("Refused to open a link that is not http(s): " + abbreviate(url));
		}
	}

	private boolean isChatPage(String location) {
		return ChatPageLocation.matches(page, location);
	}

	private static boolean isBlank(String location) {
		return location == null || location.isEmpty() || "about:blank".equalsIgnoreCase(location);
	}

	private void execute(String script) {
		if (!browser.isDisposed() && !browser.execute(script)) {
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

	private static FontData first(FontData[] data) {
		return data != null && data.length > 0 ? data[0] : null;
	}

	/** A font name goes inside a quoted CSS string. */
	private static String cssName(String name) {
		return name.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	/** Font heights are in points; CSS wants pixels at 96 dpi. */
	private static int px(FontData font) {
		return Math.max(10, Math.round(font.getHeight() * 96f / 72f));
	}

	private static String css(RGB rgb) {
		return String.format("#%02x%02x%02x", rgb.red, rgb.green, rgb.blue);
	}

	private static double luminance(RGB rgb) {
		return (0.2126 * rgb.red + 0.7152 * rgb.green + 0.0722 * rgb.blue) / 255.0;
	}
}
