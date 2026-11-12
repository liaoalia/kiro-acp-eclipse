package io.github.liaoalia.kiroacp.core.acp.process;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Turns the configured CLI command into an executable path.
 *
 * <p>An IDE started from the desktop often does not have the PATH of a login
 * shell, so a bare {@code kiro-cli} is also looked for where its installers
 * put it (reference D3).
 */
public final class CliPathResolver {

	private static final String DEFAULT_PATHEXT = ".COM;.EXE;.BAT;.CMD";

	private CliPathResolver() {
	}

	/** Resolves for this machine, with {@code env} supplying PATH and the home directory. */
	public static String resolve(String configured, Map<String, String> env) {
		return resolve(configured, Platform.current(), env, CliPathResolver::isFile);
	}

	/**
	 * <ol>
	 * <li>A value containing a path separator, or one that exists as given, is returned (trimmed).
	 * <li>Otherwise each PATH entry is searched, on Windows with the PATHEXT extensions.
	 * <li>Otherwise, for the command {@code kiro-cli}, the default install locations are tried.
	 * <li>Otherwise the configured value is returned unchanged, and starting it fails.
	 * </ol>
	 *
	 * @param exists tells whether a candidate path is there
	 */
	public static String resolve(String configured, Platform platform, Map<String, String> env, Predicate<String> exists) {
		String trimmed = configured.trim();
		if (trimmed.isEmpty()) {
			return configured;
		}
		if (trimmed.indexOf('/') >= 0 || trimmed.indexOf('\\') >= 0) {
			return trimmed;
		}
		if (exists.test(trimmed)) {
			return trimmed;
		}

		boolean windows = platform == Platform.WINDOWS;
		char separator = windows ? '\\' : '/';
		List<String> extensions = new ArrayList<>(List.of(""));
		// A name that already has an extension is looked for as it is.
		if (windows && trimmed.lastIndexOf('.') <= 0) {
			String pathext = lookup(env, "PATHEXT");
			for (String extension : (pathext == null ? DEFAULT_PATHEXT : pathext).split(";")) {
				if (!extension.isEmpty()) {
					extensions.add(extension);
				}
			}
		}
		String path = lookup(env, "PATH");
		for (String dir : (path == null ? "" : path).split(windows ? ";" : ":")) {
			if (dir.isEmpty()) {
				continue;
			}
			for (String extension : extensions) {
				String candidate = join(separator, dir, trimmed + extension);
				if (exists.test(candidate)) {
					return candidate;
				}
			}
		}

		int dot = trimmed.lastIndexOf('.');
		String base = dot > 0 ? trimmed.substring(0, dot) : trimmed;
		if (base.equalsIgnoreCase("kiro-cli")) {
			for (String candidate : defaultLocations(platform, env)) {
				if (exists.test(candidate)) {
					return candidate;
				}
			}
		}
		return configured;
	}

	static List<String> defaultLocations(Platform platform, Map<String, String> env) {
		switch (platform) {
		case WINDOWS: {
			String programFiles = lookup(env, "ProgramFiles");
			String localAppData = lookup(env, "LOCALAPPDATA");
			List<String> out = new ArrayList<>();
			out.add(join('\\', programFiles == null ? "C:\\Program Files" : programFiles, "Kiro-Cli", "kiro-cli.exe"));
			if (localAppData != null) {
				// Where the 3.2 installer puts it (seen 2026-11-12); an Eclipse started
				// before the install does not have it on its PATH yet.
				out.add(join('\\', localAppData, "Kiro-Cli", "kiro-cli.exe"));
				out.add(join('\\', localAppData, "Programs", "Kiro-Cli", "kiro-cli.exe"));
			}
			return out;
		}
		case MAC:
			return List.of("/opt/homebrew/bin/kiro-cli", "/usr/local/bin/kiro-cli",
					join('/', home(env, platform), ".local", "bin", "kiro-cli"));
		default:
			return List.of(join('/', home(env, platform), ".local", "bin", "kiro-cli"), "/usr/local/bin/kiro-cli",
					"/usr/bin/kiro-cli");
		}
	}

	/** Windows environment names are case-insensitive; a plain map is not. */
	static String lookup(Map<String, String> env, String name) {
		String exact = env.get(name);
		if (exact != null) {
			return exact;
		}
		for (Map.Entry<String, String> entry : env.entrySet()) {
			if (entry.getKey().equalsIgnoreCase(name)) {
				return entry.getValue();
			}
		}
		return null;
	}

	private static String home(Map<String, String> env, Platform platform) {
		String home = lookup(env, "HOME");
		if (home == null && platform == Platform.WINDOWS) {
			home = lookup(env, "USERPROFILE");
		}
		return home != null ? home : System.getProperty("user.home", "");
	}

	private static String join(char separator, String first, String... more) {
		StringBuilder out = new StringBuilder(first);
		for (String part : more) {
			char last = out.length() == 0 ? separator : out.charAt(out.length() - 1);
			if (last != separator && !(separator == '\\' && last == '/')) {
				out.append(separator);
			}
			out.append(part);
		}
		return out.toString();
	}

	private static boolean isFile(String candidate) {
		try {
			// A directory of that name, in the working directory or on the PATH, is not the CLI.
			return Files.isRegularFile(Path.of(candidate));
		} catch (InvalidPathException e) {
			return false;
		}
	}
}
