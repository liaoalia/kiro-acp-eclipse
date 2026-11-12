package io.github.liaoalia.kiroacp.ui.chat;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;

/**
 * Decides whether a browser location is the chat page itself.
 *
 * <p>The bridge to the Java host must only ever serve that one file: every
 * other location is refused as a navigation target and gets no messages.
 *
 * <p>The decision is a comparison of path names and nothing else. A location
 * is whatever a page, a link or a dropped item supplied, and it can spell a
 * path on another machine in more ways than can be listed (a host, a leading
 * double slash, encoded backslashes), so the file system is never consulted
 * about it: looking a path up is already a request to the machine it names.
 */
public final class ChatPageLocation {

	private ChatPageLocation() {
	}

	/**
	 * @param page     the chat page on disk ({@code web/index.html})
	 * @param location what the browser reports, e.g. {@code file:///C:/.../index.html?view=1}
	 * @return {@code true} only for a {@code file:} URL whose path, once
	 *         normalised, is the page's own; a query or fragment is ignored. The
	 *         page is always loaded from a URL built from {@code page}, so other
	 *         names for the same file (links, short names) are deliberately not
	 *         recognised.
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
			String path = uri.getPath();
			if (path == null || path.isEmpty()) {
				return false;
			}
			String host = uri.getHost();
			boolean named = host != null && !host.isEmpty() && !"localhost".equalsIgnoreCase(host);
			// Path.of resolves file://server/share/x to \\server\share\x where the
			// platform has such paths and throws where it does not. No I/O happens here.
			Path candidate = Path.of(new URI("file", named ? host : null, path, null));
			return candidate.toAbsolutePath().normalize().equals(page.toAbsolutePath().normalize());
		} catch (URISyntaxException | RuntimeException e) {
			// Unparseable or unresolvable is simply "not the chat page".
			return false;
		}
	}
}
