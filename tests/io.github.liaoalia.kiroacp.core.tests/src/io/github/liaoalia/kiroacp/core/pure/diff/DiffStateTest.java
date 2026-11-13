package io.github.liaoalia.kiroacp.core.pure.diff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import io.github.liaoalia.kiroacp.core.pure.diff.DiffState.DiffSpec;
import io.github.liaoalia.kiroacp.core.pure.diff.DiffState.Substitution;
import io.github.liaoalia.kiroacp.core.pure.diff.DiffState.TrackedDiff;

/** The cases of the reference's {@code test/features-diffState.test.ts}. */
class DiffStateTest {

	private static String lower(String path) {
		return path.toLowerCase(Locale.ROOT).replace('/', '\\');
	}

	@Test
	void registerDiffsForRemove() {
		DiffState s = new DiffState();
		s.register("tc1", 1, new DiffSpec("/b", "x", "y"));
		s.register("tc1", 0, new DiffSpec("/a", null, "new"));
		assertEquals(List.of(0, 1), s.diffsFor("tc1").stream().map(TrackedDiff::index).toList(), "ordered by index");
		assertTrue(s.hasDiffs("tc1"));
		assertFalse(s.hasDiffs("tc2"));
		assertEquals(List.of("tc1"), s.toolCallIds());
		assertEquals("new", s.diffAt("tc1", 0).newText());
		s.remove("tc1");
		assertFalse(s.hasDiffs("tc1"));
		assertEquals(List.of(), s.diffsFor("tc1"));
	}

	@Test
	void takeSubstitutionMatchesNormalisedPathAndExactAgentText() {
		DiffState s = new DiffState(DiffStateTest::lower);
		s.addSubstitution("tc1", new Substitution("C:/ws/a.ts", "agent", "user"));
		assertNull(s.takeSubstitution("c:\\ws\\a.ts", "other"), "text must match");
		assertNull(s.takeSubstitution("c:\\ws\\b.ts", "agent"), "path must match");
		assertEquals("user", s.takeSubstitution("c:\\WS\\a.ts", "agent"));
		assertNull(s.takeSubstitution("c:\\ws\\a.ts", "agent"), "consumed");
	}

	@Test
	void pendingForDropSubstitutionsReplaceSamePath() {
		DiffState s = new DiffState();
		s.addSubstitution("tc1", new Substitution("/a", "1", "2"));
		s.addSubstitution("tc1", new Substitution("/a", "3", "4"));
		s.addSubstitution("tc2", new Substitution("/b", "5", "6"));
		assertEquals(List.of(new Substitution("/a", "3", "4")), s.pendingFor("tc1"));
		assertEquals(2, s.substitutions().size());
		s.dropSubstitutions("tc1");
		assertEquals(List.of(), s.pendingFor("tc1"));
		assertEquals(1, s.pendingFor("tc2").size());
		s.clear();
		assertEquals(0, s.substitutions().size());
	}

	@Test
	void removingDiffsKeepsSubstitutions() {
		DiffState s = new DiffState();
		s.register("tc1", 0, new DiffSpec("/a", null, "n"));
		s.addSubstitution("tc1", new Substitution("/a", "n", "u"));
		s.remove("tc1");
		assertEquals("u", s.takeSubstitution("/a", "n"));
	}
}
