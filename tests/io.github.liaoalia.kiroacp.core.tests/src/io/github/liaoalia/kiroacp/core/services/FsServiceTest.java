package io.github.liaoalia.kiroacp.core.services;

import static io.github.liaoalia.kiroacp.core.services.Fakes.failure;
import static io.github.liaoalia.kiroacp.core.services.Fakes.get;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

class FsServiceTest {

	@TempDir
	Path temp;

	private Path root;
	private Path outside;
	private final List<Path> asked = new CopyOnWriteArrayList<>();
	private volatile CompletionStage<Boolean> confirmation = CompletableFuture.completedFuture(Boolean.FALSE);
	private final List<FsService.FsWriteEvent> written = new CopyOnWriteArrayList<>();
	private final Fakes.RecordingLogger logger = new Fakes.RecordingLogger();
	private FsService fs;

	@BeforeEach
	void setUp() throws IOException {
		root = Files.createDirectory(temp.resolve("workspace"));
		outside = Files.createDirectory(temp.resolve("elsewhere"));
		fs = service(new DiskTextFiles(file -> {
			asked.add(file);
			return confirmation;
		}));
	}

	@AfterEach
	void tearDown() {
		fs.close();
	}

	private FsService service(TextFiles files) {
		FsService service = new FsService(new Fakes.FakeWorkspace(root), files, logger);
		service.onDidWrite(written::add);
		return service;
	}

	private static ObjectNode read(Path file) {
		return Fakes.MAPPER.createObjectNode().put("sessionId", "s1").put("path", file.toString());
	}

	private static ObjectNode write(Path file, String content) {
		return read(file).put("content", content);
	}

	private static String text(Path file) throws IOException {
		return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
	}

	@Test
	void readsAFileAsUtf8() throws Exception {
		Path file = root.resolve("README.md");
		Files.write(file, "# Grüße\nline two\n".getBytes(StandardCharsets.UTF_8));

		JsonNode response = get(fs.readTextFile(read(file)));

		assertEquals(Fakes.json("{\"content\":\"# Grüße\\nline two\\n\"}"), response);
	}

	@Test
	void readsTheLinesAsked() throws Exception {
		Path file = root.resolve("lines.txt");
		Files.write(file, "one\ntwo\r\nthree\nfour".getBytes(StandardCharsets.UTF_8));

		assertEquals("two\r\nthree\n", get(fs.readTextFile(read(file).put("line", 2).put("limit", 2))).path("content").asText());
		assertEquals("three\nfour", get(fs.readTextFile(read(file).put("line", 3))).path("content").asText());
		assertEquals("one\n", get(fs.readTextFile(read(file).put("limit", 1))).path("content").asText());
		assertEquals("", get(fs.readTextFile(read(file).put("line", 9))).path("content").asText());
		assertEquals("", get(fs.readTextFile(read(file).put("limit", 0))).path("content").asText());
		ObjectNode nulls = read(file);
		nulls.putNull("line");
		nulls.putNull("limit");
		assertEquals("one\ntwo\r\nthree\nfour", get(fs.readTextFile(nulls)).path("content").asText());
	}

	@Test
	void aByteOrderMarkIsNotPartOfTheText() throws Exception {
		Path file = root.resolve("bom.txt");
		Files.write(file, new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'h', 'i' });
		assertEquals("hi", get(fs.readTextFile(read(file))).path("content").asText());
	}

	@Test
	void readingNeedsNoConfirmationOutsideTheWorkspace() throws Exception {
		Path file = outside.resolve("notes.txt");
		Files.write(file, "elsewhere".getBytes(StandardCharsets.UTF_8));

		assertEquals("elsewhere", get(fs.readTextFile(read(file))).path("content").asText());
		assertTrue(asked.isEmpty());
	}

	@Test
	void aPathThatIsNotAbsoluteIsRefused() throws Exception {
		Files.write(root.resolve("a.txt"), new byte[] { 'a' });

		for (CompletableFuture<JsonNode> request : List.of(
				fs.readTextFile(Fakes.MAPPER.createObjectNode().put("path", "a.txt")),
				fs.readTextFile(Fakes.MAPPER.createObjectNode()), fs.readTextFile(null),
				fs.writeTextFile(Fakes.MAPPER.createObjectNode().put("path", "sub/a.txt").put("content", "x")),
				fs.writeTextFile(Fakes.MAPPER.createObjectNode().put("content", "x")))) {
			Throwable failure = failure(request);
			assertInstanceOf(IllegalArgumentException.class, failure);
			assertEquals("path must be absolute", failure.getMessage());
		}
		assertTrue(written.isEmpty());
	}

	@Test
	void readingAMissingFileFails() throws Exception {
		assertInstanceOf(NoSuchFileException.class, failure(fs.readTextFile(read(root.resolve("missing.txt")))));
	}

	@Test
	void writesANewFileCreatingItsDirectories() throws Exception {
		Path file = root.resolve("deep").resolve("er").resolve("hello.txt");

		JsonNode response = get(fs.writeTextFile(write(file, "hello from kiro\nÄ\n")));

		assertEquals(Fakes.json("{}"), response);
		assertEquals("hello from kiro\nÄ\n", text(file));
		assertEquals(List.of(new FsService.FsWriteEvent(file.toString(), "hello from kiro\nÄ\n")), written);
		assertTrue(asked.isEmpty());
	}

	@Test
	void replacesTheWholeTextOfAnExistingFile() throws Exception {
		Path file = root.resolve("config.json");
		Files.write(file, "a much longer text than the new one".getBytes(StandardCharsets.UTF_8));

		get(fs.writeTextFile(write(file, "{}")));

		assertEquals("{}", text(file));
	}

	@Test
	void aWriteOutsideTheWorkspaceIsRefusedUnlessTheUserAllowsIt() throws Exception {
		Path file = outside.resolve("sub").resolve("out.txt");

		Throwable failure = failure(fs.writeTextFile(write(file, "x")));

		assertEquals("Write to " + file + " denied by user (outside the workspace)", failure.getMessage());
		assertEquals(List.of(file), asked);
		assertFalse(Files.exists(file));
		assertFalse(Files.exists(file.getParent()));
		assertTrue(written.isEmpty());

		confirmation = CompletableFuture.completedFuture(Boolean.TRUE);
		get(fs.writeTextFile(write(file, "allowed")));

		assertEquals("allowed", text(file));
		assertEquals(List.of(new FsService.FsWriteEvent(file.toString(), "allowed")), written);
	}

	@Test
	void theWriteWaitsForTheUsersAnswerWithoutHoldingUpOtherRequests() throws Exception {
		Path inside = root.resolve("a.txt");
		Files.write(inside, new byte[] { 'a' });
		Path file = outside.resolve("out.txt");
		CompletableFuture<Boolean> answer = new CompletableFuture<>();
		confirmation = answer;

		CompletableFuture<JsonNode> pending = fs.writeTextFile(write(file, "x"));

		assertEquals("a", get(fs.readTextFile(read(inside))).path("content").asText());
		assertFalse(pending.isDone());
		assertFalse(Files.exists(file));

		answer.complete(Boolean.TRUE);

		get(pending);
		assertEquals("x", text(file));
	}

	@Test
	void anAnswerThatFailsOrIsMissingRefusesTheWrite() throws Exception {
		Path file = outside.resolve("out.txt");

		confirmation = CompletableFuture.failedFuture(new IllegalStateException("no window"));
		assertEquals("Write to " + file + " denied by user (outside the workspace)",
				failure(fs.writeTextFile(write(file, "x"))).getMessage());
		confirmation = CompletableFuture.completedFuture(null);
		assertInstanceOf(IOException.class, failure(fs.writeTextFile(write(file, "x"))));
		confirmation = null;
		assertInstanceOf(IOException.class, failure(fs.writeTextFile(write(file, "x"))));

		assertFalse(Files.exists(file));
		assertTrue(logger.has("no window"), logger.lines.toString());
	}

	@Test
	void aPathLeavingTheWorkspaceThroughDotDotIsOutside() throws Exception {
		Path file = root.resolve("..").resolve("elsewhere").resolve("escape.txt");

		failure(fs.writeTextFile(write(file, "x")));

		assertEquals(List.of(file), asked);
		assertFalse(Files.exists(outside.resolve("escape.txt")));

		Path within = root.resolve("sub").resolve("..").resolve("stay.txt");
		get(fs.writeTextFile(write(within, "y")));
		assertEquals("y", text(root.resolve("stay.txt")));
		assertEquals(1, asked.size());
	}

	@Test
	void aWriteWithoutContentIsRefused() throws Exception {
		Path file = root.resolve("a.txt");
		assertEquals("content must be a string", failure(fs.writeTextFile(read(file))).getMessage());
		assertFalse(Files.exists(file));
	}

	@Test
	void theInterceptorDecidesWhatIsWritten() throws Exception {
		Path file = root.resolve("edited.txt");
		fs.setWriteInterceptor((path, content) -> path.equals(file.toString()) ? content + " (edited by the user)" : content);

		get(fs.writeTextFile(write(file, "proposal")));

		assertEquals("proposal (edited by the user)", text(file));
		assertEquals(List.of(new FsService.FsWriteEvent(file.toString(), "proposal (edited by the user)")), written);

		fs.setWriteInterceptor(null);
		get(fs.writeTextFile(write(file, "plain")));
		assertEquals("plain", text(file));
	}

	@Test
	void requestsTakeEffectInTheOrderTheyArrived() throws Exception {
		Path file = root.resolve("counter.txt");
		CompletableFuture<JsonNode> last = null;
		CompletableFuture<JsonNode> readBack = null;
		for (int i = 0; i < 50; i++) {
			last = fs.writeTextFile(write(file, "value " + i));
			readBack = fs.readTextFile(read(file));
		}
		get(last);
		assertEquals("value 49", get(readBack).path("content").asText());
		assertEquals(50, written.size());
		assertEquals("value 49", written.get(49).content());
	}

	@Test
	void theWorkHappensOffTheCallersThreadAndTheCallReturnsAtOnce() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		List<Thread> threads = new CopyOnWriteArrayList<>();
		fs.close();
		fs = service(new DiskTextFiles() {
			@Override
			public String read(Path file) throws IOException {
				threads.add(Thread.currentThread());
				entered.countDown();
				try {
					release.await(Fakes.SECONDS, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					throw new IOException(e);
				}
				return "slow";
			}
		});

		CompletableFuture<JsonNode> pending = fs.readTextFile(read(root.resolve("any.txt")));

		assertTrue(entered.await(Fakes.SECONDS, TimeUnit.SECONDS));
		assertFalse(pending.isDone());
		release.countDown();
		assertEquals("slow", get(pending).path("content").asText());
		assertNotEquals(Thread.currentThread(), threads.get(0));
		assertTrue(threads.get(0).isDaemon());
	}

	/** Reference F6.1: the read tool sees unsaved edits, and reading does not save them. */
	@Test
	void anOpenEditorsTextIsPreferredToTheFile() throws Exception {
		Path file = root.resolve("README.md");
		Files.write(file, "saved\n".getBytes(StandardCharsets.UTF_8));
		fs.close();
		fs = service(new DiskTextFiles() {
			@Override
			public String read(Path asked) throws IOException {
				return asked.equals(file) ? "unsaved line\nsaved\n" : super.read(asked);
			}
		});

		assertEquals("unsaved line\n", get(fs.readTextFile(read(file).put("limit", 1))).path("content").asText());
		assertEquals("saved\n", text(file));
	}

	@Test
	void aFailingListenerDoesNotFailTheWrite() throws Exception {
		Path file = root.resolve("a.txt");
		fs.onDidWrite(event -> {
			throw new IllegalStateException("listener");
		});
		fs.onDidWrite(written::add).close();

		get(fs.writeTextFile(write(file, "x")));

		assertEquals("x", text(file));
		assertEquals(1, written.size());
		assertTrue(logger.has("A file write listener failed"));
	}

	@Test
	void aWriteThatCannotBeDoneFails() throws Exception {
		Path directory = Files.createDirectory(root.resolve("taken"));
		assertInstanceOf(IOException.class, failure(fs.writeTextFile(write(directory, "x"))));
		assertTrue(written.isEmpty());
	}

	@Test
	void afterCloseRequestsFail() throws Exception {
		fs.close();
		assertEquals("The file service is closed", failure(fs.readTextFile(read(root.resolve("a.txt")))).getMessage());
		assertEquals("The file service is closed", failure(fs.writeTextFile(write(root.resolve("a.txt"), "x"))).getMessage());
	}

	@Test
	void theDiskFilesRefuseOutsideWritesWhenThereIsNobodyToAsk() throws Exception {
		assertEquals(Boolean.FALSE, new DiskTextFiles().confirmOutsideWorkspace(outside).toCompletableFuture().get());
		assertEquals("Kiro wants to write a file outside the workspace:\n" + outside,
				FsService.outsideWorkspaceQuestion(outside));
	}
}
