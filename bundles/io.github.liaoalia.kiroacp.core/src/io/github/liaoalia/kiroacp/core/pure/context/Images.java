package io.github.liaoalia.kiroacp.core.pure.context;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.liaoalia.kiroacp.core.model.ImageAttachment;
import io.github.liaoalia.kiroacp.core.pure.text.JsText;

/**
 * Image attachments (reference D29): the rules the page and the host share
 * ({@code src/ui/images.ts}: accepted types, size and count limits, the
 * type of an image by its bytes) and the host's side of them
 * ({@code features/pure/images.ts}: the check before sending, an attachment
 * from a file's bytes, the ACP {@code image} blocks).
 *
 * <p>The words of every refusal are the reference's: the page refuses the
 * same images with the same sentences.
 */
public final class Images {

	/** The formats the Claude-family models behind Kiro accept as prompt images. */
	public static final List<String> ACCEPTED_IMAGE_TYPES = List.of("image/png", "image/jpeg", "image/gif", "image/webp");

	/** File extension (lower case, with the dot) to MIME type, for files picked or dropped. */
	public static final Map<String, String> IMAGE_TYPE_BY_EXTENSION = Map.of(".png", "image/png", ".jpg", "image/jpeg", ".jpeg",
			"image/jpeg", ".gif", "image/gif", ".webp", "image/webp");

	/** The extensions of {@link #IMAGE_TYPE_BY_EXTENSION} without the dot, in the reference's order: the file dialog's filter. */
	public static final List<String> IMAGE_EXTENSIONS = List.of("png", "jpg", "jpeg", "gif", "webp");

	/** Largest image (decoded bytes) that may be attached. */
	public static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;

	/** Most images one prompt may carry. */
	public static final int MAX_IMAGES_PER_PROMPT = 10;

	/** Largest base64 payload an echoed image without a thumbnail shows inline; larger ones show as an "[image]" chip. */
	public static final int MAX_INLINE_PREVIEW_CHARS = 512 * 1024;

	private static final String BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
	/** Base64 characters that decode to the 12 bytes every accepted format is recognised by. */
	private static final int SNIFF_CHARS = 16;
	private static final int[] PNG_SIGNATURE = { 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a };
	private static final int[] JPEG_SIGNATURE = { 0xff, 0xd8, 0xff };
	private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

	private Images() {
	}

	/**
	 * Whether an image may be attached.
	 *
	 * @param mimeType the type its bytes are of, when {@code ok}
	 * @param size     its decoded size, when {@code ok}
	 * @param reason   a sentence for a notice, when not {@code ok}
	 */
	public record Verdict(boolean ok, String mimeType, long size, String reason) {

		static Verdict accepted(String mimeType, long size) {
			return new Verdict(true, mimeType, size, null);
		}

		static Verdict refused(String reason) {
			return new Verdict(false, null, 0, reason);
		}
	}

	/** An attachment made of a file the host read, or why there is none. */
	public record ImageRead(boolean ok, ImageAttachment image, String reason) {
	}

	/**
	 * The images of a prompt after {@link #checkPromptImages}.
	 *
	 * @param images  the attachments with the type and size their bytes have; empty when refused
	 * @param refusal one warning naming every reason the message is not sent, else {@code null}
	 */
	public record PromptImages(List<ImageAttachment> images, String refusal) {
	}

	// ---------------------------------------------------------------------
	// Shared with the page
	// ---------------------------------------------------------------------

	/** MIME type for a file name or path by its extension; {@code null} when it is not an accepted image. */
	public static String imageTypeForName(String name) {
		int dot = name.lastIndexOf('.');
		if (dot < 0 || dot < Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'))) {
			return null;
		}
		return IMAGE_TYPE_BY_EXTENSION.get(name.substring(dot).toLowerCase(Locale.ROOT));
	}

	public static boolean isAcceptedImageType(String mimeType) {
		return ACCEPTED_IMAGE_TYPES.contains(mimeType.toLowerCase(Locale.ROOT));
	}

	/** Decoded byte count of a base64 string, without decoding it. */
	public static long base64Size(String data) {
		long len = data.length();
		if (len == 0) {
			return 0;
		}
		int padding = data.endsWith("==") ? 2 : data.endsWith("=") ? 1 : 0;
		return len * 3 / 4 - padding;
	}

	/** "512 B", "48 KB", "7.2 MB". Megabytes round up, so a file just over the 5 MB limit reads "5.1 MB", never "5 MB". */
	public static String formatBytes(long bytes) {
		if (bytes < 1024) {
			return bytes + " B";
		}
		if (bytes < 1024 * 1024) {
			return Math.round(bytes / 1024.0) + " KB";
		}
		double mb = bytes / (1024.0 * 1024.0);
		if (mb >= 10) {
			return (long) Math.ceil(mb) + " MB";
		}
		long tenths = (long) Math.ceil(mb * 10);
		return (tenths % 10 == 0 ? Long.toString(tenths / 10) : tenths / 10 + "." + tenths % 10) + " MB";
	}

	/**
	 * Why an image cannot be attached, as a sentence for a notice;
	 * {@code null} when it can. Checks the type and the size only (the count
	 * is the caller's).
	 */
	public static String imageRejection(String name, String mimeType, long size) {
		if (!isAcceptedImageType(mimeType)) {
			return name + " was not attached: only PNG, JPEG, GIF and WebP images can be sent to Kiro.";
		}
		if (size > MAX_IMAGE_BYTES) {
			return name + " was not attached: it is " + formatBytes(size) + ", images up to " + formatBytes(MAX_IMAGE_BYTES)
					+ " can be sent.";
		}
		if (size == 0) {
			return name + " was not attached: the file is empty.";
		}
		return null;
	}

	/**
	 * The image type of {@code bytes} by their magic number: PNG, JPEG, GIF
	 * (87a/89a) or WebP ({@code RIFF????WEBP}); {@code null} for anything else.
	 */
	public static String sniffImageType(byte[] bytes) {
		return sniffImageType(bytes, bytes.length);
	}

	private static String sniffImageType(byte[] bytes, int length) {
		if (hasBytes(bytes, length, PNG_SIGNATURE, 0)) {
			return "image/png";
		}
		if (hasBytes(bytes, length, JPEG_SIGNATURE, 0)) {
			return "image/jpeg";
		}
		if (hasBytes(bytes, length, ascii("GIF87a"), 0) || hasBytes(bytes, length, ascii("GIF89a"), 0)) {
			return "image/gif";
		}
		if (hasBytes(bytes, length, ascii("RIFF"), 0) && hasBytes(bytes, length, ascii("WEBP"), 8)) {
			return "image/webp";
		}
		return null;
	}

	private static int[] ascii(String signature) {
		return signature.chars().toArray();
	}

	private static boolean hasBytes(byte[] bytes, int length, int[] expected, int offset) {
		if (length < offset + expected.length) {
			return false;
		}
		for (int i = 0; i < expected.length; i++) {
			if ((bytes[offset + i] & 0xff) != expected[i]) {
				return false;
			}
		}
		return true;
	}

	/**
	 * {@link #sniffImageType} of base64 data, decoding only its first 12
	 * bytes. {@code null} when they are not an accepted image or the prefix
	 * is not base64.
	 */
	public static String sniffBase64ImageType(String data) {
		String head = data.substring(0, Math.min(SNIFF_CHARS, data.length()));
		int end = head.length();
		while (end > 0 && head.charAt(end - 1) == '=') {
			end--;
		}
		byte[] bytes = new byte[12];
		int count = 0;
		int bits = 0;
		int value = 0;
		for (int i = 0; i < end; i++) {
			int index = BASE64_ALPHABET.indexOf(head.charAt(i));
			if (index < 0) {
				return null;
			}
			value = ((value << 6) | index) & 0xffff;
			bits += 6;
			if (bits >= 8) {
				bits -= 8;
				bytes[count++] = (byte) ((value >> bits) & 0xff);
			}
		}
		return sniffImageType(bytes, count);
	}

	/**
	 * The verdict on image bytes of {@code size} that sniffed as
	 * {@code sniffed}: {@link #imageRejection}'s type and size rules applied
	 * to the sniffed type. {@code claimedType} (the declared type, or the
	 * file extension's) only words the reason when the bytes were not
	 * recognised, so an SVG or an empty file gets the sharper one.
	 */
	public static Verdict imageVerdict(String name, String sniffed, String claimedType, long size) {
		String rejection = imageRejection(name, sniffed != null ? sniffed : claimedType, size);
		if (rejection != null) {
			return Verdict.refused(rejection);
		}
		if (sniffed == null) {
			return Verdict.refused(name + " was not attached: it is not a PNG, JPEG, GIF or WebP image.");
		}
		return Verdict.accepted(sniffed, size);
	}

	/** {@link #imageVerdict} for base64 image data: the type from its bytes, the size decoded from its length. */
	public static Verdict imageDataVerdict(String name, String data, String claimedType) {
		return imageVerdict(name, sniffBase64ImageType(data), claimedType, base64Size(data));
	}

	/** {@code data:<mime>;base64,<data>}. */
	public static String imageDataUrl(String mimeType, String data) {
		return "data:" + mimeType + ";base64," + data;
	}

	/**
	 * Split a {@code data:} URL of an accepted image type.
	 *
	 * @return {@code [mimeType, data]}, or {@code null} for anything else (other types, not base64, stray characters)
	 */
	public static String[] parseImageDataUrl(String url) {
		for (String type : ACCEPTED_IMAGE_TYPES) {
			String prefix = "data:" + type + ";base64,";
			if (url.startsWith(prefix)) {
				String data = url.substring(prefix.length());
				return !data.isEmpty() && base64Body(data) >= 1 ? new String[] { type, data } : null;
			}
		}
		return null;
	}

	public static boolean isSafeImageDataUrl(String url) {
		return parseImageDataUrl(url) != null;
	}

	/**
	 * {@code /^[A-Za-z0-9+/]*={0,2}$/}, by hand: the data may be megabytes.
	 *
	 * @return the number of characters before the padding, or -1 when {@code data} is something else
	 */
	private static int base64Body(String data) {
		int end = data.length();
		int padding = 0;
		while (padding < 2 && end > 0 && data.charAt(end - 1) == '=') {
			end--;
			padding++;
		}
		for (int i = 0; i < end; i++) {
			char c = data.charAt(i);
			if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '+' || c == '/')) {
				return -1;
			}
		}
		return end;
	}

	// ---------------------------------------------------------------------
	// The host's side
	// ---------------------------------------------------------------------

	/** The name an image goes by in notices. */
	private static String displayName(String name) {
		return name != null && !JsText.trim(name).isEmpty() ? name : "An image";
	}

	/**
	 * Check an attachment sent by the page before it goes out: {@code data}
	 * must be plain base64; the type is taken from the bytes, not from the
	 * declared {@code mimeType} (an accepted type sniffed under another
	 * declared one corrects it), and the size is the real decoded size, not
	 * the declared one.
	 */
	public static Verdict checkImageAttachment(ImageAttachment img) {
		if (img == null) {
			return Verdict.refused("An image was not attached: it arrived malformed.");
		}
		String name = displayName(img.name());
		String data = img.data();
		if (data == null || data.length() % 4 != 0 || base64Body(data) < 0) {
			return Verdict.refused(name + " was not attached: its data is not valid base64.");
		}
		return imageDataVerdict(name, data, img.mimeType() != null ? img.mimeType() : "");
	}

	/**
	 * The images of a prompt, checked against their bytes: the agent must
	 * accept images, there may be at most {@link #MAX_IMAGES_PER_PROMPT},
	 * and every one must pass {@link #checkImageAttachment} (its sniffed type
	 * and decoded size replace the declared ones). Otherwise one warning names
	 * every reason and the message is not sent at all, not half-sent.
	 *
	 * @param agentAcceptsImages the agent advertised {@code promptCapabilities.image}; ACP allows image blocks only then
	 */
	public static PromptImages checkPromptImages(List<ImageAttachment> images, boolean agentAcceptsImages) {
		if (!agentAcceptsImages) {
			return new PromptImages(List.of(), "Kiro does not accept images, so the message was not sent.");
		}
		if (images.size() > MAX_IMAGES_PER_PROMPT) {
			return new PromptImages(List.of(), "A message can carry up to " + MAX_IMAGES_PER_PROMPT + " images (this one had "
					+ images.size() + "), so it was not sent.");
		}
		List<ImageAttachment> checked = new ArrayList<>();
		List<String> reasons = new ArrayList<>();
		for (ImageAttachment image : images) {
			Verdict check = checkImageAttachment(image);
			if (check.ok()) {
				checked.add(new ImageAttachment(image.id(), image.name(), check.mimeType(), image.data(), check.size(), image.path(),
						image.preview()));
			} else {
				reasons.add(check.reason());
			}
		}
		if (!reasons.isEmpty()) {
			return new PromptImages(List.of(), String.join(" ", reasons) + " The message was not sent.");
		}
		return new PromptImages(List.copyOf(checked), null);
	}

	/**
	 * An attachment for an image file the host read ({@code pickImages},
	 * {@code dropFiles}): the same checks as {@link #checkImageAttachment},
	 * the type sniffed from {@code bytes}.
	 *
	 * @param path the file's absolute path, which becomes the block's {@code uri}; may be {@code null}
	 */
	public static ImageRead imageAttachmentFromBytes(String id, String name, byte[] bytes, String path) {
		String claimed = name != null ? imageTypeForName(name) : null;
		Verdict check = imageVerdict(displayName(name), sniffImageType(bytes), claimed != null ? claimed : "", bytes.length);
		if (!check.ok()) {
			return new ImageRead(false, null, check.reason());
		}
		String data = new String(Base64.getEncoder().encode(bytes), StandardCharsets.ISO_8859_1);
		return new ImageRead(true,
				new ImageAttachment(id, name, check.mimeType(), data, check.size(), path == null || path.isEmpty() ? null : path, null),
				null);
	}

	/**
	 * The ACP {@code image} block sent for a checked attachment; {@code uri}
	 * is the file's URL when the image came from a file.
	 */
	public static JsonNode imageBlock(ImageAttachment img) {
		return block(img.mimeType(), img.data(), img);
	}

	/**
	 * The {@code image} block used only for the local echo of the user's
	 * message (reference D13; never sent): the page's thumbnail when it is a
	 * safe image data URL, else the image itself when it is small enough to
	 * show inline, else no data (the message shows an "[image]" chip).
	 */
	public static JsonNode echoImageContent(ImageAttachment img) {
		String[] preview = img.preview() != null ? parseImageDataUrl(img.preview()) : null;
		if (preview != null) {
			return block(preview[0], preview[1], img);
		}
		return block(img.mimeType(), img.data().length() <= MAX_INLINE_PREVIEW_CHARS ? img.data() : "", img);
	}

	private static ObjectNode block(String mimeType, String data, ImageAttachment source) {
		ObjectNode block = JSON.objectNode().put("type", "image").put("mimeType", mimeType).put("data", data);
		if (source.path() != null && !source.path().isEmpty()) {
			block.put("uri", ContentBlocks.fileUrlFor(source.path()));
		}
		return block;
	}
}
