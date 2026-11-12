package io.github.liaoalia.kiroacp.ui.chat;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Decides whether a browser location is the chat page itself.
 *
 * <p>The bridge to the Java host must only ever serve that one local file:
 * every other location is refused as a navigation target and gets no messages.
 */
public final class ChatPageLocation {

	private ChatPageLocation() {
	}

	/**
	 * @param page     the chat page on disk ({@code web/index.html})
	 * @param location what the browser reports, e.g. {@code file:///C:/.../index.html?view=1}
	 * @return {@code true} only for a local {@code file:} URL of exactly that file;
	 *         a query or fragment is ignored
	 */
	public static boolean matches(Path page, String location) {
		if (page == null || location == null || location.isBlank()) {
			return false;
		}
		try {
			URI uri = new URI(location.trim());
			if (!"file".equalsIgnoreCase(uri.getScheme())) {
				return false;
			}
			// file://host/share/... names another machine; the page is always local.
			String host = uri.getHost();
			if (host != null && !host.isEmpty() && !"localhost".equalsIgnoreCase(host)) {
				return false;
			}
			String path = uri.getPath();
			if (path == null || path.isEmpty()) {
				return false;
			}
			Path candidate = Path.of(new URI("file", null, path, null)).toAbsolutePath().normalize();
			Path expected = page.toAbsolutePath().normalize();
			return candidate.equals(expected) || (Files.exists(candidate) && Files.isSameFile(candidate, expected));
		} catch (URISyntaxException | IOException | RuntimeException e) {
			// Unparseable or unresolvable is simply "not the chat page".
			return false;
		}
	}
}
