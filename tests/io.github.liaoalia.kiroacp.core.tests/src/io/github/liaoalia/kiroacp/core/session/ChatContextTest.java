package io.github.liaoalia.kiroacp.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.liaoalia.kiroacp.core.acp.api.KiroAgent;
import io.github.liaoalia.kiroacp.core.host.Settings;
import io.github.liaoalia.kiroacp.core.host.Subscription;
import io.github.liaoalia.kiroacp.core.host.Workspace;
import io.github.liaoalia.kiroacp.core.model.ContextChip;
import io.github.liaoalia.kiroacp.core.model.FileSuggestion;
import io.github.liaoalia.kiroacp.core.model.FromWebview;
import io.github.liaoalia.kiroacp.core.model.ImageAttachment;
import io.github.liaoalia.kiroacp.core.model.MessageRole;
import io.github.liaoalia.kiroacp.core.model.MessageSegment;
import io.github.liaoalia.kiroacp.core.model.ToWebview;
import io.github.liaoalia.kiroacp.core.pure.context.Images;
import io.github.liaoalia.kiroacp.core.services.DiskTextFiles;
import io.github.liaoalia.kiroacp.core.session.Harness.Client;
import io.github.liaoalia.kiroacp.core.session.context.ChatContext;
import io.github.liaoalia.kiroacp.core.session.context.EditorContext;
import io.github.liaoalia.kiroacp.core.session.context.ImagePicker;

/**
 * File, selection and image context against the reference's fake agent: its
 * e2e tests F4 (file context), F5 (selection) and F16 (images) at the level
 * of the page protocol. The editor and the file dialog are fakes; the files
 * are real ones in a sample project.
 *
 * <p>Not here, because they belong to features that are not part of this
 * one: F16.5 and F16.10 (slash commands), F16.8 and F16.9 (steering and the
 * queue).
 */
class ChatContextTest {

	/** A 1x1 PNG: the image sent to the agent. */
	private static final String PNG_1PX = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";
	private static final int PNG_BYTES = Base64.getDecoder().decode(PNG_1PX).length;
	/** A 1x1 WebP standing in for the page's thumbnail: other bytes and another type than the image, so the echo tells which it shows. */
	private static final String PREVIEW = "data:image/webp;base64,UklGRhoAAABXRUJQVlA4TA0AAAAvAAAAEAcQERGIiP4HAA==";

	@TempDir
	Path dir;

	/** The editor the user "works in": a test activates files and selects text in it. */
	private static final class FakeEditor implements EditorContext {
		private final List<Consumer<Path>> listeners = new CopyOnWriteArrayList<>();
		volatile Path file;
		volatile TextSelection selection;

		@Override
		public Path activeFile() {
			return file;
		}

		@Override
		public TextSelection selection() {
			return selection;
		}

		@Override
		public Subscription onActiveFileChanged(Consumer<Path> listener) {
			listeners.add(listener);
			return () -> listeners.remove(listener);
		}

		/** As the workbench tells it: on a thread that is not the controller's. */
		void activate(Path next) {
			file = next;
			if (next != null) {
				listeners.forEach(listener -> listener.accept(next));
			}
		}
	}

	/** The file dialog: answers with what the test chose and counts how often it was opened. */
	private static final class FakePicker implements ImagePicker {
		final List<List<String>> opened = new CopyOnWriteArrayList<>();
		volatile List<Path> choice = List.of();

		@Override
		public CompletionStage<List<Path>> pickImages(List<String> extensions) {
			opened.add(extensions);
			return CompletableFuture.completedFuture(choice);
		}
	}

	/** One root; labels are relative to it, as the reference's are to its workspace folder. */
	private record Rooted(Path root) implements Workspace {

		@Override
		public List<Path> roots() {
			return List.of(root);
		}

		@Override
		public Path defaultCwd() {
			return root;
		}

		@Override
		public boolean contains(Path absolute) {
			return absolute.startsWith(root);
		}

		@Override
		public String label(Path absolute) {
			return absolute.startsWith(root) ? root.relativize(absolute).toString().replace('\\', '/')
					: String.valueOf(absolute.getFileName());
		}
	}

	/** The harness with the feature installed and everything a test steers it with. */
	private final class Fixture implements AutoCloseable {
		final Harness h;
		final FakeEditor editor = new FakeEditor();
		final FakePicker picker = new FakePicker();
		final AtomicBoolean attachActiveFile = new AtomicBoolean();
		final List<Runnable> settingsListeners = new CopyOnWriteArrayList<>();
		/** The content blocks of every {@code session/prompt}. */
		final List<List<JsonNode>> prompts = new CopyOnWriteArrayList<>();
		final ChatContext context;

		Fixture(String... agentArgs) {
			h = Harness.launch(dir, agentArgs);
			h.agentWrapper = agent -> (KiroAgent) Proxy.newProxyInstance(KiroAgent.class.getClassLoader(),
					new Class<?>[] { KiroAgent.class }, (proxy, method, args) -> {
						if (method.getName().equals("prompt")) {
							@SuppressWarnings("unchecked")
							List<JsonNode> blocks = (List<JsonNode>) args[1];
							prompts.add(List.copyOf(blocks));
						}
						try {
							return method.invoke(agent, args);
						} catch (InvocationTargetException e) {
							throw e.getCause();
						}
					});
			Settings settings = (Settings) Proxy.newProxyInstance(Settings.class.getClassLoader(), new Class<?>[] { Settings.class },
					(proxy, method, args) -> {
						if (method.getName().equals("attachActiveFile")) {
							return attachActiveFile.get();
						}
						if (method.getName().equals("onChange")) {
							Runnable listener = (Runnable) args[0];
							settingsListeners.add(listener);
							return (Subscription) () -> settingsListeners.remove(listener);
						}
						return method.invoke(h.settings, args);
					});
			context = ChatContext.install(h.controller, new ChatContext.Host(new Rooted(dir), settings, h.scheduler, new DiskTextFiles(),
					h.logger, editor, picker, null));
		}

		void setAttachActiveFile(boolean on) {
			attachActiveFile.set(on);
			settingsListeners.forEach(Runnable::run);
		}

		List<ContextChip> chips() throws Exception {
			return h.ask(context::chips);
		}

		/** Sends a prompt as the page sends it and waits for its turn to end. */
		void turn(Client view, String text, List<ContextChip> chips, List<ImageAttachment> images) throws Exception {
			int mark = view.mark();
			view.send(new FromWebview.Prompt(text, chips, images));
			view.awaitTurnEnd(mark);
		}

		@Override
		public void close() throws Exception {
			context.close();
			h.close();
		}
	}

	private Path write(String relative, String text) throws IOException {
		return write(relative, text.getBytes(StandardCharsets.UTF_8));
	}

	private Path write(String relative, byte[] bytes) throws IOException {
		Path file = dir.resolve(relative);
		Files.createDirectories(file.getParent());
		return Files.write(file, bytes);
	}

	private static ImageAttachment pasted(String id) {
		return pasted(id, PNG_1PX);
	}

	private static ImageAttachment pasted(String id, String data) {
		return new ImageAttachment(id, "Pasted image " + id + ".png", "image/png", data, PNG_BYTES, null, PREVIEW);
	}

	private static List<JsonNode> messages(Client view, String role) throws Exception {
		List<JsonNode> out = new ArrayList<>();
		for (JsonNode item : view.snapshot().get("items")) {
			if ("message".equals(item.path("type").asText()) && role.equals(item.path("message").path("role").asText())) {
				out.add(item.get("message"));
			}
		}
		return out;
	}

	private static String textOf(JsonNode message) {
		StringBuilder text = new StringBuilder();
		for (JsonNode segment : message.get("segments")) {
			if ("text".equals(segment.path("type").asText())) {
				text.append(segment.get("text").asText());
			}
		}
		return text.toString();
	}

	private static String lastAgentText(Client view) throws Exception {
		List<JsonNode> agent = messages(view, "agent");
		return agent.isEmpty() ? "" : textOf(agent.get(agent.size() - 1));
	}

	private static List<JsonNode> segments(JsonNode message, String type) {
		List<JsonNode> out = new ArrayList<>();
		for (JsonNode segment : message.get("segments")) {
			if (type.equals(segment.path("type").asText())) {
				out.add(segment);
			}
		}
		return out;
	}

	private static List<String> notices(Client view, int mark) {
		return view.page.ofType(ToWebview.Notice.class, mark).stream().map(notice -> notice.notice().text()).toList();
	}

	/** The types of what the page got since the mark, without the session list, which comes whenever its debounce has passed. */
	private static List<String> types(Client view, int mark) {
		return view.page.types(mark).stream().filter(type -> !type.equals("sessions")).toList();
	}

	private static List<ContextChip> lastChips(Client view) {
		return view.page.last(ToWebview.Chips.class).chips();
	}

	// ---------------------------------------------------------------------
	// F4: file context
	// ---------------------------------------------------------------------

	@Test
	void anExplicitFileChipIsSentAsAResourceLinkShownAsAResourceAndClearedAfterwards() throws Exception {
		Path readme = write("README.md", "# Sample\n");
		try (Fixture f = new Fixture()) {
			Client view = f.h.openView("view");

			ContextChip chip = f.h.ask(() -> f.context.addFileChip(readme));
			assertEquals(new ContextChip("file:" + readme, ContextChip.Kind.FILE, readme.toString(), "README.md", null, null, false), chip);
			assertEquals(List.of(chip), f.chips(), "the chip is listed before sending");
			assertEquals(List.of(chip), lastChips(view), "a chips message carrying the chip was posted");
			assertEquals(1, view.snapshot().get("chips").size(), "the state a page gets for ready lists it");

			int mark = view.mark();
			f.turn(view, "/scenario chat look at the readme", List.of(chip), null);

			// What the agent got: the text, then the file as a link (reference D10).
			assertEquals(1, f.prompts.size());
			assertEquals(List.of(Harness.json("{\"type\":\"text\",\"text\":\"/scenario chat look at the readme\"}"),
					Harness.json("{\"type\":\"resource_link\",\"uri\":" + Harness.MAPPER.valueToTree(readme.toUri().toString())
							+ ",\"name\":\"README.md\",\"mimeType\":\"text/markdown\"}")),
					f.prompts.get(0));

			// What the transcript shows: the user's message with a resource segment (reference D13).
			JsonNode user = messages(view, "user").get(0);
			List<JsonNode> resources = segments(user, "resource");
			assertEquals(1, resources.size(), user.toString());
			assertEquals("README.md", resources.get(0).get("label").asText());
			String uri = resources.get(0).get("uri").asText();
			assertTrue(uri.startsWith("file:") && uri.endsWith("README.md"), uri);
			assertTrue(view.page.ofType(ToWebview.Chunk.class, mark).stream()
					.anyMatch(chunk -> chunk.role() == MessageRole.USER && chunk.segment() instanceof MessageSegment.Resource),
					"a user chunk with a resource segment was posted");

			assertEquals(List.of(), f.chips(), "explicit chips are cleared after the prompt was sent");
			assertEquals(List.of(), lastChips(view), "and the page was told");
			assertFalse(messages(view, "agent").isEmpty(), "the agent answered the prompt");
			assertEquals(view.snapshot(), view.page.model(), "the page arrived at the state it would get for ready");
		}
	}

	@Test
	void queryFilesAnswersWithTheRankedFilesOfTheSampleProject() throws Exception {
		Path sample = write("src/sample.ts", "export const sample = 1;\n");
		write("sample.config.json", "{}");
		write("test/my-sample.test.ts", "");
		write("samples/readme.md", "the folder matches, the file's name does not");
		write("README.md", "# Sample\n");
		// What the reference never searches.
		write("node_modules/sample-lib/sample.js", "");
		write("dist/sample.js", "");
		write("out/sample.js", "");
		write(".git/sample", "");
		try (Fixture f = new Fixture()) {
			Client view = f.h.openView("view");
			Client other = f.h.openView("other");

			int mark = view.mark();
			int otherMark = other.mark();
			view.send(new FromWebview.QueryFiles("samp"));
			ToWebview.FileSuggestions answer = view.page.await(ToWebview.FileSuggestions.class, mark, m -> m.query().equals("samp"),
					"file suggestions for 'samp'");

			assertEquals(List.of(new FileSuggestion(sample.toString(), "src/sample.ts"),
					new FileSuggestion(dir.resolve("sample.config.json").toString(), "sample.config.json"),
					new FileSuggestion(dir.resolve("test").resolve("my-sample.test.ts").toString(), "test/my-sample.test.ts")),
					answer.items(), "name starts with the query, shorter label first; then the name that only contains it");

			// A query with a folder; and the empty one, which the picker sends for a lone "@".
			mark = view.mark();
			view.send(new FromWebview.QueryFiles("src\\samp"));
			assertEquals(List.of("src/sample.ts"), view.page.await(ToWebview.FileSuggestions.class, mark, m -> true, "suggestions")
					.items().stream().map(FileSuggestion::label).toList());
			mark = view.mark();
			view.send(new FromWebview.QueryFiles(""));
			assertEquals(List.of("README.md", "src/sample.ts", "samples/readme.md", "sample.config.json", "test/my-sample.test.ts"),
					view.page.await(ToWebview.FileSuggestions.class, mark, m -> m.query().isEmpty(), "every file").items().stream()
							.map(FileSuggestion::label).toList(),
					"every file outside the excluded folders, the shortest labels first");
			mark = view.mark();
			view.send(new FromWebview.QueryFiles("no-such-file"));
			assertEquals(List.of(), view.page.await(ToWebview.FileSuggestions.class, mark, m -> true, "no suggestions").items());

			f.h.scheduler.flush();
			assertEquals(List.of(), other.page.ofType(ToWebview.FileSuggestions.class, otherMark), "only the page that asked is answered");
		}
	}

	@Test
	void noMoreThanThirtyOfTheFirstHundredAndFiftyMatchesAreSuggested() throws Exception {
		for (int i = 0; i < 160; i++) {
			write(String.format("many/file-%03d.txt", i), "");
		}
		write("file-top.txt", "");
		try (Fixture f = new Fixture()) {
			Client view = f.h.openView("view");
			int mark = view.mark();
			view.send(new FromWebview.QueryFiles("file-"));
			List<FileSuggestion> items = view.page.await(ToWebview.FileSuggestions.class, mark, m -> true, "suggestions").items();
			assertEquals(30, items.size());
			assertEquals("file-top.txt", items.get(0).label(), "the shortest label");
			assertEquals("many/file-000.txt", items.get(1).label());
			assertEquals("many/file-028.txt", items.get(29).label());
		}
	}

	@Test
	void theActiveFileIsAnImplicitChipWhenTheUserWantsItAttachedAndCanBeRemoved() throws Exception {
		Path sample = write("src/sample.ts", "one\ntwo\n");
		Path other = write("src/other.ts", "");
		try (Fixture f = new Fixture()) {
			Client view = f.h.openView("view");
			ContextChip implicit = new ContextChip("file:" + sample, ContextChip.Kind.FILE, sample.toString(), "src/sample.ts", null, null,
					true);

			// Off by default, as in the reference: the editor is followed, nothing is attached.
			int mark = view.mark();
			f.editor.activate(sample);
			view.page.await(ToWebview.Chips.class, mark, m -> true, "the chips after an editor was activated");
			assertEquals(List.of(), lastChips(view));

			mark = view.mark();
			f.setAttachActiveFile(true);
			view.page.await(ToWebview.Chips.class, mark, m -> m.chips().equals(List.of(implicit)), "an implicit chip for src/sample.ts");
			assertEquals(List.of(implicit), f.chips());

			// A setting that has nothing to do with the chips changes: the pages are not told again.
			mark = view.mark();
			f.setAttachActiveFile(true);
			f.h.scheduler.flush();
			assertEquals(List.of(), view.page.ofType(ToWebview.Chips.class, mark));

			// The chip's remove button.
			mark = view.mark();
			view.send(new FromWebview.RemoveChip(implicit.id()));
			view.page.await(ToWebview.Chips.class, mark, m -> m.chips().isEmpty(), "the implicit chip to go after removeChip");
			assertEquals(List.of(), f.chips());

			// It stays removed until the editor changes.
			mark = view.mark();
			f.editor.activate(other);
			view.page.await(ToWebview.Chips.class, mark, m -> m.chips().size() == 1 && m.chips().get(0).label().equals("src/other.ts"),
					"the chip of the editor activated next");

			// The implicit chip goes out with a prompt like any other and is attached again afterwards.
			List<ContextChip> sent = f.chips();
			f.turn(view, "/scenario chat with the active file", sent, null);
			assertEquals("resource_link", f.prompts.get(0).get(1).get("type").asText());
			assertEquals("src/other.ts", f.prompts.get(0).get(1).get("name").asText());
			assertEquals(sent, f.chips(), "still attached for the next prompt");

			mark = view.mark();
			f.setAttachActiveFile(false);
			view.page.await(ToWebview.Chips.class, mark, m -> m.chips().isEmpty(), "no chip once the setting is off");
		}
	}

	// ---------------------------------------------------------------------
	// F5: selection
	// ---------------------------------------------------------------------

	@Test
	void sendSelectionAddsASelectionChipWhoseLinesGoToTheAgentAsAnEmbeddedResource() throws Exception {
		Path sample = write("src/sample.ts", "line1\r\nline2\r\nline3\r\nline4\r\nline5\r\nline6\r\n");
		try (Fixture f = new Fixture()) {
			Client view = f.h.openView("view");

			assertFalse(f.h.ask(f.context::addSelectionChip), "without an editor of a file there is nothing to send");
			assertEquals(List.of(), f.chips());

			// 0-based positions: line 2 column 0 to line 4 column 3, which is lines 3-5.
			f.editor.selection = new EditorContext.TextSelection(sample, 2, 0, 4, 3);
			int mark = view.mark();
			assertTrue(f.h.ask(f.context::addSelectionChip));

			ContextChip chip = new ContextChip("sel:" + sample + ":3-5", ContextChip.Kind.SELECTION, sample.toString(), "src/sample.ts", 3,
					5, false);
			assertEquals(List.of(chip), f.chips());
			assertEquals(List.of(chip), lastChips(view));
			JsonNode listed = view.snapshot().get("chips");
			assertEquals(1, listed.size(), "the snapshot lists the selection chip");
			assertEquals(Harness.json("{\"kind\":\"selection\",\"startLine\":3,\"endLine\":5,\"implicit\":false}"),
					((com.fasterxml.jackson.databind.node.ObjectNode) listed.get(0).deepCopy()).retain("kind", "startLine", "endLine", "implicit"));
			assertEquals(List.of("chips", "focusInput"), types(view, mark), "the composer is told to take the focus");

			f.turn(view, "/scenario chat explain", List.of(chip), null);

			// The fake agent advertises promptCapabilities.embeddedContext (reference D10).
			assertEquals(List.of(Harness.json("{\"type\":\"text\",\"text\":\"/scenario chat explain\"}"),
					Harness.json("{\"type\":\"resource\",\"resource\":{\"uri\":"
							+ Harness.MAPPER.valueToTree(sample.toUri().toString() + "#L3-L5")
							+ ",\"text\":\"line3\\nline4\\nline5\",\"mimeType\":\"text/typescript\"}}")),
					f.prompts.get(0));
			List<JsonNode> resources = segments(messages(view, "user").get(0), "resource");
			assertEquals("src/sample.ts:3-5", resources.get(0).get("label").asText(), "the echo names the lines");
			assertEquals(List.of(), f.chips(), "the selection is used up by the prompt");
		}
	}

	@Test
	void aSelectionWhoseFileIsGoneIsSentAsALinkToTheFile() throws Exception {
		Path gone = dir.resolve("src").resolve("gone.py");
		try (Fixture f = new Fixture()) {
			Client view = f.h.openView("view");
			f.editor.selection = new EditorContext.TextSelection(gone, 0, 0, 0, 0);
			assertTrue(f.h.ask(f.context::addSelectionChip));
			List<ContextChip> chips = f.chips();
			assertEquals(1, chips.get(0).startLine().intValue(), "the caret's line stands for an empty selection");

			f.turn(view, "/scenario chat", chips, null);

			assertEquals(Harness.json("{\"type\":\"resource_link\",\"uri\":" + Harness.MAPPER.valueToTree(gone.toUri().toString())
					+ ",\"name\":\"src/gone.py\",\"mimeType\":\"text/x-python\"}"), f.prompts.get(0).get(1));
		}
	}

	// ---------------------------------------------------------------------
	// F16: images
	// ---------------------------------------------------------------------

	@Test
	void textAndImageGoOutAsTextAndImageBlocksAndTheEchoShowsTheThumbnailNotTheImage() throws Exception {
		try (Fixture f = new Fixture()) {
			Client view = f.h.openView("view");
			assertTrue(view.snapshot().at("/connection/acceptsImages").asBoolean(), "the fake agent advertises promptCapabilities.image");

			int mark = view.mark();
			f.turn(view, "/scenario images what is on it?", List.of(), List.of(pasted("a")));

			assertEquals("Received 1 image(s): image/png " + PNG_BYTES + " B; blocks: text,image.", lastAgentText(view),
					"what the agent received (the full PNG, not the thumbnail)");
			assertEquals(Harness.json("{\"type\":\"image\",\"mimeType\":\"image/png\",\"data\":\"" + PNG_1PX + "\"}"),
					f.prompts.get(0).get(1));
			JsonNode user = messages(view, "user").get(0);
			assertEquals("/scenario images what is on it?", textOf(user));
			List<JsonNode> images = segments(user, "image");
			assertEquals(1, images.size());
			assertEquals(PREVIEW, images.get(0).get("src").asText(), "the echo shows the thumbnail");
			assertTrue(view.page.ofType(ToWebview.Chunk.class, mark).stream().anyMatch(chunk -> chunk.role() == MessageRole.USER
					&& chunk.segment() instanceof MessageSegment.Image image && PREVIEW.equals(image.src())),
					"an image chunk carrying the thumbnail was posted to the page");
		}
	}

	@Test
	void aMessageOfImagesOnlyIsSentAndTheTypeOfAnImageIsTheOneOfItsBytes() throws Exception {
		try (Fixture f = new Fixture()) {
			Client view = f.h.openView("view");
			ImageAttachment mislabelled = new ImageAttachment("c", "shot.jpg", "image/jpeg", PNG_1PX, 1, null, PREVIEW);
			f.turn(view, "", List.of(), List.of(pasted("b"), mislabelled));

			assertEquals("Received 2 image(s): image/png " + PNG_BYTES + " B, image/png " + PNG_BYTES + " B; blocks: image,image.",
					lastAgentText(view));
			List<JsonNode> images = segments(messages(view, "user").get(0), "image");
			assertEquals(2, images.size());
			images.forEach(image -> assertEquals(PREVIEW, image.get("src").asText()));
		}
	}

	@Test
	void bytesThatAreNoImageAnOversizedImageAndTooManyImagesAreRefusedBeforeAnythingIsSent() throws Exception {
		try (Fixture f = new Fixture()) {
			Client view = f.h.openView("view");
			int first = view.mark();

			int mark = view.mark();
			String junk = Base64.getEncoder().encodeToString("hello, not a png".getBytes(StandardCharsets.UTF_8));
			view.send(new FromWebview.Prompt("/scenario images look", List.of(), List.of(pasted("junk", junk))));
			view.page.await(ToWebview.Notice.class, mark, m -> true, "a notice about the junk");
			assertEquals(List.of("Pasted image junk.png was not attached: it is not a PNG, JPEG, GIF or WebP image. The message was not sent."),
					notices(view, mark));

			mark = view.mark();
			byte[] big = new byte[Images.MAX_IMAGE_BYTES + 1];
			System.arraycopy(Base64.getDecoder().decode(PNG_1PX), 0, big, 0, PNG_BYTES);
			view.send(new FromWebview.Prompt("/scenario images look", List.of(),
					List.of(pasted("ok"), pasted("big", Base64.getEncoder().encodeToString(big)))));
			view.page.await(ToWebview.Notice.class, mark, m -> true, "a notice about the big image");
			assertEquals(List.of("Pasted image big.png was not attached: it is 5.1 MB, images up to 5 MB can be sent. The message was not sent."),
					notices(view, mark));

			mark = view.mark();
			List<ImageAttachment> many = new ArrayList<>();
			for (int i = 0; i < 11; i++) {
				many.add(pasted("m" + i));
			}
			view.send(new FromWebview.Prompt("/scenario images look", List.of(), many));
			view.page.await(ToWebview.Notice.class, mark, m -> true, "a notice about the count");
			assertEquals(List.of("A message can carry up to 10 images (this one had 11), so it was not sent."), notices(view, mark));

			f.h.scheduler.flush();
			assertEquals(List.of(), messages(view, "user"), "nothing was echoed");
			assertEquals(List.of(), view.page.ofType(ToWebview.Turn.class, first), "no turn was started");
			assertEquals(List.of(), f.prompts, "nothing was sent");
		}
	}

	@Test
	void aRefusedImageKeepsTheChipsOfItsMessage() throws Exception {
		Path readme = write("README.md", "# Sample\n");
		try (Fixture f = new Fixture()) {
			Client view = f.h.openView("view");
			ContextChip chip = f.h.ask(() -> f.context.addFileChip(readme));
			int mark = view.mark();
			view.send(new FromWebview.Prompt("look", List.of(chip), List.of(pasted("empty", ""))));
			view.page.await(ToWebview.Notice.class, mark, m -> true, "the refusal");
			f.h.scheduler.flush();
			assertEquals(List.of(chip), f.chips(), "the message was not sent, so its chips are not used up");
		}
	}

	@Test
	void theAttachButtonReadsThePickedFilesAndAFakeImageIsRefused() throws Exception {
		Path png = write("pic.png", Base64.getDecoder().decode(PNG_1PX));
		Path fake = write("fake.gif", "GIF? no");
		Path missing = dir.resolve("missing.png");
		try (Fixture f = new Fixture()) {
			Client view = f.h.openView("view");
			Client other = f.h.openView("other");
			f.picker.choice = List.of(png, fake, missing);

			int mark = view.mark();
			int otherMark = other.mark();
			view.send(new FromWebview.PickImages());
			ToWebview.ImagesAttached attached = view.page.await(ToWebview.ImagesAttached.class, mark, m -> true, "the picked image");

			assertEquals(List.of(List.of("png", "jpg", "jpeg", "gif", "webp")), f.picker.opened, "one dialog, for the accepted types");
			assertEquals(List.of(new ImageAttachment("host-img-1", "pic.png", "image/png", PNG_1PX, PNG_BYTES, png.toString(), null)),
					attached.images());
			assertEquals(List.of("fake.gif was not attached: it is not a PNG, JPEG, GIF or WebP image. "
					+ "missing.png was not attached: the file does not exist."), notices(view, mark));
			f.h.scheduler.flush();
			assertEquals(List.of(), other.page.ofType(ToWebview.ImagesAttached.class, otherMark), "only the page that asked gets the images");

			// The page sends the image back with the prompt; its path becomes the block's uri.
			ImageAttachment image = attached.images().get(0);
			f.turn(view, "/scenario images from a file", List.of(), List.of(image));
			assertEquals(Harness.json("{\"type\":\"image\",\"mimeType\":\"image/png\",\"data\":\"" + PNG_1PX + "\",\"uri\":"
					+ Harness.MAPPER.valueToTree(png.toUri().toString()) + "}"), f.prompts.get(0).get(1));

			// Closing the dialog without a choice attaches nothing and says nothing.
			f.picker.choice = List.of();
			mark = view.mark();
			view.send(new FromWebview.PickImages());
			f.h.await(() -> f.picker.opened.size() == 2, "the dialog to be opened again");
			f.h.scheduler.flush();
			assertEquals(List.of(), types(view, mark));
		}
	}

	@Test
	void anImageFileOverTheLimitIsRefusedFromItsSizeAndOnlyTenImagesAreReadPerPick() throws Exception {
		List<Path> picked = new ArrayList<>();
		picked.add(write("big.png", new byte[Images.MAX_IMAGE_BYTES + 1]));
		for (int i = 0; i < 11; i++) {
			picked.add(write("pic-" + i + ".png", Base64.getDecoder().decode(PNG_1PX)));
		}
		try (Fixture f = new Fixture()) {
			Client view = f.h.openView("view");
			f.picker.choice = picked;
			int mark = view.mark();
			view.send(new FromWebview.PickImages());
			ToWebview.ImagesAttached attached = view.page.await(ToWebview.ImagesAttached.class, mark, m -> true, "the picked images");

			assertEquals(9, attached.images().size(), "the first ten files are looked at, one of them is refused");
			assertEquals(List.of("big.png was not attached: it is 5.1 MB, images up to 5 MB can be sent. "
					+ "2 more images were not attached: a message can carry up to 10 images."), notices(view, mark));
		}
	}

	@Test
	void droppedFilesImagesAreAttachedOtherFilesBecomeChips() throws Exception {
		Path png = write("drop me.png", Base64.getDecoder().decode(PNG_1PX));
		Path readme = write("README.md", "# Sample\n");
		Files.createDirectories(dir.resolve("folder"));
		try (Fixture f = new Fixture()) {
			Client view = f.h.openView("view");
			int mark = view.mark();
			view.send(new FromWebview.DropFiles(List.of(png.toUri().toString(), readme.toUri().toString(),
					// Ignored: a folder, a file that is not there, a web link, something that is no URI, nothing at all.
					dir.resolve("folder").toUri().toString(), dir.resolve("nothing.txt").toUri().toString(), "https://example.com/a.txt",
					"not a uri", "  ")));
			ToWebview.ImagesAttached attached = view.page.await(ToWebview.ImagesAttached.class, mark, m -> true, "the dropped image");
			view.page.await(ToWebview.FocusInput.class, mark, m -> true, "the focus for the composer");

			assertEquals(List.of("drop me.png"), attached.images().stream().map(ImageAttachment::name).toList(), "the image was attached");
			assertEquals(png.toString(), attached.images().get(0).path());
			assertEquals(List.of(new ContextChip("file:" + readme, ContextChip.Kind.FILE, readme.toString(), "README.md", null, null, false)),
					f.chips(), "README.md became a file chip");
			assertEquals(f.chips(), lastChips(view));
			assertEquals(List.of(), notices(view, mark));
		}
	}

	@Test
	void aDroppedImageThatCannotBeReadIsNamedAndNothingElseHappens() throws Exception {
		try (Fixture f = new Fixture()) {
			Client view = f.h.openView("view");
			int mark = view.mark();
			view.send(new FromWebview.DropFiles(List.of("vscode-remote://host/work/shot.png", dir.resolve("gone.jpg").toUri().toString())));
			view.page.await(ToWebview.Notice.class, mark, m -> true, "the refusals");
			f.h.scheduler.flush();
			assertEquals(List.of("shot.png was not attached: only files of this computer can be read. "
					+ "gone.jpg was not attached: the file does not exist."), notices(view, mark));
			assertEquals(List.of("notice"), types(view, mark), "no images, no chips, no focus");
		}
	}

	@Test
	void anAgentWithoutImageSupportImagesAreRefusedBeforeAnythingIsSentAndNothingIsRead() throws Exception {
		Path png = write("refused.png", Base64.getDecoder().decode(PNG_1PX));
		Path readme = write("README.md", "# Sample\n");
		try (Fixture f = new Fixture("--no-image")) {
			Client view = f.h.openView("view");
			assertFalse(view.snapshot().at("/connection/acceptsImages").asBoolean(true), "acceptsImages without promptCapabilities.image");

			int mark = view.mark();
			view.send(new FromWebview.Prompt("/scenario images look", List.of(), List.of(pasted("n"))));
			view.page.await(ToWebview.Notice.class, mark, m -> true, "a notice that says why");
			f.h.scheduler.flush();
			assertEquals(List.of("Kiro does not accept images, so the message was not sent."), notices(view, mark));
			assertEquals(List.of(), messages(view, "user"), "nothing was echoed");
			assertEquals(List.of(), view.page.ofType(ToWebview.Turn.class, mark), "no turn was started");

			mark = view.mark();
			f.picker.choice = List.of(png);
			view.send(new FromWebview.PickImages());
			view.page.await(ToWebview.Notice.class, mark, m -> true, "the attach button's answer");
			view.send(new FromWebview.DropFiles(List.of(png.toUri().toString(), readme.toUri().toString())));
			view.page.await(ToWebview.FocusInput.class, mark, m -> true, "the drop to be dealt with");
			f.h.scheduler.flush();

			assertEquals(List.of(), f.picker.opened, "no file dialog was opened");
			assertEquals(List.of(), view.page.ofType(ToWebview.ImagesAttached.class, mark), "no image was handed to the composer");
			assertEquals(List.of("Kiro does not accept images.", "Kiro does not accept images, so the image was not attached."),
					notices(view, mark), "the attach button and the drop each say why");
			assertEquals(1, f.chips().size(), "the other dropped file is a chip all the same");

			// Text and chips go out as ever.
			f.turn(view, "/scenario chat still works", f.chips(), null);
			assertNotNull(f.prompts.get(0).get(1).get("uri"));
		}
	}
}
