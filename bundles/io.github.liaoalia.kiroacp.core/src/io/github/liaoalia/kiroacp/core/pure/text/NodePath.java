package io.github.liaoalia.kiroacp.core.pure.text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The parts of Node's {@code path.win32} / {@code path.posix} the reference
 * relies on, for either platform regardless of the host. {@link java.nio.file.Path}
 * cannot stand in: it only speaks the host's dialect and rejects strings the
 * reference accepts (a shell command containing {@code "} or {@code |} is not a
 * valid Windows path, yet must still be answered with "not absolute").
 */
public final class NodePath {

	private NodePath() {
	}

	/** {@code path.isAbsolute}. A Windows path is absolute with a leading separator or a {@code X:\} root. */
	public static boolean isAbsolute(String p, Platform platform) {
		if (p.isEmpty()) {
			return false;
		}
		if (Platform.orCurrent(platform) == Platform.POSIX) {
			return p.charAt(0) == '/';
		}
		if (isSeparator(p.charAt(0))) {
			return true;
		}
		return p.length() > 2 && isDriveLetter(p.charAt(0)) && p.charAt(1) == ':' && isSeparator(p.charAt(2));
	}

	/** {@code path.basename}: the last segment, ignoring trailing separators; empty for a root. */
	public static String basename(String p, Platform platform) {
		boolean win = Platform.orCurrent(platform) == Platform.WIN32;
		int start = 0;
		if (win && p.length() >= 2 && isDriveLetter(p.charAt(0)) && p.charAt(1) == ':') {
			start = 2;
		}
		int end = -1;
		for (int i = p.length() - 1; i >= start; i--) {
			char c = p.charAt(i);
			if (win ? isSeparator(c) : c == '/') {
				if (end != -1) {
					start = i + 1;
					break;
				}
			} else if (end == -1) {
				end = i + 1;
			}
		}
		return end == -1 ? "" : p.substring(start, end);
	}

	/**
	 * {@code path.resolve(p)}: an absolute, normalised path without {@code .} and
	 * {@code ..} segments. A relative path is resolved against the process's
	 * working directory.
	 */
	public static String resolve(String p, Platform platform) {
		return Platform.orCurrent(platform) == Platform.WIN32 ? resolveWin32(p) : resolvePosix(p);
	}

	private static String resolvePosix(String p) {
		String resolved = "";
		boolean absolute = false;
		for (String part : new String[] { p, posixCwd() }) {
			if (part.isEmpty()) {
				continue;
			}
			resolved = part + "/" + resolved;
			absolute = part.charAt(0) == '/';
			if (absolute) {
				break;
			}
		}
		String tail = normalizeSegments(resolved, !absolute, '/', false);
		if (absolute) {
			return "/" + tail;
		}
		return tail.isEmpty() ? "." : tail;
	}

	private static String resolveWin32(String p) {
		String resolvedDevice = "";
		String resolvedTail = "";
		boolean resolvedAbsolute = false;
		// The argument first, then the working directory, as long as no absolute path is assembled.
		for (int i = 0; i <= 1; i++) {
			String path;
			if (i == 0) {
				path = p;
				if (path.isEmpty()) {
					continue;
				}
			} else if (resolvedDevice.isEmpty()) {
				path = cwd();
			} else {
				// Windows keeps a working directory per drive in the hidden "=X:" variables.
				path = driveCwd(resolvedDevice);
				String head = path.substring(0, Math.min(2, path.length()));
				if (!head.toLowerCase(Locale.ROOT).equals(resolvedDevice.toLowerCase(Locale.ROOT))
						&& path.length() > 2 && path.charAt(2) == '\\') {
					path = resolvedDevice + "\\";
				}
			}

			int len = path.length();
			int rootEnd = 0;
			String device = "";
			boolean absolute = false;
			char first = len > 0 ? path.charAt(0) : 0;
			if (len == 1) {
				if (isSeparator(first)) {
					rootEnd = 1;
					absolute = true;
				}
			} else if (len > 1 && isSeparator(first)) {
				absolute = true;
				if (isSeparator(path.charAt(1))) {
					// A UNC root is \\server\share; anything less is an ordinary rooted path.
					int j = 2;
					int last = j;
					while (j < len && !isSeparator(path.charAt(j))) {
						j++;
					}
					if (j < len && j != last) {
						String server = path.substring(last, j);
						last = j;
						while (j < len && isSeparator(path.charAt(j))) {
							j++;
						}
						if (j < len && j != last) {
							last = j;
							while (j < len && !isSeparator(path.charAt(j))) {
								j++;
							}
							if (j == len || j != last) {
								device = "\\\\" + server + "\\" + path.substring(last, j);
								rootEnd = j;
							}
						}
					}
				} else {
					rootEnd = 1;
				}
			} else if (len > 1 && isDriveLetter(first) && path.charAt(1) == ':') {
				device = path.substring(0, 2);
				rootEnd = 2;
				if (len > 2 && isSeparator(path.charAt(2))) {
					absolute = true;
					rootEnd = 3;
				}
			}

			if (!device.isEmpty()) {
				if (!resolvedDevice.isEmpty()) {
					if (!device.toLowerCase(Locale.ROOT).equals(resolvedDevice.toLowerCase(Locale.ROOT))) {
						continue;
					}
				} else {
					resolvedDevice = device;
				}
			}
			if (resolvedAbsolute) {
				if (!resolvedDevice.isEmpty()) {
					break;
				}
			} else {
				resolvedTail = path.substring(rootEnd) + "\\" + resolvedTail;
				resolvedAbsolute = absolute;
				if (absolute && !resolvedDevice.isEmpty()) {
					break;
				}
			}
		}
		resolvedTail = normalizeSegments(resolvedTail, !resolvedAbsolute, '\\', true);
		if (resolvedAbsolute) {
			return resolvedDevice + "\\" + resolvedTail;
		}
		String out = resolvedDevice + resolvedTail;
		return out.isEmpty() ? "." : out;
	}

	/** Drops empty and {@code .} segments and applies {@code ..}; joins with {@code separator}. */
	private static String normalizeSegments(String path, boolean allowAboveRoot, char separator, boolean win) {
		List<String> out = new ArrayList<>();
		int start = 0;
		for (int i = 0; i <= path.length(); i++) {
			if (i < path.length() && !(win ? isSeparator(path.charAt(i)) : path.charAt(i) == '/')) {
				continue;
			}
			String segment = path.substring(start, i);
			start = i + 1;
			if (segment.isEmpty() || segment.equals(".")) {
				continue;
			}
			if (segment.equals("..")) {
				if (!out.isEmpty() && !out.get(out.size() - 1).equals("..")) {
					out.remove(out.size() - 1);
				} else if (allowAboveRoot) {
					out.add("..");
				}
				continue;
			}
			out.add(segment);
		}
		return String.join(String.valueOf(separator), out);
	}

	private static String cwd() {
		return System.getProperty("user.dir", "");
	}

	/** The working directory as a POSIX path: on Windows the drive is dropped, as Node does. */
	private static String posixCwd() {
		String cwd = cwd();
		if (Platform.current() == Platform.WIN32) {
			cwd = cwd.replace('\\', '/');
			int slash = cwd.indexOf('/');
			return slash >= 0 ? cwd.substring(slash) : "/";
		}
		return cwd;
	}

	private static String driveCwd(String device) {
		String perDrive = null;
		try {
			perDrive = System.getenv("=" + device);
		} catch (RuntimeException e) {
			// Not every runtime exposes the per-drive variables; the process directory is the fallback anyway.
		}
		return perDrive != null && !perDrive.isEmpty() ? perDrive : cwd();
	}

	private static boolean isSeparator(char c) {
		return c == '/' || c == '\\';
	}

	private static boolean isDriveLetter(char c) {
		return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
	}
}
