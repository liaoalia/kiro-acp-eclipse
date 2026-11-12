package io.github.liaoalia.kiroacp.core.pure.output;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/** One ACP {@code EnvVariable} of a {@code terminal/create} request. */
public record EnvVariable(String name, String value) {

	/**
	 * The variables of a request's {@code env} array. Entries without a textual
	 * name and value are skipped: they could not be set anyway.
	 *
	 * @param env the array; {@code null}, missing or not an array yields an empty list
	 */
	public static List<EnvVariable> listOf(JsonNode env) {
		List<EnvVariable> out = new ArrayList<>();
		if (env == null || !env.isArray()) {
			return out;
		}
		for (JsonNode entry : env) {
			JsonNode name = entry.get("name");
			JsonNode value = entry.get("value");
			if (name != null && name.isTextual() && value != null && value.isTextual()) {
				out.add(new EnvVariable(name.textValue(), value.textValue()));
			}
		}
		return out;
	}
}
