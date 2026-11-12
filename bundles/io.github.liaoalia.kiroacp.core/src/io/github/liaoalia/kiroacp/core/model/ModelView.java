package io.github.liaoalia.kiroacp.core.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

public record ModelView(String currentModelId, List<Model> availableModels) {

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Model(String modelId, String name, String description) {
	}
}
