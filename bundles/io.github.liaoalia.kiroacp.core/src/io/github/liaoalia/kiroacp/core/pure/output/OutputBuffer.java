package io.github.liaoalia.kiroacp.core.pure.output;

import java.util.ArrayDeque;

/**
 * Retains appended text up to {@code limitBytes} (UTF-8), the unit of ACP's
 * {@code outputByteLimit} (D9 of the reference). When the limit is exceeded the
 * OLDEST bytes are dropped, cutting on a character boundary so the retained
 * text is always valid. A limit of zero or less means unbounded.
 *
 * <p>Sizes are those of the UTF-8 encoding of the UTF-16 text as the reference
 * computes them: a surrogate pair is one 4-byte character, an unpaired
 * surrogate counts as the 3 bytes of U+FFFD.
 *
 * <p>Synchronized: a process's reader threads append while the agent asks for
 * the output.
 */
public final class OutputBuffer {

	private record Chunk(String text, long size) {
	}

	private final long limitBytes;
	private final ArrayDeque<Chunk> chunks = new ArrayDeque<>();
	private long total;
	private String cache = "";
	private boolean truncated;

	public OutputBuffer(long limitBytes) {
		this.limitBytes = limitBytes;
	}

	public long limitBytes() {
		return limitBytes;
	}

	/** Append {@code chunk} and trim from the start if necessary. */
	public synchronized void append(String chunk) {
		if (chunk.isEmpty()) {
			return;
		}
		long size = utf8Length(chunk);
		chunks.addLast(new Chunk(chunk, size));
		total += size;
		cache = null;
		trim();
	}

	/** The retained text. */
	public synchronized String text() {
		if (cache == null) {
			StringBuilder joined = new StringBuilder();
			for (Chunk chunk : chunks) {
				joined.append(chunk.text());
			}
			cache = joined.toString();
		}
		return cache;
	}

	/** True once any output was dropped because of the limit. */
	public synchronized boolean truncated() {
		return truncated;
	}

	/** Retained size in UTF-8 bytes. */
	public synchronized long byteLength() {
		return total;
	}

	private void trim() {
		if (limitBytes <= 0 || total <= limitBytes) {
			return;
		}
		truncated = true;
		while (total > limitBytes && !chunks.isEmpty()) {
			long excess = total - limitBytes;
			Chunk first = chunks.removeFirst();
			if (first.size() <= excess) {
				total -= first.size();
				continue;
			}
			// Dropping at least `excess` bytes of the first chunk ends the loop.
			String kept = dropLeadingBytes(first.text(), excess);
			long keptSize = utf8Length(kept);
			total -= first.size() - keptSize;
			chunks.addFirst(new Chunk(kept, keptSize));
		}
	}

	/**
	 * Drop at least {@code bytes} UTF-8 bytes from the start of {@code s}, moving
	 * the cut forward to the next character boundary.
	 *
	 * <p>As in the reference, where the text makes a round trip through its
	 * encoding, unpaired surrogates of the kept part come back as U+FFFD; nothing
	 * changes when nothing is dropped.
	 */
	public static String dropLeadingBytes(String s, long bytes) {
		if (bytes <= 0) {
			return s;
		}
		long dropped = 0;
		int cut = 0;
		while (cut < s.length() && dropped < bytes) {
			int width = pairAt(s, cut) ? 2 : 1;
			dropped += utf8Length(s.charAt(cut), width == 2);
			cut += width;
		}
		if (cut >= s.length()) {
			return "";
		}
		StringBuilder kept = new StringBuilder(s.length() - cut);
		for (int i = cut; i < s.length(); i++) {
			char c = s.charAt(i);
			if (pairAt(s, i)) {
				kept.append(c).append(s.charAt(++i));
			} else {
				kept.append(Character.isSurrogate(c) ? '�' : c);
			}
		}
		return kept.toString();
	}

	/**
	 * Length of the UTF-8 encoding of {@code s}. Not {@code getBytes(UTF_8).length}:
	 * the JDK encodes an unpaired surrogate as one byte ({@code ?}), the reference
	 * as three.
	 */
	public static long utf8Length(String s) {
		long size = 0;
		for (int i = 0; i < s.length(); i++) {
			boolean pair = pairAt(s, i);
			size += utf8Length(s.charAt(i), pair);
			if (pair) {
				i++;
			}
		}
		return size;
	}

	private static boolean pairAt(String s, int i) {
		return Character.isHighSurrogate(s.charAt(i)) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1));
	}

	private static int utf8Length(char c, boolean startsPair) {
		if (startsPair) {
			return 4;
		}
		if (c < 0x80) {
			return 1;
		}
		return c < 0x800 ? 2 : 3;
	}
}
