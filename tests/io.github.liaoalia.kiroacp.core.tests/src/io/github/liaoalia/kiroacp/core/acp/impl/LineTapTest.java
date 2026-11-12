package io.github.liaoalia.kiroacp.core.acp.impl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class LineTapTest {

	private final List<String> lines = new ArrayList<>();

	@Test
	void reportsEachLineReadWhateverTheChunksAndPassesTheBytesOnUnchanged() throws Exception {
		byte[] bytes = "{\"a\":\"äö€\"}\r\n\n  \n{\"b\":2}\nrest".getBytes(StandardCharsets.UTF_8);

		// Three bytes at a time cuts through the multi-byte characters.
		InputStream in = new LineTap(lines::add).wrap(new ByteArrayInputStream(bytes));
		ByteArrayOutputStream copy = new ByteArrayOutputStream();
		byte[] buffer = new byte[3];
		copy.write(in.read());
		for (int count = in.read(buffer, 0, 3); count >= 0; count = in.read(buffer, 0, 3)) {
			copy.write(buffer, 0, count);
		}

		assertArrayEquals(bytes, copy.toByteArray());
		assertEquals(List.of("{\"a\":\"äö€\"}", "{\"b\":2}", "rest"), lines);
	}

	@Test
	void reportsEachLineWrittenOnceItIsComplete() throws Exception {
		ByteArrayOutputStream target = new ByteArrayOutputStream();
		OutputStream out = new LineTap(lines::add).wrap(target);

		out.write("{\"a\":".getBytes(StandardCharsets.UTF_8));
		assertEquals(List.of(), lines);
		out.write("1}\n{\"b\"".getBytes(StandardCharsets.UTF_8));
		assertEquals(List.of("{\"a\":1}"), lines);
		out.write(':');
		out.write("2}\n".getBytes(StandardCharsets.UTF_8), 0, 3);

		assertEquals(List.of("{\"a\":1}", "{\"b\":2}"), lines);
		assertEquals("{\"a\":1}\n{\"b\":2}\n", target.toString(StandardCharsets.UTF_8));
	}

	@Test
	void aFailingListenerDoesNotBreakTheStream() throws Exception {
		ByteArrayOutputStream target = new ByteArrayOutputStream();
		OutputStream out = new LineTap(line -> {
			throw new IllegalStateException("log is full");
		}).wrap(target);

		out.write("{}\n".getBytes(StandardCharsets.UTF_8));

		assertEquals("{}\n", target.toString(StandardCharsets.UTF_8));
	}
}
