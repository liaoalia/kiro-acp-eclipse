package io.github.liaoalia.kiroacp.core.acp.impl;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Reports every complete line that passes through a stream, for the protocol
 * trace. The wire layer has no hook for the raw text, and the trace should
 * show exactly the bytes exchanged, so the tap sits on the streams.
 */
final class LineTap {

	private final Consumer<String> onLine;
	private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

	LineTap(Consumer<String> onLine) {
		this.onLine = onLine;
	}

	InputStream wrap(InputStream in) {
		return new FilterInputStream(in) {
			@Override
			public int read() throws IOException {
				int value = super.read();
				if (value < 0) {
					flush();
				} else {
					accept((byte) value);
				}
				return value;
			}

			@Override
			public int read(byte[] buffer, int offset, int length) throws IOException {
				int count = super.read(buffer, offset, length);
				if (count < 0) {
					flush();
				} else {
					accept(buffer, offset, count);
				}
				return count;
			}

			@Override
			public boolean markSupported() {
				// A reset would report the same bytes twice.
				return false;
			}
		};
	}

	OutputStream wrap(OutputStream out) {
		return new FilterOutputStream(out) {
			@Override
			public void write(int value) throws IOException {
				out.write(value);
				accept((byte) value);
			}

			@Override
			public void write(byte[] buffer, int offset, int length) throws IOException {
				out.write(buffer, offset, length);
				accept(buffer, offset, length);
			}
		};
	}

	private synchronized void accept(byte[] buffer, int offset, int length) {
		for (int i = offset; i < offset + length; i++) {
			accept(buffer[i]);
		}
	}

	private synchronized void accept(byte value) {
		// Lines are cut at the byte level, so a character split across two reads stays whole.
		if (value == '\n') {
			flush();
		} else {
			pending.write(value);
		}
	}

	private synchronized void flush() {
		if (pending.size() == 0) {
			return;
		}
		String line = pending.toString(StandardCharsets.UTF_8);
		pending.reset();
		if (line.endsWith("\r")) {
			line = line.substring(0, line.length() - 1);
		}
		if (line.isBlank()) {
			return;
		}
		try {
			onLine.accept(line);
		} catch (RuntimeException e) {
			// Tracing must never break the transport.
		}
	}
}
