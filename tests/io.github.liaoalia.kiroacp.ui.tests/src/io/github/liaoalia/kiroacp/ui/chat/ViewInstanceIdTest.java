package io.github.liaoalia.kiroacp.ui.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ViewInstanceIdTest {

	@Test
	void aStoredIdIsKept() {
		String id = ViewInstanceId.resolve(null);
		assertEquals(id, ViewInstanceId.resolve(id));
	}

	@Test
	void viewsWithoutAStoredIdGetDifferentIds() {
		// Two workbench windows each create the view with no memento.
		assertNotEquals(ViewInstanceId.resolve(null), ViewInstanceId.resolve(null));
	}

	@Test
	void anIdIsSafeInAUrlAndAStorageKey() {
		assertTrue(ViewInstanceId.resolve(null).matches("[A-Za-z0-9-]{8,64}"));
	}

	@Test
	void anythingThisClassDidNotIssueIsReplaced() {
		for (String stored : new String[] { "", " ", "main", "a b c d e f g h", "../../etc/passwd", "x".repeat(65),
				"abcdefgh&view=other" }) {
			String id = ViewInstanceId.resolve(stored);
			assertNotEquals(stored, id, stored);
			assertTrue(id.matches("[A-Za-z0-9-]{8,64}"), id);
		}
	}
}
