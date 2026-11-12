package io.github.liaoalia.kiroacp.core.services;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/**
 * {@link TextFiles} over the file system alone, for hosts without editors and
 * as the fallback of one with editors for files that are not open.
 */
public class DiskTextFiles implements TextFiles {

	private final Function<Path, CompletionStage<Boolean>> confirm;

	/** Refuses every write outside the workspace: there is nobody to ask. */
	public DiskTextFiles() {
		this(file -> CompletableFuture.completedFuture(Boolean.FALSE));
	}

	/** @param confirm answers {@link #confirmOutsideWorkspace} */
	public DiskTextFiles(Function<Path, CompletionStage<Boolean>> confirm) {
		this.confirm = confirm;
	}

	@Override
	public String read(Path file) throws IOException {
		// Malformed bytes become U+FFFD and a byte order mark is dropped, as the
		// reference's TextDecoder does.
		String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
		return text.startsWith("﻿") ? text.substring(1) : text;
	}

	@Override
	public void write(Path file, String content) throws IOException {
		if (!Files.isRegularFile(file)) {
			Path parent = file.toAbsolutePath().getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
		}
		Files.write(file, content.getBytes(StandardCharsets.UTF_8));
	}

	@Override
	public CompletionStage<Boolean> confirmOutsideWorkspace(Path file) {
		return confirm.apply(file);
	}
}
