package io.github.liaoalia.kiroacp.core.pure.context;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.liaoalia.kiroacp.core.model.ContextChip;
import io.github.liaoalia.kiroacp.core.model.ImageAttachment;
import io.github.liaoalia.kiroacp.core.pure.text.NodePath;
import io.github.liaoalia.kiroacp.core.pure.text.Platform;

/**
 * Builds the {@code session/prompt} content blocks from the typed text, the
 * attached images and the context chips (the reference's
 * {@code features/pure/contentBlocks.ts}; its decisions D10 and D29).
 */
public final class ContentBlocks {

	/** Reads the current text of a file by its absolute path. */
	@FunctionalInterface
	public interface TextReader {

		String read(String absPath) throws IOException;
	}

	private static final Map<String, String> MIME_BY_EXT = Map.ofEntries(Map.entry(".ts", "text/typescript"),
			Map.entry(".tsx", "text/typescript"), Map.entry(".js", "text/javascript"), Map.entry(".mjs", "text/javascript"),
			Map.entry(".cjs", "text/javascript"), Map.entry(".jsx", "text/javascript"), Map.entry(".json", "application/json"),
			Map.entry(".md", "text/markdown"), Map.entry(".py", "text/x-python"), Map.entry(".rs", "text/x-rust"),
			Map.entry(".go", "text/x-go"), Map.entry(".java", "text/x-java"), Map.entry(".c", "text/x-c"),
			Map.entry(".h", "text/x-c"), Map.entry(".cpp", "text/x-c++"), Map.entry(".hpp", "text/x-c++"),
			Map.entry(".cs", "text/x-csharp"), Map.entry(".html", "text/html"), Map.entry(".css", "text/css"),
			Map.entry(".yaml", "text/yaml"), Map.entry(".yml", "text/yaml"), Map.entry(".toml", "text/toml"),
			Map.entry(".xml", "text/xml"), Map.entry(".sh", "text/x-shellscript"), Map.entry(".ps1", "text/x-powershell"),
			Map.entry(".txt", "text/plain"));

	private static final Map<String, String> LANG_BY_EXT = Map.ofEntries(Map.entry(".ts", "ts"), Map.entry(".tsx", "tsx"),
			Map.entry(".js", "js"), Map.entry(".mjs", "js"), Map.entry(".cjs", "js"), Map.entry(".jsx", "jsx"),
			Map.entry(".json", "json"), Map.entry(".md", "md"), Map.entry(".py", "python"), Map.entry(".rs", "rust"),
			Map.entry(".go", "go"), Map.entry(".java", "java"), Map.entry(".c", "c"), Map.entry(".h", "c"),
			Map.entry(".cpp", "cpp"), Map.entry(".hpp", "cpp"), Map.entry(".cs", "csharp"), Map.entry(".html", "html"),
			Map.entry(".css", "css"), Map.entry(".yaml", "yaml"), Map.entry(".yml", "yaml"), Map.entry(".toml", "toml"),
			Map.entry(".xml", "xml"), Map.entry(".sh", "bash"), Map.entry(".ps1", "powershell"), Map.entry(".sql", "sql"));

	private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

	private ContentBlocks() {
	}

	/** MIME type for a few common text file types; {@code null} otherwise. */
	public static String guessMimeType(String filePath) {
		return MIME_BY_EXT.get(extension(filePath));
	}

	/** Fence language id for a path (empty when unknown). */
	public static String languageForPath(String filePath) {
		return LANG_BY_EXT.getOrDefault(extension(filePath), "");
	}

	/** Node's {@code path.extname}, lower case: from the last dot of the file's name, unless the name starts with it. */
	private static String extension(String filePath) {
		String base = filePath.substring(Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\')) + 1);
		int dot = base.lastIndexOf('.');
		return dot <= 0 ? "" : base.substring(dot).toLowerCase(Locale.ROOT);
	}

	/** {@link #fileUrlFor(String, Platform)} for the platform this runs on. */
	public static String fileUrlFor(String absPath) {
		return fileUrlFor(absPath, null);
	}

	/**
	 * {@code file:///…} URL for an absolute path, as Node's
	 * {@code pathToFileURL(path).href} writes it: {@code C:\ws\a b.ts} is
	 * {@code file:///C:/ws/a%20b.ts}, {@code \\server\share\a} is
	 * {@code file://server/share/a}.
	 *
	 * @param platform whose paths {@code absPath} is one of; {@code null} for the current one
	 */
	public static String fileUrlFor(String absPath, Platform platform) {
		Platform plat = Platform.orCurrent(platform);
		String resolved = NodePath.resolve(absPath, plat);
		if (plat == Platform.WIN32) {
			String forward = resolved.replace('\\', '/');
			if (forward.startsWith("//")) {
				// UNC: the server is the URL's host.
				int share = forward.indexOf('/', 2);
				String host = share < 0 ? forward.substring(2) : forward.substring(2, share);
				return "file://" + host + encodePath(share < 0 ? "/" : forward.substring(share), true);
			}
			return "file:///" + encodePath(forward, true);
		}
		return "file://" + encodePath(resolved, false);
	}

	/** Percent-encodes what a URL's path cannot hold as it is; {@code /} stays, and so does the {@code :} of a drive. */
	private static String encodePath(String path, boolean windows) {
		StringBuilder out = new StringBuilder(path.length() + 16);
		for (byte b : path.getBytes(StandardCharsets.UTF_8)) {
			int c = b & 0xff;
			boolean encode = c <= 0x20 || c >= 0x7f || c == '"' || c == '#' || c == '%' || c == '<' || c == '>' || c == '?'
					|| c == '^' || c == '`' || c == '{' || c == '}' || (!windows && c == '\\');
			if (encode) {
				out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
						.append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
			} else {
				out.append((char) c);
			}
		}
		return out.toString();
	}

	/** Lines {@code start..end} (1-based, inclusive) of {@code text}, joined with a line feed. */
	public static String extractLines(String text, int start, int end) {
		String[] lines = text.split("\\r?\\n", -1);
		int from = Math.max(0, Math.min(start, end) - 1);
		int to = Math.min(lines.length, Math.max(start, end));
		if (from >= to) {
			return "";
		}
		return String.join("\n", List.of(lines).subList(from, to));
	}

	/** What a chip is called in the transcript: its label, with the lines of a selection. */
	public static String chipLabel(ContextChip chip) {
		if (chip.kind() == ContextChip.Kind.SELECTION && chip.startLine() != null) {
			return chip.label() + ":" + chip.startLine() + "-" + (chip.endLine() != null ? chip.endLine() : chip.startLine());
		}
		return chip.label();
	}

	private static ObjectNode fileLinkBlock(ContextChip chip) {
		ObjectNode block = JSON.objectNode().put("type", "resource_link").put("uri", fileUrlFor(chip.path())).put("name",
				chip.label());
		String mimeType = guessMimeType(chip.path());
		if (mimeType != null) {
			block.put("mimeType", mimeType);
		}
		return block;
	}

	/**
	 * Compose the prompt: the text block first (when non-empty), then one
	 * {@code image} block per image, then one block per chip. Files become
	 * {@code resource_link}s; selections become an embedded {@code resource}
	 * ({@code uri#L<start>-L<end>}) when {@code embeddedContext}, else a
	 * fenced {@code text} block. A selection whose file cannot be read
	 * degrades to a {@code resource_link} for the file.
	 *
	 * @param read            only called for selections
	 * @param embeddedContext the agent advertised {@code promptCapabilities.embeddedContext}
	 * @param images          already checked ({@link Images#checkPromptImages}), and only passed when the agent accepts images
	 */
	public static List<JsonNode> buildPromptBlocks(String text, List<ContextChip> chips, TextReader read, boolean embeddedContext,
			List<ImageAttachment> images) {
		List<JsonNode> blocks = new ArrayList<>();
		if (!text.isEmpty()) {
			blocks.add(JSON.objectNode().put("type", "text").put("text", text));
		}
		for (ImageAttachment image : images) {
			blocks.add(Images.imageBlock(image));
		}
		Set<String> seen = new HashSet<>();
		for (ContextChip chip : chips) {
			if (!seen.add(chip.id())) {
				continue;
			}
			if (chip.kind() != ContextChip.Kind.SELECTION) {
				blocks.add(fileLinkBlock(chip));
				continue;
			}
			int start = chip.startLine() != null ? chip.startLine() : 1;
			int end = chip.endLine() != null ? chip.endLine() : start;
			String snippet;
			try {
				snippet = extractLines(read.read(chip.path()), start, end);
			} catch (IOException | RuntimeException e) {
				blocks.add(fileLinkBlock(chip));
				continue;
			}
			String mimeType = guessMimeType(chip.path());
			if (embeddedContext) {
				ObjectNode resource = JSON.objectNode().put("uri", fileUrlFor(chip.path()) + "#L" + start + "-L" + end).put("text",
						snippet);
				if (mimeType != null) {
					resource.put("mimeType", mimeType);
				}
				ObjectNode block = JSON.objectNode().put("type", "resource");
				block.set("resource", resource);
				blocks.add(block);
			} else {
				blocks.add(JSON.objectNode().put("type", "text").put("text", "Selection from " + chip.label() + ":" + start + "-"
						+ end + ":\n```" + languageForPath(chip.path()) + "\n" + snippet + "\n```"));
			}
		}
		return blocks;
	}

	/**
	 * What the user's message shows after its text (reference D13), in the
	 * order of the prompt's blocks: one image per attachment (its thumbnail),
	 * then one resource per chip.
	 */
	public static List<JsonNode> echoBlocks(List<ContextChip> chips, List<ImageAttachment> images) {
		List<JsonNode> blocks = new ArrayList<>();
		for (ImageAttachment image : images) {
			blocks.add(Images.echoImageContent(image));
		}
		for (ContextChip chip : chips) {
			blocks.add(JSON.objectNode().put("type", "resource_link").put("uri", fileUrlFor(chip.path())).put("name",
					chipLabel(chip)));
		}
		return blocks;
	}
}
