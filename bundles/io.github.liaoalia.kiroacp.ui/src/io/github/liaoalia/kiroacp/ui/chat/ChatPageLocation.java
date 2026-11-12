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
 * <p>The page must be on a local drive. A plugin installed on a network share
 * is not supported ({@link #isLocal}): {@code file:} URLs spell a path on
 * another machine in too many ways to compare reliably, so every location that
 * names a machine, or resolves to a share, is simply not the page.
 *
 * <p>The decision is a comparison of path names and nothing else. A location
 * is whatever a page, a link or a dropped item supplied, so the file system is
 * never consulted about it: looking a path up is already a request to the
 * machine it names.
 */
public final class ChatPageLocation {

	private ChatPageLocation() {
	}

	/** @return {@code false} for a page on a network share (a UNC path), which cannot be shown */
	public static boolean isLocal(Path page) {
		return page != null && !isShare(page.toAbsolutePath());
	}

	/**
	 * @param page     the chat page on disk ({@code web/index.html})
	 * @param location what the browser reports, e.g. {@code file:///C:/.../index.html?view=1}
	 * @return {@code true} only for a {@code file:} URL without a host whose
	 *         path, once normalised, is the local page's own; a query or fragment
	 *         is ignored. The page is always loaded from a URL built from
	 *         {@code page}, so other names for the same file (links, short names)
	 *         are deliberately not recognised.
	 */
	public static boolean matches(Path page, String location) {
		if (!isLocal(page) || location == null || location.isBlank()) {
			return false;
		}
		try {
			URI uri = new URI(location.trim());
			if (!"file".equalsIgnoreCase(uri.getScheme())) {
				return false;
			}
			// Any authority at all, parseable as a host name or not (file://file_srv/...),
			// names a machine. Only its absence or "localhost" means this one.
			String authority = uri.getRawAuthority();
			if (authority != null && !authority.isEmpty() && !"localhost".equalsIgnoreCase(authority)) {
				return false;
			}
			String path = uri.getPath();
			if (path == null || path.isEmpty()) {
				return false;
			}
			// Path.of does no I/O. A path that still resolves to a share
			// (//server/..., encoded backslashes) is caught by isShare.
			Path candidate = Path.of(new URI("file", null, path, null)).toAbsolutePath().normalize();
			return !isShare(candidate) && candidate.equals(page.toAbsolutePath().normalize());
		} catch (URISyntaxException | RuntimeException e) {
			// Unparseable or unresolvable is simply "not the chat page".
			return false;
		}
	}

	/** A Windows UNC path: its root is {@code \\server\share\}. Other platforms have none. */
	private static boolean isShare(Path absolute) {
		Path root = absolute.getRoot();
		if (root == null) {
			return false;
		}
		String text = root.toString();
		return text.startsWith("\\\\") || text.startsWith("//");
	}
}
