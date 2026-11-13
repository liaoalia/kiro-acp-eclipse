package io.github.liaoalia.kiroacp.core.pure.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.liaoalia.kiroacp.core.model.PlanEntryView;
import io.github.liaoalia.kiroacp.core.model.PlanEntryView.Priority;
import io.github.liaoalia.kiroacp.core.model.PlanEntryView.Status;

/** The cases of the reference's {@code test/features-planMarkdown.test.ts}. */
class PlanMarkdownTest {

	private static final List<PlanEntryView> ENTRIES = List.of(new PlanEntryView("Read the code", Priority.HIGH, Status.COMPLETED),
			new PlanEntryView("Write tests", Priority.MEDIUM, Status.IN_PROGRESS),
			new PlanEntryView("Polish\ndocs", Priority.LOW, Status.PENDING));

	@Test
	void emptyPlanRendersTitleAndPlaceholder() {
		String md = PlanMarkdown.render(List.of(), null, null, false);
		assertTrue(md.startsWith("# Kiro plan\n"));
		assertTrue(md.contains(PlanMarkdown.EMPTY_TEXT));
		assertFalse(md.contains("- ["));
		assertFalse(md.contains(PlanMarkdown.APPROVAL_NOTE));
		assertEquals("# Kiro plan\n\n" + PlanMarkdown.EMPTY_TEXT + "\n", md);
	}

	@Test
	void statusLineCountsDoneAndInProgress() {
		assertEquals("1 of 3 done · 1 in progress", PlanMarkdown.statusLine(ENTRIES));
		assertEquals("1 of 1 done", PlanMarkdown.statusLine(List.of(ENTRIES.get(0))));
	}

	@Test
	void entriesRenderAsATaskListWithMarkersAndBadges() {
		assertEquals("- [x] **(high)** Read the code", PlanMarkdown.renderEntry(ENTRIES.get(0)));
		assertEquals("- [ ] ⏳ Write tests", PlanMarkdown.renderEntry(ENTRIES.get(1)));
		assertEquals("- [ ] (low) Polish docs", PlanMarkdown.renderEntry(ENTRIES.get(2)));
		assertEquals("- [ ] one two", PlanMarkdown.renderEntry(new PlanEntryView("  one\r\ntwo\n", Priority.MEDIUM, Status.PENDING)));
	}

	@Test
	void fullRenderKeepsOrderAndIncludesMetaAndApprovalNote() {
		String md = PlanMarkdown.render(ENTRIES, "sess-1", LocalTime.of(3, 4, 5), true);
		List<String> lines = List.of(md.split("\n", -1));
		assertEquals("# Kiro plan", lines.get(0));
		assertEquals("_Session sess-1 · updated 03:04:05_", lines.get(1));
		assertTrue(md.contains(PlanMarkdown.APPROVAL_NOTE));
		assertTrue(md.contains("**1 of 3 done · 1 in progress**"));
		assertEquals(List.of("- [x] **(high)** Read the code", "- [ ] ⏳ Write tests", "- [ ] (low) Polish docs"),
				lines.stream().filter(line -> line.startsWith("- [")).toList());
		assertTrue(md.indexOf(PlanMarkdown.APPROVAL_NOTE) < md.indexOf("- [x]"), "note precedes the list");
		assertEquals("# Kiro plan\n_Session sess-1 · updated 03:04:05_\n\n" + PlanMarkdown.APPROVAL_NOTE + "\n\n"
				+ "**1 of 3 done · 1 in progress**\n\n- [x] **(high)** Read the code\n- [ ] ⏳ Write tests\n- [ ] (low) Polish docs\n", md);
	}

	@Test
	void approvalNoteAbsentWhenNotPending() {
		String md = PlanMarkdown.render(ENTRIES, null, null, false);
		assertFalse(md.contains(PlanMarkdown.APPROVAL_NOTE));
		assertFalse(md.contains("_Session"));
		assertTrue(PlanMarkdown.APPROVAL_NOTE.contains("waiting for approval"));
	}
}
