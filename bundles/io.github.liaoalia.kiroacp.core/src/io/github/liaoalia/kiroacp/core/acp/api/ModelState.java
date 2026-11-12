package io.github.liaoalia.kiroacp.core.acp.api;

import java.util.List;

/** Unstable ACP v1 model state ({@code models} of a session), used by CLI v2 with {@code session/set_model}. */
public record ModelState(String currentModelId, List<Model> availableModels) {

	public ModelState {
		availableModels = List.copyOf(availableModels);
	}

	public record Model(String modelId, String name, String description) {
	}
}
