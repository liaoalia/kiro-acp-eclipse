package io.github.liaoalia.kiroacp.core.session.context;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;

import io.github.liaoalia.kiroacp.core.pure.text.JsText;

/**
 * One URI of a drop, read as leniently as the reference reads it
 * ({@code vscode.Uri.parse(text, true)}): a scheme is all it must have, and
 * characters a strict parser refuses (a space in a path) are taken as they
 * are.
 *
 * @param scheme lower case
 * @param path   the path, percent-decoded
 * @param file   the local file a {@code file:} URI names; {@code null} for any other scheme or a path that is none here
 * @param text   the URI as it was dropped
 */
record DroppedUri(String scheme, String path, Path file, String text) {

	/** @return {@code null} when {@code raw} is not a URI (empty, no scheme) */
	static DroppedUri parse(String raw) {
		if (raw == null) {
			return null;
		}
		String text = JsText.trim(raw);
		int colon = schemeEnd(text);
		if (colon < 0) {
			return null;
		}
		String scheme = text.substring(0, colon).toLowerCase(Locale.ROOT);
		String rest = text.substring(colon + 1);
		int cut = rest.indexOf('#');
		if (cut >= 0) {
			rest = rest.substring(0, cut);
		}
		cut = rest.indexOf('?');
		if (cut >= 0) {
			rest = rest.substring(0, cut);
		}
		String authority = "";
		if (rest.startsWith("//")) {
			int slash = rest.indexOf('/', 2);
			authority = decode(slash < 0 ? rest.substring(2) : rest.substring(2, slash));
			rest = slash < 0 ? "" : rest.substring(slash);
		}
		String path = decode(rest);
		return new DroppedUri(scheme, path, "file".equals(scheme) ? localFile(authority, path) : null, text);
	}

	/** The name the file goes by in notices: the last segment of the path, else the whole URI. */
	String name() {
		String last = path.substring(path.lastIndexOf('/') + 1);
		return last.isEmpty() ? text : last;
	}

	/** Index of the colon that ends a scheme ({@code ALPHA *( ALPHA / DIGIT / "+" / "-" / "." )}), or -1. */
	private static int schemeEnd(String text) {
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			boolean letter = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
			if (c == ':') {
				return i > 0 ? i : -1;
			}
			if (!letter && (i == 0 || !((c >= '0' && c <= '9') || c == '+' || c == '-' || c == '.'))) {
				return -1;
			}
		}
		return -1;
	}

	/** {@code vscode.Uri.fsPath}: {@code //server/share/…} for a URI with a host, {@code C:/…} for {@code /C:/…}. */
	private static Path localFile(String authority, String path) {
		String fsPath;
		if (!authority.isEmpty() && !"localhost".equalsIgnoreCase(authority)) {
			fsPath = "//" + authority + path;
		} else if (path.length() >= 3 && path.charAt(0) == '/' && Character.isLetter(path.charAt(1)) && path.charAt(2) == ':') {
			fsPath = path.substring(1);
		} else {
			fsPath = path;
		}
		try {
			Path file = Path.of(fsPath);
			return file.isAbsolute() ? file.normalize() : null;
		} catch (InvalidPathException e) {
			return null;
		}
	}

	/** Percent-decoding as UTF-8; what is not an escape stays as written. */
	private static String decode(String text) {
		if (text.indexOf('%') < 0) {
			return text;
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream(text.length());
		byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
		for (int i = 0; i < bytes.length; i++) {
			if (bytes[i] == '%' && i + 2 < bytes.length) {
				int high = Character.digit(bytes[i + 1], 16);
				int low = Character.digit(bytes[i + 2], 16);
				if (high >= 0 && low >= 0) {
					out.write(high * 16 + low);
					i += 2;
					continue;
				}
			}
			out.write(bytes[i]);
		}
		return new String(out.toByteArray(), StandardCharsets.UTF_8);
	}
}
