package io.github.liaoalia.kiroacp.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * The build's check that the core stays free of the IDE: no SWT, no JFace, no
 * Eclipse or OSGi API at all. It reads the bundle's sources and manifest, so
 * a violation fails {@code mvn verify} even where the compiler would let it
 * through (a class of the JDK's own UI toolkits, or an Eclipse bundle that
 * happens to be on the class path of the build).
 */
class CoreIndependenceTest {

	/** Where the core's imports may come from: the JDK's base packages, Jackson and the core itself. */
	private static final List<String> ALLOWED = List.of("java.io.", "java.net.", "java.nio.", "java.time.", "java.util.", "java.lang.",
			"com.fasterxml.jackson.", "io.github.liaoalia.kiroacp.core.");
	/** Never to be named, import or not: in code a fully qualified name does without an import. */
	private static final Pattern FORBIDDEN = Pattern
			.compile("\\b(org\\.eclipse|org\\.osgi|java\\.awt|javax\\.swing|io\\.github\\.liaoalia\\.kiroacp\\.ui)\\b");
	private static final Pattern IMPORT = Pattern.compile("^\\s*import\\s+(?:static\\s+)?([\\w.]+)\\s*(?:\\.\\*)?\\s*;", Pattern.MULTILINE);

	@Test
	void theSourcesImportNothingOfTheIde() throws IOException {
		List<String> problems = problems(bundle().resolve("src"));
		assertEquals(List.of(), problems, "the core must not depend on the IDE");
	}

	@Test
	void theManifestRequiresNoBundleAndImportsOnlyJackson() throws IOException {
		String manifest = new String(Files.readAllBytes(bundle().resolve("META-INF").resolve("MANIFEST.MF")), StandardCharsets.UTF_8)
				// A header's value goes on in lines that start with a space.
				.replaceAll("\\r?\\n ", "");
		List<String> problems = new ArrayList<>();
		for (String line : manifest.split("\\r?\\n")) {
			if (line.startsWith("Require-Bundle:") || line.startsWith("Fragment-Host:") || line.startsWith("Bundle-Activator:")
					|| line.startsWith("DynamicImport-Package:")) {
				problems.add(line);
			} else if (line.startsWith("Import-Package:")) {
				// Commas also separate the bounds of a version range, inside quotes.
				for (String entry : line.substring("Import-Package:".length()).split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)")) {
					String name = entry.trim().split(";")[0];
					if (!name.startsWith("com.fasterxml.jackson.")) {
						problems.add("Import-Package: " + name);
					}
				}
			}
		}
		assertEquals(List.of(), problems, "the core bundle must not depend on the IDE");
	}

	@Test
	void theCheckFindsWhatItLooksFor(@org.junit.jupiter.api.io.TempDir Path temp) throws IOException {
		Path pkg = Files.createDirectories(temp.resolve("a"));
		Files.write(pkg.resolve("Clean.java"), ("package a;\nimport java.util.List;\nimport static java.util.Objects.requireNonNull;\n"
				+ "import com.fasterxml.jackson.databind.JsonNode;\n/** Not org.eclipse.swt. */\nclass Clean {\n\t// nor javax.swing\n}\n")
				.getBytes(StandardCharsets.UTF_8));
		assertEquals(List.of(), problems(temp));

		Files.write(pkg.resolve("Swt.java"), "package a;\nimport org.eclipse.swt.widgets.Display;\nclass Swt {\n}\n".getBytes(StandardCharsets.UTF_8));
		Files.write(pkg.resolve("Qualified.java"),
				"package a;\nclass Qualified {\n\tObject o = org.eclipse.core.runtime.Platform.getBundle(\"x\");\n}\n".getBytes(StandardCharsets.UTF_8));
		Files.write(pkg.resolve("Other.java"), "package a;\nimport  static  javax.swing.SwingUtilities.invokeLater ;\nimport org.slf4j.Logger;\n"
				.getBytes(StandardCharsets.UTF_8));

		assertEquals(List.of("a/Other.java: imports javax.swing.SwingUtilities.invokeLater", "a/Other.java: imports org.slf4j.Logger",
				"a/Other.java: names javax.swing", "a/Qualified.java: names org.eclipse", "a/Swt.java: imports org.eclipse.swt.widgets.Display",
				"a/Swt.java: names org.eclipse"), problems(temp));
	}

	/** Every import from outside {@link #ALLOWED} and every mention of a {@link #FORBIDDEN} package, file by file. */
	private static List<String> problems(Path sources) throws IOException {
		List<Path> files;
		try (Stream<Path> walk = Files.walk(sources)) {
			files = walk.filter(file -> file.getFileName().toString().endsWith(".java")).sorted().toList();
		}
		assertTrue(!files.isEmpty(), "no Java sources under " + sources);
		List<String> problems = new ArrayList<>();
		for (Path file : files) {
			String name = sources.relativize(file).toString().replace('\\', '/');
			String code = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
			List<String> found = new ArrayList<>();
			Matcher imports = IMPORT.matcher(code);
			while (imports.find()) {
				String imported = imports.group(1);
				if (ALLOWED.stream().noneMatch(imported::startsWith)) {
					found.add(name + ": imports " + imported);
				}
			}
			// Prose may name what the code must not: comments are left out, roughly (a "//" inside a string ends its line too).
			Matcher forbidden = FORBIDDEN.matcher(code.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " "));
			while (forbidden.find()) {
				String line = name + ": names " + forbidden.group(1);
				if (!found.contains(line)) {
					found.add(line);
				}
			}
			found.sort(null);
			problems.addAll(found);
		}
		return problems;
	}

	/** The core bundle's folder; the test bundle's pom says where it is. */
	private static Path bundle() {
		String dir = System.getProperty("kiroacp.core.bundle");
		assertTrue(dir != null && !dir.isBlank(), "System property kiroacp.core.bundle is not set (the test bundle's pom sets it)");
		Path bundle = Path.of(dir).toAbsolutePath().normalize();
		assertTrue(Files.isDirectory(bundle.resolve("src")), "not the core bundle: " + bundle);
		return bundle;
	}
}
