package io.github.liaoalia.kiroacp.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CommandView(String name, String description, String hint) {
}
