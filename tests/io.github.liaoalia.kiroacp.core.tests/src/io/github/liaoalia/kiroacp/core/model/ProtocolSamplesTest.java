package io.github.liaoalia.kiroacp.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.liaoalia.kiroacp.core.acp.FakeAgent;

/**
 * The Java models against {@code tests/fixtures/protocol-samples.json}, which
 * {@code webview/samples.ts} emits and the TypeScript compiler checks against
 * the reference's protocol.ts. Every sample must come back from the models as
 * the same JSON, and every variant must have a model.
 */
class ProtocolSamplesTest {

	private static JsonNode samples;

	@BeforeAll
	static void load() throws IOException {
		Path file = FakeAgent.fixtures().resolve("protocol-samples.json");
		samples = new ObjectMapper().readTree(Files.readString(file));
	}

	@Test
	void everyToWebviewVariantHasAModel() {
		assertEquals(names(samples.path("toWebview")), subtypeNames(ToWebview.class));
	}

	@Test
	void everyFromWebviewVariantHasAModel() {
		assertEquals(names(samples.path("fromWebview")), subtypeNames(FromWebview.class));
	}

	@Test
	void toWebviewSamplesRoundTrip() {
		roundTrip(samples.path("toWebview"), json -> UiJson.toWebview(json).map(UiJson::toJson));
	}

	@Test
	void fromWebviewSamplesRoundTrip() {
		roundTrip(samples.path("fromWebview"), json -> UiJson.fromWebview(json).map(UiJson::toJson));
	}

	@Test
	void aMessageOfAnUnknownTypeIsNotUnderstood() {
		ObjectNode message = new ObjectMapper().createObjectNode().put("type", "somethingNewer");
		assertEquals(Optional.empty(), UiJson.fromWebview(message));
		assertEquals(Optional.empty(), UiJson.fromWebview(null));
		assertEquals(Optional.empty(), UiJson.fromWebview(new ObjectMapper().createArrayNode()));
	}

	@Test
	void fieldsANewerPageAddsAreIgnored() {
		ObjectNode message = new ObjectMapper().createObjectNode().put("type", "setMode").put("modeId", "plan").put("since", 2);
		assertEquals(Optional.of(new FromWebview.SetMode("plan")), UiJson.fromWebview(message));
	}

	private static void roundTrip(JsonNode byType, Function<JsonNode, Optional<JsonNode>> throughModel) {
		List<String> problems = new ArrayList<>();
		int count = 0;
		for (Map.Entry<String, JsonNode> entry : byType.properties()) {
			for (JsonNode sample : entry.getValue()) {
				count++;
				Optional<JsonNode> back = throughModel.apply(sample);
				if (back.isEmpty()) {
					problems.add(entry.getKey() + ": not understood: " + sample);
				} else {
					diff(entry.getKey(), sample, back.get(), problems);
				}
			}
		}
		assertTrue(count > 0, "no samples");
		if (!problems.isEmpty()) {
			fail(problems.size() + " difference(s):\n" + String.join("\n", problems));
		}
	}

	/** Structural comparison; numbers compare by value, so 40 and 40.0 are the same. */
	private static void diff(String path, JsonNode expected, JsonNode actual, List<String> problems) {
		if (expected.isObject() && actual.isObject()) {
			Set<String> fields = new TreeSet<>();
			expected.fieldNames().forEachRemaining(fields::add);
			actual.fieldNames().forEachRemaining(fields::add);
			for (String field : fields) {
				if (!expected.has(field)) {
					problems.add(path + "." + field + ": added by the model: " + actual.get(field));
				} else if (!actual.has(field)) {
					problems.add(path + "." + field + ": lost by the model, was " + expected.get(field));
				} else {
					diff(path + "." + field, expected.get(field), actual.get(field), problems);
				}
			}
		} else if (expected.isArray() && actual.isArray()) {
			if (expected.size() != actual.size()) {
				problems.add(path + ": " + expected.size() + " elements became " + actual.size());
				return;
			}
			for (int i = 0; i < expected.size(); i++) {
				diff(path + "[" + i + "]", expected.get(i), actual.get(i), problems);
			}
		} else if (expected.isNumber() && actual.isNumber()) {
			if (expected.decimalValue().compareTo(actual.decimalValue()) != 0) {
				problems.add(path + ": " + expected + " became " + actual);
			}
		} else if (!expected.equals(actual)) {
			problems.add(path + ": " + expected + " became " + actual);
		}
	}

	private static Set<String> names(JsonNode byType) {
		Set<String> names = new TreeSet<>();
		for (Iterator<String> it = byType.fieldNames(); it.hasNext();) {
			names.add(it.next());
		}
		assertFalse(names.isEmpty());
		return names;
	}

	private static Set<String> subtypeNames(Class<?> union) {
		Set<String> names = new TreeSet<>();
		for (JsonSubTypes.Type type : union.getAnnotation(JsonSubTypes.class).value()) {
			names.add(type.name());
		}
		return names;
	}
}
