package io.github.liaoalia.kiroacp.core.pure.plan;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import io.github.liaoalia.kiroacp.core.model.PlanEntryView;
import io.github.liaoalia.kiroacp.core.pure.text.JsText;

/**
 * The agent's plan as Markdown, for the plan document (the reference's
 * {@code features/pure/planMarkdown.ts}, its decision D11).
 */
public final class PlanMarkdown {

	public static final String TITLE = "# Kiro plan";
	public static final String EMPTY_TEXT = "No plan yet. Ask Kiro to plan a task and the steps will appear here as the agent reports them.";
	/** The reference points at its editor title here; in Eclipse the buttons are in the plan editor itself. */
	public static final String APPROVAL_NOTE = "> The agent is waiting for approval — use Approve/Reject at the top of this editor.";

	private PlanMarkdown() {
	}

	private static String pad2(int n) {
		return n < 10 ? "0" + n : String.valueOf(n);
	}

	private static String formatTime(LocalTime time) {
		return pad2(time.getHour()) + ":" + pad2(time.getMinute()) + ":" + pad2(time.getSecond());
	}

	/** Status summary such as {@code 3 of 7 done · 1 in progress}. */
	public static String statusLine(List<PlanEntryView> entries) {
		int total = entries.size();
		long done = entries.stream().filter(entry -> entry.status() == PlanEntryView.Status.COMPLETED).count();
		long inProgress = entries.stream().filter(entry -> entry.status() == PlanEntryView.Status.IN_PROGRESS).count();
		String line = done + " of " + total + " done";
		if (inProgress > 0) {
			line += " · " + inProgress + " in progress";
		}
		return line;
	}

	/** One task-list line for an entry. */
	public static String renderEntry(PlanEntryView entry) {
		String box = entry.status() == PlanEntryView.Status.COMPLETED ? "[x]" : "[ ]";
		String marker = entry.status() == PlanEntryView.Status.IN_PROGRESS ? "⏳ " : "";
		String badge = entry.priority() == PlanEntryView.Priority.HIGH ? "**(high)** "
				: entry.priority() == PlanEntryView.Priority.LOW ? "(low) " : "";
		String content = JsText.trim((entry.content() == null ? "" : entry.content()).replaceAll("\r?\n", " "));
		return "- " + box + " " + marker + badge + content;
	}

	/**
	 * Full document text. Layout:
	 *
	 * <pre>
	 * # Kiro plan
	 * _Session abc · updated 12:34:56_
	 *
	 * &gt; approval note (when pending)
	 *
	 * **3 of 7 done · 1 in progress**
	 *
	 * - [x] **(high)** done task
	 * - [ ] ⏳ in-progress task
	 * - [ ] (low) pending task
	 * </pre>
	 *
	 * @param sessionId       named in the second line; {@code null} or empty to leave it out
	 * @param updatedAt       local time of the last change; {@code null} to leave it out
	 * @param approvalPending a {@code switch_mode} permission request is waiting for the user
	 */
	public static String render(List<PlanEntryView> entries, String sessionId, LocalTime updatedAt, boolean approvalPending) {
		List<String> lines = new ArrayList<>();
		lines.add(TITLE);
		List<String> meta = new ArrayList<>();
		if (sessionId != null && !sessionId.isEmpty()) {
			meta.add("Session " + sessionId);
		}
		if (updatedAt != null) {
			meta.add("updated " + formatTime(updatedAt));
		}
		if (!meta.isEmpty()) {
			lines.add("_" + String.join(" · ", meta) + "_");
		}
		lines.add("");
		if (approvalPending) {
			lines.add(APPROVAL_NOTE);
			lines.add("");
		}
		if (entries.isEmpty()) {
			lines.add(EMPTY_TEXT);
			lines.add("");
			return String.join("\n", lines);
		}
		lines.add("**" + statusLine(entries) + "**");
		lines.add("");
		for (PlanEntryView entry : entries) {
			lines.add(renderEntry(entry));
		}
		lines.add("");
		return String.join("\n", lines);
	}
}
