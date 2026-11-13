package io.github.liaoalia.kiroacp.core.pure.diff;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * Bookkeeping for proposed-change diffs (reference D7,
 * {@code features/pure/diffState.ts}).
 *
 * <p>Tracks which diffs are open per tool call and the substitutions recorded
 * when the user accepted an edited proposal. Not thread-safe.
 */
public final class DiffState {

	/**
	 * A proposed change of one file.
	 *
	 * @param path    absolute path of the file the agent wants to change
	 * @param oldText original content; {@code null} for new files
	 * @param newText content proposed by the agent
	 */
	public record DiffSpec(String path, String oldText, String newText) {
	}

	/**
	 * The user accepted something else than the agent proposed.
	 *
	 * @param path      normalised path (see the {@code normalize} constructor argument)
	 * @param agentText what the agent proposed (and will presumably write)
	 * @param userText  what the user turned it into in the proposed editor
	 */
	public record Substitution(String path, String agentText, String userText) {
	}

	/** A diff and its index within the tool call's content. */
	public record TrackedDiff(int index, DiffSpec diff) {
	}

	/** A substitution and the tool call it was recorded for. */
	public record Pending(String toolCallId, Substitution sub) {
	}

	private final UnaryOperator<String> normalize;
	/** Insertion order: the tool calls are listed oldest first. */
	private final Map<String, Map<Integer, DiffSpec>> diffs = new LinkedHashMap<>();
	private final List<Pending> subs = new ArrayList<>();

	/** Paths are compared as they are given. */
	public DiffState() {
		this(path -> path);
	}

	/**
	 * @param normalize path normaliser applied to every path so lookups by {@link #takeSubstitution}
	 *                  match regardless of separators and drive-letter case
	 */
	public DiffState(UnaryOperator<String> normalize) {
		this.normalize = normalize;
	}

	/** Remember a diff (index within the tool call's content) for {@code toolCallId}. */
	public void register(String toolCallId, int index, DiffSpec diff) {
		diffs.computeIfAbsent(toolCallId, key -> new TreeMap<>()).put(index, diff);
	}

	/** Diffs registered for a tool call, ordered by index. */
	public List<TrackedDiff> diffsFor(String toolCallId) {
		Map<Integer, DiffSpec> byIndex = diffs.get(toolCallId);
		if (byIndex == null) {
			return List.of();
		}
		return byIndex.entrySet().stream().map(entry -> new TrackedDiff(entry.getKey(), entry.getValue())).toList();
	}

	/** One diff by index, or {@code null}. */
	public DiffSpec diffAt(String toolCallId, int index) {
		Map<Integer, DiffSpec> byIndex = diffs.get(toolCallId);
		return byIndex != null ? byIndex.get(index) : null;
	}

	public boolean hasDiffs(String toolCallId) {
		Map<Integer, DiffSpec> byIndex = diffs.get(toolCallId);
		return byIndex != null && !byIndex.isEmpty();
	}

	/** Tool call ids with open diffs, oldest first. */
	public List<String> toolCallIds() {
		return List.copyOf(diffs.keySet());
	}

	/** Forget the diffs (not the substitutions) of a tool call. */
	public void remove(String toolCallId) {
		diffs.remove(toolCallId);
	}

	/**
	 * Record that the user accepted {@code userText} instead of
	 * {@code agentText} for {@code path}. Replaces an earlier substitution for
	 * the same tool call and path.
	 */
	public void addSubstitution(String toolCallId, Substitution sub) {
		String path = normalize.apply(sub.path());
		subs.removeIf(s -> s.toolCallId().equals(toolCallId) && s.sub().path().equals(path));
		subs.add(new Pending(toolCallId, new Substitution(path, sub.agentText(), sub.userText())));
	}

	/**
	 * If a substitution exists for {@code path} whose {@code agentText} equals
	 * {@code content}, remove it and return its {@code userText}; otherwise
	 * {@code null}.
	 */
	public String takeSubstitution(String path, String content) {
		return takeSubstitution(path, content, toolCallId -> true);
	}

	/**
	 * {@link #takeSubstitution(String, String)} among the substitutions of the
	 * tool calls {@code toolCall} accepts: whoever knows which tool calls a
	 * write can belong to leaves the others' substitutions alone.
	 */
	public String takeSubstitution(String path, String content, Predicate<String> toolCall) {
		String key = normalize.apply(path);
		for (int i = 0; i < subs.size(); i++) {
			Substitution sub = subs.get(i).sub();
			if (sub.path().equals(key) && sub.agentText().equals(content) && toolCall.test(subs.get(i).toolCallId())) {
				subs.remove(i);
				return sub.userText();
			}
		}
		return null;
	}

	/** Substitutions still pending for a tool call. */
	public List<Substitution> pendingFor(String toolCallId) {
		return subs.stream().filter(s -> s.toolCallId().equals(toolCallId)).map(Pending::sub).toList();
	}

	/** Drop every substitution of a tool call (e.g. on failed/cancelled). */
	public void dropSubstitutions(String toolCallId) {
		subs.removeIf(s -> s.toolCallId().equals(toolCallId));
	}

	/** Drop every substitution of the tool calls {@code toolCall} accepts. */
	public void dropSubstitutionsIf(Predicate<String> toolCall) {
		subs.removeIf(s -> toolCall.test(s.toolCallId()));
	}

	/** All pending substitutions (diagnostics/tests). */
	public List<Pending> substitutions() {
		return List.copyOf(subs);
	}

	/** Forget everything. */
	public void clear() {
		diffs.clear();
		subs.clear();
	}
}
