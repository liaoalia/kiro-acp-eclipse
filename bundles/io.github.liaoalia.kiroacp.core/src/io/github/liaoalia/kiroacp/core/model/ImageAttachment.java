package io.github.liaoalia.kiroacp.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/** An image attached in the composer and sent with a prompt as an ACP {@code image} block. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ImageAttachment(String id, String name, String mimeType,
		/** The image bytes, base64 without a {@code data:} prefix. */
		String data,
		/** Decoded size in bytes. */
		long size,
		/** Absolute path when the image came from a file. */
		String path,
		/** Small {@code data:} URL thumbnail made by the page. */
		String preview) {
}
