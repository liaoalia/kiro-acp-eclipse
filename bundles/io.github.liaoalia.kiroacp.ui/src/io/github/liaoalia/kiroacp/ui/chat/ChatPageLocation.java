package io.github.liaoalia.kiroacp.ui.chat;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Decides whether a browser location is the chat page itself.
 *
 * <p>The bridge to the Java host must only ever serve that one file: every
 * other location is refused as a navigation target and gets no messages.
 */
public final class ChatPageLocation {

	private ChatPageLocation() {
	}

	/**
	 * @param page     the chat page on disk ({@code web/index.html})
	 * @param location what the browser reports, e.g. {@code file:///C:/.../index.html?view=1}
	 * @return {@code true} only for a {@code file:} URL of exactly that file; a
	 *         query or fragment is ignored. A URL naming a host
	 *         ({@code file://server/share/...}) matches only when the page itself
	 *         lives on that share.
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
			boolean remote = host != null && !host.isEmpty() && !"localhost".equalsIgnoreCase(host);
			// Path.of resolves file://server/share/x to \\server\share\x where the
			// platform has such paths and throws where it does not.
			Path candidate = Path.of(new URI("file", remote ? host : null, path, null)).toAbsolutePath().normalize();
			Path expected = page.toAbsolutePath().normalize();
			if (candidate.equals(expected)) {
				return true;
			}
			// Another spelling of the same local file (links, short names). Never
			// asked for a path on a named host or an explicit share: probing it
			// would contact a machine the location picked.
			return !remote && !path.startsWith("//") && Files.exists(candidate)
					&& Files.isSameFile(candidate, expected);
		} catch (URISyntaxException | IOException | RuntimeException e) {
			// Unparseable or unresolvable is simply "not the chat page".
			return false;
		}
	}
}
