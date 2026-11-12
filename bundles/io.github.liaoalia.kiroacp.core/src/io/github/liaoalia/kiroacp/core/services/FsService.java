package io.github.liaoalia.kiroacp.core.services;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.MissingNode;

import io.github.liaoalia.kiroacp.core.host.Logger;
import io.github.liaoalia.kiroacp.core.host.Subscription;
import io.github.liaoalia.kiroacp.core.host.Workspace;
import io.github.liaoalia.kiroacp.core.pure.text.TextRange;

/**
 * {@code fs/read_text_file} and {@code fs/write_text_file} (reference D8).
 *
 * <p>Reads prefer open (possibly dirty) editors so the agent sees unsaved
 * edits; writes end on disk so the agent's next command sees the file. Both go
 * through {@link TextFiles}.
 *
 * <p>The request methods may be called from any thread and return at once.
 * The work runs on one worker thread, so requests take effect in the order
 * they arrived: a read after a write sees what was written. Only a write that
 * waits for the user's confirmation lets later requests pass.
 */
public final class FsService implements AutoCloseable {

	/**
	 * A successful {@code writeTextFile}.
	 *
	 * @param path    the absolute path as the agent sent it
	 * @param content the content actually written
	 */
	public record FsWriteEvent(String path, String content) {
	}

	/**
	 * Transforms content before it is written (used by the diff feature to
	 * substitute the user's edited proposal, reference D7). Called on the
	 * service's worker thread.
	 */
	@FunctionalInterface
	public interface WriteInterceptor {

		String intercept(String absPath, String content);
	}

	private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

	private final Workspace workspace;
	private final TextFiles files;
	private final Logger logger;
	private final ExecutorService worker = Workers.single("kiro-fs");
	private final List<Consumer<FsWriteEvent>> writeListeners = new CopyOnWriteArrayList<>();
	private volatile WriteInterceptor writeInterceptor;

	/**
	 * @param workspace whose roots a write needs no confirmation in
	 * @param logger    may be {@code null}
	 */
	public FsService(Workspace workspace, TextFiles files, Logger logger) {
		this.workspace = workspace;
		this.files = files;
		this.logger = logger != null ? logger : Logger.NONE;
	}

	/** The question a write outside the workspace is confirmed with. */
	public static String outsideWorkspaceQuestion(Path file) {
		return "Kiro wants to write a file outside the workspace:\n" + file;
	}

	/** @param interceptor replaces the current one; {@code null} for none */
	public void setWriteInterceptor(WriteInterceptor interceptor) {
		this.writeInterceptor = interceptor;
	}

	/**
	 * Fires after a successful {@code writeTextFile}, on the service's worker
	 * thread and before the request's future completes.
	 *
	 * @return removes the listener
	 */
	public Subscription onDidWrite(Consumer<FsWriteEvent> listener) {
		writeListeners.add(listener);
		return () -> writeListeners.remove(listener);
	}

	/**
	 * ClientServices.readTextFile. The path may be anywhere: only writes are
	 * confined to the workspace.
	 *
	 * @return the ACP {@code ReadTextFileResponse}; fails when the path is not absolute or the
	 *         file cannot be read
	 */
	public CompletableFuture<JsonNode> readTextFile(JsonNode request) {
		JsonNode req = request != null ? request : MissingNode.getInstance();
		String absPath = req.path("path").asText("");
		if (!TextRange.isAbsolutePath(absPath)) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("path must be absolute"));
		}
		Number line = req.path("line").isNumber() ? req.get("line").numberValue() : null;
		Number limit = req.path("limit").isNumber() ? req.get("limit").numberValue() : null;
		CompletableFuture<JsonNode> result = new CompletableFuture<>();
		submit(result, () -> {
			String text = files.read(Path.of(absPath));
			result.complete(JSON.objectNode().put("content", TextRange.sliceLines(text, line, limit)));
		});
		return result;
	}

	/**
	 * ClientServices.writeTextFile. A path outside every workspace root is
	 * written only after {@link TextFiles#confirmOutsideWorkspace} allowed it.
	 *
	 * @return the ACP {@code WriteTextFileResponse}; fails when the path is not absolute, the user
	 *         refused or the file cannot be written
	 */
	public CompletableFuture<JsonNode> writeTextFile(JsonNode request) {
		JsonNode req = request != null ? request : MissingNode.getInstance();
		String absPath = req.path("path").asText("");
		if (!TextRange.isAbsolutePath(absPath)) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("path must be absolute"));
		}
		if (!req.path("content").isTextual()) {
			return CompletableFuture.failedFuture(new IllegalArgumentException("content must be a string"));
		}
		String requested = req.get("content").textValue();
		CompletableFuture<JsonNode> result = new CompletableFuture<>();
		submit(result, () -> {
			Path file = Path.of(absPath);
			if (isInsideWorkspace(absPath)) {
				write(file, absPath, requested, result);
				return;
			}
			CompletionStage<Boolean> answer = files.confirmOutsideWorkspace(file);
			if (answer == null) {
				throw denied(absPath);
			}
			answer.whenComplete((allowed, failure) -> {
				if (failure != null || !Boolean.TRUE.equals(allowed)) {
					if (failure != null) {
						logger.warn("Asking about a write outside the workspace failed: " + failure);
					}
					result.completeExceptionally(denied(absPath));
				} else {
					// Back on the worker: the answer arrives on the UI's thread.
					submit(result, () -> write(file, absPath, requested, result));
				}
			});
		});
		return result;
	}

	/** Requests already accepted are still served; later ones fail. */
	@Override
	public void close() {
		worker.shutdown();
	}

	private void write(Path file, String absPath, String requested, CompletableFuture<JsonNode> result)
			throws IOException {
		WriteInterceptor interceptor = writeInterceptor;
		String content = interceptor != null ? interceptor.intercept(absPath, requested) : requested;
		files.write(file, content);
		FsWriteEvent event = new FsWriteEvent(absPath, content);
		for (Consumer<FsWriteEvent> listener : writeListeners) {
			try {
				listener.accept(event);
			} catch (RuntimeException e) {
				logger.error("A file write listener failed", e);
			}
		}
		result.complete(JSON.objectNode());
	}

	private boolean isInsideWorkspace(String absPath) {
		for (Path root : workspace.roots()) {
			if (TextRange.isWithinRoot(absPath, root.toString())) {
				return true;
			}
		}
		return false;
	}

	private static IOException denied(String absPath) {
		return new IOException("Write to " + absPath + " denied by user (outside the workspace)");
	}

	private interface Work {
		void run() throws Exception;
	}

	private void submit(CompletableFuture<JsonNode> result, Work work) {
		try {
			worker.execute(() -> {
				try {
					work.run();
				} catch (Throwable e) {
					// Whatever it is, the agent must get an answer.
					result.completeExceptionally(e);
				}
			});
		} catch (RejectedExecutionException e) {
			result.completeExceptionally(new IllegalStateException("The file service is closed"));
		}
	}
}
