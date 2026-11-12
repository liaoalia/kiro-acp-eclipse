package io.github.liaoalia.kiroacp.core.acp.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The {@code resolveCliPath} cases of the reference's {@code test/process.test.ts}. */
class CliPathResolverTest {

	private final List<String> probed = new ArrayList<>();

	private Predicate<String> existing(String... found) {
		Set<String> set = List.of(found).stream().map(f -> f.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
		return candidate -> {
			probed.add(candidate);
			return set.contains(candidate.toLowerCase(Locale.ROOT));
		};
	}

	@Test
	void keepsValuesContainingAPathSeparator() {
		assertEquals("C:\\tools\\kiro-cli.exe",
				CliPathResolver.resolve("C:\\tools\\kiro-cli.exe", Platform.WINDOWS, Map.of(), existing()));
		assertEquals("./bin/kiro-cli", CliPathResolver.resolve("./bin/kiro-cli", Platform.LINUX, Map.of(), existing()));
		assertEquals(List.of(), probed);
	}

	@Test
	void keepsABareValueThatExistsAsGiven() {
		assertEquals("kiro-cli",
				CliPathResolver.resolve("kiro-cli", Platform.LINUX, Map.of("PATH", "/usr/bin"), existing("kiro-cli")));
	}

	@Test
	void searchesPathEntriesInOrderWithPathextOnWindows() {
		Map<String, String> env = Map.of("Path", "C:\\a;C:\\b;C:\\c", "PATHEXT", ".EXE;.CMD");

		String result = CliPathResolver.resolve("kiro-cli", Platform.WINDOWS, env, existing("C:\\b\\kiro-cli.EXE"));

		assertEquals("C:\\b\\kiro-cli.EXE", result);
		assertEquals(List.of("kiro-cli", "C:\\a\\kiro-cli", "C:\\a\\kiro-cli.EXE", "C:\\a\\kiro-cli.CMD", "C:\\b\\kiro-cli",
				"C:\\b\\kiro-cli.EXE"), probed);
	}

	@Test
	void doesNotAppendPathextWhenTheNameAlreadyHasAnExtension() {
		String result = CliPathResolver.resolve("kiro-cli.exe", Platform.WINDOWS, Map.of("PATH", "C:\\a", "PATHEXT", ".EXE"),
				existing("C:\\a\\kiro-cli.exe"));

		assertEquals("C:\\a\\kiro-cli.exe", result);
		assertEquals(List.of("kiro-cli.exe", "C:\\a\\kiro-cli.exe"), probed);
	}

	@Test
	void usesTheUsualExtensionsWithoutPathextAndToleratesATrailingSeparator() {
		String result = CliPathResolver.resolve(" kiro-cli ", Platform.WINDOWS, Map.of("PATH", "C:\\a\\;;C:\\b"),
				existing("C:\\b\\kiro-cli.CMD"));

		assertEquals("C:\\b\\kiro-cli.CMD", result);
		assertEquals(List.of("kiro-cli", "C:\\a\\kiro-cli", "C:\\a\\kiro-cli.COM", "C:\\a\\kiro-cli.EXE", "C:\\a\\kiro-cli.BAT",
				"C:\\a\\kiro-cli.CMD", "C:\\b\\kiro-cli", "C:\\b\\kiro-cli.COM", "C:\\b\\kiro-cli.EXE", "C:\\b\\kiro-cli.BAT",
				"C:\\b\\kiro-cli.CMD"), probed);
	}

	@Test
	void searchesPathOnLinux() {
		assertEquals("/usr/local/bin/kiro-cli", CliPathResolver.resolve("kiro-cli", Platform.LINUX,
				Map.of("PATH", "/usr/bin:/usr/local/bin"), existing("/usr/local/bin/kiro-cli")));
		assertFalse(probed.contains("/usr/bin/kiro-cli.EXE"));
	}

	@Test
	void fallsBackToTheWindowsDefaultInstallLocations() {
		Map<String, String> env = Map.of("PATH", "C:\\nothing", "LOCALAPPDATA", "C:\\Users\\me\\AppData\\Local", "ProgramFiles",
				"C:\\Program Files");
		String local = "C:\\Users\\me\\AppData\\Local\\Programs\\Kiro-Cli\\kiro-cli.exe";

		String result = CliPathResolver.resolve("kiro-cli", Platform.WINDOWS, env, existing(local));

		assertEquals(local, result);
		String programFiles = "C:\\Program Files\\Kiro-Cli\\kiro-cli.exe";
		assertTrue(probed.contains(programFiles), probed.toString());
		assertTrue(probed.indexOf(programFiles) < probed.indexOf(local), "Program Files is probed before LOCALAPPDATA");
	}

	@Test
	void findsTheInstallerLocationDirectlyUnderLocalAppData() {
		Map<String, String> env = Map.of("PATH", "C:\\nothing", "LOCALAPPDATA", "C:\\Users\\me\\AppData\\Local");
		String installed = "C:\\Users\\me\\AppData\\Local\\Kiro-Cli\\kiro-cli.exe";

		assertEquals(installed, CliPathResolver.resolve("kiro-cli", Platform.WINDOWS, env, existing(installed)));
	}

	@Test
	void fallsBackToTheMacDefaultInstallLocations() {
		String result = CliPathResolver.resolve("kiro-cli", Platform.MAC, Map.of("PATH", "/usr/bin", "HOME", "/Users/me"),
				existing("/Users/me/.local/bin/kiro-cli"));

		assertEquals("/Users/me/.local/bin/kiro-cli", result);
		assertEquals(List.of("/opt/homebrew/bin/kiro-cli", "/usr/local/bin/kiro-cli", "/Users/me/.local/bin/kiro-cli"),
				probed.subList(probed.size() - 3, probed.size()));
	}

	@Test
	void fallsBackToTheLinuxDefaultInstallLocations() {
		String result = CliPathResolver.resolve("kiro-cli", Platform.LINUX, Map.of("PATH", "/opt/none", "HOME", "/home/me"),
				existing("/usr/bin/kiro-cli"));

		assertEquals("/usr/bin/kiro-cli", result);
		assertEquals(List.of("/home/me/.local/bin/kiro-cli", "/usr/local/bin/kiro-cli", "/usr/bin/kiro-cli"),
				probed.subList(probed.size() - 3, probed.size()));
	}

	@Test
	void returnsTheConfiguredValueUnchangedWhenNothingIsFound() {
		assertEquals("kiro-cli", CliPathResolver.resolve("kiro-cli", Platform.LINUX,
				Map.of("PATH", "/usr/bin", "HOME", "/home/me"), existing()));
		assertEquals(" ", CliPathResolver.resolve(" ", Platform.LINUX, Map.of(), existing()));
	}

	@Test
	void onlyTriesTheDefaultLocationsForTheKiroCliCommand() {
		assertEquals("node", CliPathResolver.resolve("node", Platform.LINUX, Map.of("PATH", "/opt/none", "HOME", "/home/me"),
				existing("/usr/bin/kiro-cli")));
		assertFalse(probed.contains("/usr/bin/kiro-cli"));
	}

	@Test
	void onThisMachineAFileOnThePathIsFoundAndADirectoryOfThatNameIsNot(@TempDir Path dir) throws Exception {
		Path bin = Files.createDirectories(dir.resolve("bin"));
		Files.createDirectories(dir.resolve("shadow").resolve("my-agent"));
		boolean windows = Platform.current() == Platform.WINDOWS;
		Path executable = Files.writeString(bin.resolve(windows ? "my-agent.cmd" : "my-agent"), "");
		String separator = windows ? ";" : ":";
		Map<String, String> env = Map.of("PATH", dir.resolve("shadow") + separator + bin, "PATHEXT", ".EXE;.CMD");

		String resolved = CliPathResolver.resolve("my-agent", env);

		// On Windows the extension comes from PATHEXT, in its spelling.
		assertTrue(Files.isSameFile(executable, Path.of(resolved)), resolved);
		assertEquals(bin, Path.of(resolved).getParent());
	}
}
