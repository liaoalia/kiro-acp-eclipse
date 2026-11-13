package io.github.liaoalia.kiroacp.core.session.context;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Lets the user choose image files: the composer's attach button (the reference's {@code showOpenDialog}). */
@FunctionalInterface
public interface ImagePicker {

	/**
	 * Called on the controller thread; must return at once.
	 *
	 * @param extensions the file types to offer, without the dot ({@code png}, {@code jpg}, …)
	 * @return completes, on any thread, with the files chosen; empty or {@code null} when the user chose none
	 */
	CompletionStage<List<Path>> pickImages(List<String> extensions);

	/** No dialog to show: nothing is ever chosen. */
	ImagePicker NONE = extensions -> CompletableFuture.completedFuture(List.of());
}
