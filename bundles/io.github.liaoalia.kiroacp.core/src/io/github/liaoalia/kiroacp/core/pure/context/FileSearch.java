package io.github.liaoalia.kiroacp.core.pure.context;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

import io.github.liaoalia.kiroacp.core.pure.text.JsText;

/**
 * Matching and ranking for the {@code @} file picker (the reference's
 * {@code features/pure/fileSearch.ts}), plus the reference's glob as a
 * predicate: there it is handed to {@code workspace.findFiles}, here the
 * host walks the folders itself.
 */
public final class FileSearch {

	/** Suggestions shown for one query. */
	public static final int MAX_SUGGESTIONS = 30;

	/** {@code {**&#47;node_modules/**,**&#47;.git/**,**&#47;dist/**,**&#47;out/**}}: what the reference never searches. */
	public static final String SEARCH_EXCLUDE = "{**/node_modules/**,**/.git/**,**/dist/**,**/out/**}";

	/** {@link #SEARCH_EXCLUDE} as the names of the folders that are not entered, at any depth. */
	public static final Set<String> EXCLUDED_FOLDERS = Set.of("node_modules", ".git", "dist", "out");

	private static final String GLOB_CHARACTERS = "*?[]{}()!";

	private FileSearch() {
	}

	/** Characters with glob meaning are stripped from the user's query; {@code \} becomes {@code /}. */
	public static String sanitizeQuery(String query) {
		StringBuilder out = new StringBuilder(query.length());
		for (int i = 0; i < query.length(); i++) {
			char c = query.charAt(i) == '\\' ? '/' : query.charAt(i);
			if (GLOB_CHARACTERS.indexOf(c) < 0) {
				out.append(c);
			}
		}
		return JsText.trim(out.toString());
	}

	/** The glob the reference searches with for a query. */
	public static String globForQuery(String query) {
		String q = sanitizeQuery(query);
		return q.isEmpty() ? "**/*" : "**/*" + q + "*";
	}

	/** How many matches the reference asks the search for before ranking them: {@code max(max * 5, 50)}. */
	public static int searchLimit(int max) {
		return Math.max(max * 5, 50);
	}

	/**
	 * Whether {@link #globForQuery} matches a file, case-insensitively.
	 * {@code *} does not cross a {@code /}: a query without one must be
	 * contained in the file's name; of a query with some, the last part starts
	 * the file's name, the first ends a folder's name and those between are
	 * the names of the folders between.
	 *
	 * @param relativePath the file relative to the searched folder, {@code /} or {@code \} separated
	 */
	public static boolean matches(String query, String relativePath) {
		String q = sanitizeQuery(query).toLowerCase(Locale.ROOT);
		if (q.isEmpty()) {
			return true;
		}
		String[] parts = q.split("/", -1);
		String[] segments = relativePath.replace('\\', '/').toLowerCase(Locale.ROOT).split("/", -1);
		int first = segments.length - parts.length;
		if (first < 0) {
			return false;
		}
		if (parts.length == 1) {
			return segments[first].contains(parts[0]);
		}
		if (!segments[first].endsWith(parts[0]) || !segments[segments.length - 1].startsWith(parts[parts.length - 1])) {
			return false;
		}
		for (int i = 1; i < parts.length - 1; i++) {
			if (!segments[first + i].equals(parts[i])) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Higher is better: the file's name starts with the query (3), contains
	 * it (2), the path contains it (1), otherwise 0. Case-insensitive.
	 */
	public static int scoreFileMatch(String query, String relPath) {
		String q = sanitizeQuery(query).toLowerCase(Locale.ROOT);
		if (q.isEmpty()) {
			return 1;
		}
		String p = relPath.replace('\\', '/').toLowerCase(Locale.ROOT);
		String base = p.substring(p.lastIndexOf('/') + 1);
		if (base.startsWith(q)) {
			return 3;
		}
		if (base.contains(q)) {
			return 2;
		}
		return p.contains(q) ? 1 : 0;
	}

	/**
	 * Sort by {@link #scoreFileMatch} descending, then by the label's length,
	 * then alphabetically, then by the order given.
	 *
	 * <p>The reference's alphabetical order is {@code localeCompare}; here it
	 * is the order of the letters regardless of case, lower case first where
	 * that alone differs. It only decides between labels of the same score
	 * and length.
	 */
	public static <T> List<T> sortByScore(String query, List<T> items, Function<? super T, String> label) {
		record Scored<T>(T item, String label, int score) {
		}
		List<Scored<T>> scored = new ArrayList<>(items.size());
		for (T item : items) {
			String text = label.apply(item);
			scored.add(new Scored<>(item, text, scoreFileMatch(query, text)));
		}
		// The sort is stable, which is the reference's last criterion: the index.
		scored.sort(Comparator.<Scored<T>>comparingInt(s -> -s.score()).thenComparingInt(s -> s.label().length())
				.thenComparing(Scored::label, FileSearch::alphabetical));
		return scored.stream().map(Scored::item).toList();
	}

	private static int alphabetical(String a, String b) {
		int order = a.compareToIgnoreCase(b);
		return order != 0 ? order : b.compareTo(a);
	}
}
