package io.github.liaoalia.kiroacp.core.acp.process;

import java.util.Locale;

/** The operating system families the launch rules differ for. */
public enum Platform {
	WINDOWS, MAC, LINUX;

	public static Platform current() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		if (os.contains("win")) {
			return WINDOWS;
		}
		if (os.contains("mac") || os.contains("darwin")) {
			return MAC;
		}
		return LINUX;
	}
}
