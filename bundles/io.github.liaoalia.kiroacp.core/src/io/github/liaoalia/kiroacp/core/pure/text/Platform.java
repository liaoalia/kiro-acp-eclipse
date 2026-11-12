package io.github.liaoalia.kiroacp.core.pure.text;

import java.util.Locale;

/**
 * The two path and shell dialects the pure helpers distinguish. Every helper
 * takes the platform as a parameter so both dialects are testable on any host.
 */
public enum Platform {
	WIN32, POSIX;

	private static final Platform CURRENT = System.getProperty("os.name", "").toLowerCase(Locale.ROOT)
			.startsWith("windows") ? WIN32 : POSIX;

	/** The platform this JVM runs on. */
	public static Platform current() {
		return CURRENT;
	}

	/** {@code platform}, or the current one for {@code null}. */
	public static Platform orCurrent(Platform platform) {
		return platform != null ? platform : CURRENT;
	}
}
