package io.github.liaoalia.kiroacp.core.pure.usage;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.liaoalia.kiroacp.core.model.SessionUsage;
import io.github.liaoalia.kiroacp.core.pure.text.JsText;

/**
 * Context-window and token usage of a session, assembled from whichever
 * updates the agent sends (the reference's {@code features/pure/usage.ts},
 * its decision D26):
 *
 * <ul>
 * <li>ACP {@code usage_update} ({@code used} / {@code size} tokens in
 * context, optional {@code cost});
 * <li>Kiro V3 {@code session_info_update} with
 * {@code _meta.kiro.kind = "context_usage"} ({@code usagePercentage});
 * <li>Kiro V3 {@code session_info_update} with
 * {@code _meta.kiro.kind = "turn_completion"} ({@code promptTurnSummaries},
 * the turn's metering; token fields are summed);
 * <li>the reply of {@code _kiro/session/context} (polled after a turn when
 * the agent never reported a percentage itself), read defensively: numbers
 * under the usual keys at any depth, else "NN%" / "A of B tokens" in its
 * text.
 * </ul>
 *
 * <p>Only {@code usagePercentage} is documented by Kiro; the other Kiro
 * fields are read defensively (any numeric {@code ...tokens} field).
 */
public final class Usage {

	/** Context numbers found in a reply; each is {@code null} when unknown. */
	public record ContextNumbers(Double contextPercent, Long contextUsed, Long contextSize) {
	}

	private static final Pattern TOTAL_KEY = Pattern.compile("^total.?tokens$", Pattern.CASE_INSENSITIVE);
	private static final Pattern TOKEN_KEY = Pattern.compile("tokens?$", Pattern.CASE_INSENSITIVE);

	private static final List<String> PERCENT_KEYS = List.of("usagePercentage", "usagePercent", "contextUsagePercent", "percentage",
			"percent");
	private static final List<String> USED_KEYS = List.of("usedTokens", "tokensUsed", "contextTokens", "contextUsed", "used",
			"totalTokens", "tokens");
	private static final List<String> SIZE_KEYS = List.of("contextWindow", "contextWindowSize", "contextWindowTokens", "maxTokens",
			"contextSize", "size", "limit");

	private static final String WS = JsText.WHITESPACE_CLASS;
	private static final Pattern PERCENT_TEXT = Pattern.compile("(\\d{1,3}(?:\\.\\d+)?)" + WS + "*%");
	private static final Pattern TOKENS_TEXT = Pattern
			.compile("([\\d.,]+" + WS + "*[kKmM]?)" + WS + "*(?:of|/)" + WS + "*([\\d.,]+" + WS + "*[kKmM]?)" + WS + "*tokens");
	private static final Pattern COUNT = Pattern.compile("^([\\d.,]+)" + WS + "*([kKmM])?$");

	private Usage() {
	}

	private static boolean isRecord(JsonNode value) {
		return value != null && value.isObject();
	}

	/** A finite number, or {@code null}. */
	private static Double number(JsonNode value) {
		if (value == null || !value.isNumber()) {
			return null;
		}
		double n = value.doubleValue();
		return Double.isFinite(n) ? n : null;
	}

	/** First finite number found under any of {@code keys}, in {@code objects} order. */
	private static Double pick(List<JsonNode> objects, List<String> keys) {
		for (JsonNode object : objects) {
			if (!isRecord(object)) {
				continue;
			}
			for (String key : keys) {
				Double n = number(object.get(key));
				if (n != null) {
					return n;
				}
			}
		}
		return null;
	}

	/** {@code _meta.kiro} of an update when it is an object, else {@code null}. */
	public static JsonNode kiroMeta(JsonNode update) {
		JsonNode meta = update.get("_meta");
		if (!isRecord(meta)) {
			return null;
		}
		return isRecord(meta.get("kiro")) ? meta.get("kiro") : null;
	}

	private static double clampPercent(double value) {
		return Math.min(100, Math.max(0, value));
	}

	private static double percentOf(double used, double size) {
		return clampPercent(Math.round(used / size * 100));
	}

	/**
	 * Tokens reported by one metering object: its {@code totalTokens} when
	 * present, else the sum of every numeric {@code ...tokens} field, nested
	 * objects included ({@code { usage: { inputTokens, outputTokens } }}),
	 * three levels deep.
	 */
	public static double tokenTotal(JsonNode entry) {
		return tokenTotal(entry, 0);
	}

	private static double tokenTotal(JsonNode entry, int depth) {
		if (!isRecord(entry) || depth > 3) {
			return 0;
		}
		for (Iterator<Map.Entry<String, JsonNode>> fields = entry.fields(); fields.hasNext();) {
			Map.Entry<String, JsonNode> field = fields.next();
			Double n = number(field.getValue());
			if (n != null && TOTAL_KEY.matcher(field.getKey()).find()) {
				return n;
			}
		}
		double sum = 0;
		for (Iterator<Map.Entry<String, JsonNode>> fields = entry.fields(); fields.hasNext();) {
			Map.Entry<String, JsonNode> field = fields.next();
			Double n = number(field.getValue());
			if (n != null) {
				if (TOKEN_KEY.matcher(field.getKey()).find()) {
					sum += n;
				}
			} else if (isRecord(field.getValue())) {
				sum += tokenTotal(field.getValue(), depth + 1);
			}
		}
		return sum;
	}

	private static Long whole(Double value) {
		return value == null ? null : Long.valueOf(Math.round(value));
	}

	/**
	 * Fold one {@code session/update} payload into {@code usage}.
	 *
	 * @return the new usage, or {@code null} when the update carries nothing about usage (or nothing new)
	 */
	public static SessionUsage applyUsageUpdate(SessionUsage usage, JsonNode update) {
		if (!isRecord(update) || !update.path("sessionUpdate").isTextual()) {
			return null;
		}
		String kind = update.get("sessionUpdate").textValue();
		Double percent = usage.contextPercent();
		Long contextUsed = usage.contextUsed();
		Long contextSize = usage.contextSize();
		Long tokensSpent = usage.tokensSpent();
		SessionUsage.Cost cost = usage.cost();

		if ("usage_update".equals(kind)) {
			Double used = number(update.get("used"));
			Double size = number(update.get("size"));
			if (used != null) {
				contextUsed = whole(used);
			}
			if (size != null && size > 0) {
				contextSize = whole(size);
			}
			if (used != null && size != null && size > 0) {
				percent = percentOf(used, size);
			}
			JsonNode reported = update.get("cost");
			if (isRecord(reported)) {
				Double amount = number(reported.get("amount"));
				if (amount != null) {
					cost = new SessionUsage.Cost(amount, reported.path("currency").isTextual() ? reported.get("currency").textValue() : "");
				}
			}
		} else if ("session_info_update".equals(kind)) {
			JsonNode kiro = kiroMeta(update);
			String kiroKind = kiro != null && kiro.path("kind").isTextual() ? kiro.get("kind").textValue() : null;
			if ("context_usage".equals(kiroKind)) {
				List<JsonNode> sources = List.of(kiro, update);
				Double reported = pick(sources, List.of("usagePercentage", "usagePercent", "percentage", "percent"));
				Double used = pick(sources, List.of("usedTokens", "tokensUsed", "used", "contextTokens", "tokens"));
				Double size = pick(sources, List.of("contextWindow", "contextWindowSize", "maxTokens", "size", "limit"));
				if (used != null) {
					contextUsed = whole(used);
				}
				if (size != null && size > 0) {
					contextSize = whole(size);
				}
				if (reported != null) {
					percent = clampPercent(Math.round(reported));
				} else if (used != null && size != null && size > 0) {
					percent = percentOf(used, size);
				}
			} else if ("turn_completion".equals(kiroKind)) {
				JsonNode summaries = kiro.has("promptTurnSummaries") && !kiro.get("promptTurnSummaries").isNull()
						? kiro.get("promptTurnSummaries")
						: update.get("promptTurnSummaries");
				List<JsonNode> entries = new ArrayList<>();
				if (summaries != null && summaries.isArray()) {
					summaries.forEach(entries::add);
				} else if (isRecord(summaries)) {
					entries.add(summaries);
				}
				double turnTokens = 0;
				for (JsonNode entry : entries) {
					turnTokens += tokenTotal(entry);
				}
				if (turnTokens > 0) {
					tokensSpent = (tokensSpent != null ? tokensSpent : 0) + Math.round(turnTokens);
				}
			}
		}

		SessionUsage next = new SessionUsage(percent, contextUsed, contextSize, tokensSpent, cost);
		return next.equals(usage) ? null : next;
	}

	/** Breadth-first: the first finite number stored under {@code key}, four levels deep. */
	private static Double deepFind(JsonNode root, String key) {
		List<JsonNode> level = new ArrayList<>();
		level.add(root);
		for (int depth = 0; depth <= 4 && !level.isEmpty(); depth++) {
			List<JsonNode> next = new ArrayList<>();
			for (JsonNode node : level) {
				if (!isRecord(node)) {
					continue;
				}
				Double n = number(node.get(key));
				if (n != null) {
					return n;
				}
				for (JsonNode value : node) {
					if (isRecord(value)) {
						next.add(value);
					} else if (value.isArray()) {
						value.forEach(next::add);
					}
				}
			}
			level = next;
		}
		return null;
	}

	/** The first of {@code keys} (in priority order) found anywhere in {@code root}: a specific key beats a generic one. */
	private static Double deepPick(JsonNode root, List<String> keys) {
		for (String key : keys) {
			Double n = deepFind(root, key);
			if (n != null) {
				return n;
			}
		}
		return null;
	}

	/** Every string four levels deep, joined, for the textual fallback. */
	private static String deepText(JsonNode root, int depth) {
		if (root == null) {
			return "";
		}
		if (root.isTextual()) {
			return root.textValue();
		}
		if (depth > 4 || !(root.isArray() || root.isObject())) {
			return "";
		}
		List<String> parts = new ArrayList<>();
		for (JsonNode value : root) {
			parts.add(deepText(value, depth + 1));
		}
		return String.join("\n", parts);
	}

	/** "12k", "12,345", "1.2M" as a token count. */
	private static Double parseCount(String text) {
		Matcher m = COUNT.matcher(JsText.trim(text));
		if (!m.matches()) {
			return null;
		}
		double base;
		try {
			base = Double.parseDouble(m.group(1).replace(",", ""));
		} catch (NumberFormatException e) {
			return null;
		}
		if (!Double.isFinite(base)) {
			return null;
		}
		String unit = m.group(2) == null ? "" : m.group(2).toLowerCase(java.util.Locale.ROOT);
		return (double) Math.round(unit.equals("k") ? base * 1_000 : unit.equals("m") ? base * 1_000_000 : base);
	}

	/**
	 * Context numbers found in a {@code _kiro/session/context} reply (or any
	 * similar object): structured keys first, then the text ("42% used",
	 * "84k of 200k tokens").
	 *
	 * @return {@code null} when it holds nothing usable
	 */
	public static ContextNumbers contextFromResult(JsonNode result) {
		Double percent = deepPick(result, PERCENT_KEYS);
		Double used = deepPick(result, USED_KEYS);
		Double size = deepPick(result, SIZE_KEYS);
		if (percent == null && (used == null || size == null)) {
			String text = deepText(result, 0);
			Matcher pm = PERCENT_TEXT.matcher(text);
			if (pm.find()) {
				percent = Double.valueOf(pm.group(1));
			}
			Matcher tm = TOKENS_TEXT.matcher(text);
			if (tm.find()) {
				used = used != null ? used : parseCount(tm.group(1));
				size = size != null ? size : parseCount(tm.group(2));
			}
		}
		if (size != null && size <= 0) {
			size = null;
		}
		if (percent == null && used != null && size != null) {
			percent = used / size * 100;
		}
		if (percent == null && used == null && size == null) {
			return null;
		}
		return new ContextNumbers(percent == null ? null : Double.valueOf(clampPercent(Math.round(percent))), whole(used), whole(size));
	}

	/** Fold a {@code _kiro/session/context} reply into {@code usage}; {@code null} when nothing changed. */
	public static SessionUsage applyContextResult(SessionUsage usage, JsonNode result) {
		ContextNumbers found = contextFromResult(result);
		if (found == null) {
			return null;
		}
		SessionUsage next = new SessionUsage(found.contextPercent() != null ? found.contextPercent() : usage.contextPercent(),
				found.contextUsed() != null ? found.contextUsed() : usage.contextUsed(),
				found.contextSize() != null ? found.contextSize() : usage.contextSize(), usage.tokensSpent(), usage.cost());
		return next.equals(usage) ? null : next;
	}
}
