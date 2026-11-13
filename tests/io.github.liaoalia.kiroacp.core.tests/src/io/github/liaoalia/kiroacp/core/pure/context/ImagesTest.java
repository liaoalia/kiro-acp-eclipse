package io.github.liaoalia.kiroacp.core.pure.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.liaoalia.kiroacp.core.model.ImageAttachment;
import io.github.liaoalia.kiroacp.core.pure.context.Images.ImageRead;
import io.github.liaoalia.kiroacp.core.pure.context.Images.PromptImages;
import io.github.liaoalia.kiroacp.core.pure.context.Images.Verdict;
import io.github.liaoalia.kiroacp.core.pure.text.NodePath;

/** The cases of the reference's {@code test/features-images.test.ts}. */
class ImagesTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** A real 1x1 PNG. */
	private static final String PNG_1PX = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";
	private static final byte[] PNG = Base64.getDecoder().decode(PNG_1PX);
	private static final byte[] JPEG = { (byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 0x00, 0x10, 0x4a, 0x46, 0x49, 0x46, 0x00,
			0x01 };
	private static final byte[] GIF87 = latin1("GIF87a\u0001\u0000\u0001\u0000\u0000\u0000");
	private static final byte[] GIF89 = latin1("GIF89a\u0001\u0000\u0001\u0000\u0000\u0000");
	private static final byte[] WEBP = latin1("RIFF\u0024\u0000\u0000\u0000WEBPVP8 ");
	private static final byte[] WAVE = latin1("RIFF\u0024\u0000\u0000\u0000WAVEfmt ");
	private static final byte[] SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(StandardCharsets.UTF_8);

	private static byte[] latin1(String text) {
		return text.getBytes(StandardCharsets.ISO_8859_1);
	}

	private static byte[] utf8(String text) {
		return text.getBytes(StandardCharsets.UTF_8);
	}

	private static String base64(byte[] bytes) {
		return Base64.getEncoder().encodeToString(bytes);
	}

	/** {@code size} bytes that start as a PNG does. */
	private static byte[] pngOfSize(int size) {
		byte[] big = new byte[size];
		System.arraycopy(PNG, 0, big, 0, PNG.length);
		return big;
	}

	private static ImageAttachment attachment(String data) {
		return new ImageAttachment("img-1", "shot.png", "image/png", data, 1, null, null);
	}

	private static ImageAttachment attachment(byte[] bytes) {
		return attachment(base64(bytes));
	}

	private static ImageAttachment attachment(byte[] bytes, String name, String mimeType) {
		return new ImageAttachment("img-1", name, mimeType, base64(bytes), 1, null, null);
	}

	private static Verdict ok(String mimeType, long size) {
		return new Verdict(true, mimeType, size, null);
	}

	private static Verdict refused(String reason) {
		return new Verdict(false, null, 0, reason);
	}

	private static JsonNode json(String text) {
		try {
			return MAPPER.readTree(text);
		} catch (JsonProcessingException e) {
			throw new IllegalArgumentException(text, e);
		}
	}

	private static String url(String absPath) {
		return MAPPER.valueToTree(Path.of(absPath).toUri().toString()).toString();
	}

	@Test
	void sniffImageTypeRecognisesPngJpegGifAndWebpByTheirMagicBytes() {
		assertEquals("image/png", Images.sniffImageType(PNG));
		assertEquals("image/jpeg", Images.sniffImageType(JPEG));
		assertEquals("image/gif", Images.sniffImageType(GIF87));
		assertEquals("image/gif", Images.sniffImageType(GIF89));
		assertEquals("image/webp", Images.sniffImageType(WEBP));
	}

	@Test
	void sniffBase64ImageTypeAgreesWithSniffImageTypeOnEveryFormatAndOnJunk() {
		Object[][] cases = { { PNG, "image/png" }, { JPEG, "image/jpeg" }, { GIF87, "image/gif" }, { GIF89, "image/gif" },
				{ WEBP, "image/webp" }, { SVG, null }, { utf8("hello world, not an image"), null }, { WAVE, null } };
		for (Object[] each : cases) {
			byte[] bytes = (byte[]) each[0];
			assertEquals(each[1], Images.sniffBase64ImageType(base64(bytes)), new String(bytes, StandardCharsets.ISO_8859_1));
			assertEquals(Images.sniffImageType(bytes), Images.sniffBase64ImageType(base64(bytes)));
		}
		// Every byte offset of the 12-byte prefix decodes right, including a short (padded) buffer.
		assertEquals("image/jpeg", Images.sniffBase64ImageType(base64(new byte[] { (byte) 0xff, (byte) 0xd8, (byte) 0xff })));
		assertNull(Images.sniffBase64ImageType(""));
		assertNull(Images.sniffBase64ImageType("data:image/png;base64,iVBORw0KGgo"), "a data: URL is not plain base64");
		assertNull(Images.sniffBase64ImageType("iVBOR w0KGgo"), "whitespace in the prefix");
	}

	@Test
	void imageDataVerdictTheBytesDecideTheTypeTheLengthTheSizeTheReasonsMatchTheHosts() {
		assertEquals(ok("image/png", PNG.length), Images.imageDataVerdict("shot.jpg", PNG_1PX, "image/jpeg"), "a PNG named .jpg is a PNG");

		String notPng = base64(utf8("not really a png"));
		assertEquals(refused("photo.png was not attached: it is not a PNG, JPEG, GIF or WebP image."),
				Images.imageDataVerdict("photo.png", notPng, "image/png"));

		assertTrue(Images.imageDataVerdict("logo.svg", base64(SVG), "image/svg+xml").reason().contains("only PNG, JPEG, GIF and WebP"));
		assertTrue(Images.imageDataVerdict("empty.png", "", "image/png").reason().contains("the file is empty"));

		String big = base64(pngOfSize(Images.MAX_IMAGE_BYTES + 1));
		assertEquals(refused("big.png was not attached: it is 5.1 MB, images up to 5 MB can be sent."),
				Images.imageDataVerdict("big.png", big, "image/png"));

		// The host's send check gives the very same verdicts.
		String[][] same = { { "photo.png", notPng, "image/png" }, { "big.png", big, "image/png" }, { "shot.jpg", PNG_1PX, "image/jpeg" } };
		for (String[] each : same) {
			assertEquals(Images.imageDataVerdict(each[0], each[1], each[2]),
					Images.checkImageAttachment(new ImageAttachment("img-1", each[0], each[2], each[1], 1, null, null)));
		}
	}

	@Test
	void sniffImageTypeRejectsJunkShortBuffersAndOtherRiffFiles() {
		assertNull(Images.sniffImageType(new byte[0]));
		assertNull(Images.sniffImageType(SVG));
		assertNull(Images.sniffImageType(utf8("hello world, not an image")));
		assertNull(Images.sniffImageType(Arrays.copyOf(PNG, 7)));
		assertNull(Images.sniffImageType(latin1("RIFF")));
		assertNull(Images.sniffImageType(WAVE));
		assertNull(Images.sniffImageType(latin1("GIF88a\u0001\u0000")));
	}

	@Test
	void checkImageAttachmentAcceptsAValidImageWithItsRealDecodedSize() {
		assertEquals(ok("image/png", PNG.length),
				Images.checkImageAttachment(new ImageAttachment("img-1", "shot.png", "image/png", PNG_1PX, 999, null, null)));
		assertEquals(ok("image/webp", WEBP.length), Images.checkImageAttachment(attachment(WEBP, "shot.png", "image/webp")));
	}

	@Test
	void checkImageAttachmentTheSniffedTypeWinsOverTheDeclaredOne() {
		assertEquals(ok("image/jpeg", JPEG.length), Images.checkImageAttachment(attachment(JPEG, "shot.png", "image/png")));
		// A declared type that is not accepted is corrected too when the bytes are an accepted image.
		assertEquals(ok("image/gif", GIF89.length), Images.checkImageAttachment(attachment(GIF89, "shot.png", "image/svg+xml")));
	}

	@Test
	void checkImageAttachmentRejectsOtherImageTypesAndUnrecognisedBytes() {
		Verdict svg = Images.checkImageAttachment(attachment(SVG, "logo.svg", "image/svg+xml"));
		assertFalse(svg.ok());
		assertTrue(svg.reason().startsWith("logo.svg was not attached: only PNG, JPEG, GIF and WebP"), svg.reason());

		assertEquals(refused("shot.png was not attached: it is not a PNG, JPEG, GIF or WebP image."),
				Images.checkImageAttachment(attachment(utf8("not really a png at all"))));
	}

	@Test
	void checkImageAttachmentRejectsAnImageOverTheSizeLimitByItsDecodedSize() {
		byte[] big = pngOfSize(Images.MAX_IMAGE_BYTES + 1024 * 1024);
		assertEquals(refused("shot.png was not attached: it is 6 MB, images up to 5 MB can be sent."),
				Images.checkImageAttachment(new ImageAttachment("img-1", "shot.png", "image/png", base64(big), 10, null, null)));
		// One byte over is refused; exactly at the limit is fine.
		assertFalse(Images.checkImageAttachment(attachment(Arrays.copyOf(big, Images.MAX_IMAGE_BYTES + 1))).ok());
		assertEquals(ok("image/png", Images.MAX_IMAGE_BYTES),
				Images.checkImageAttachment(attachment(Arrays.copyOf(big, Images.MAX_IMAGE_BYTES))));
	}

	@Test
	void checkImageAttachmentRejectsDataThatIsNotPlainBase64() {
		Verdict expected = refused("shot.png was not attached: its data is not valid base64.");
		for (String data : List.of("data:image/png;base64," + PNG_1PX, PNG_1PX.substring(1),
				PNG_1PX.substring(0, 8) + " " + PNG_1PX.substring(8, 15), "ab=c", "a===", "#$%^")) {
			assertEquals(expected, Images.checkImageAttachment(attachment(data)), data);
		}
		assertFalse(Images.checkImageAttachment(attachment((String) null)).ok());
		assertEquals(refused("An image was not attached: it arrived malformed."), Images.checkImageAttachment(null));
	}

	@Test
	void checkImageAttachmentRejectsAnEmptyImageAndNamesUnnamedOnesGenerically() {
		assertEquals(refused("shot.png was not attached: the file is empty."), Images.checkImageAttachment(attachment("")));
		Verdict unnamed = Images.checkImageAttachment(attachment(SVG, "", "image/png"));
		assertTrue(unnamed.reason().startsWith("An image was not attached"), unnamed.reason());
	}

	@Test
	void imageAttachmentFromBytesBuildsAnAttachmentFromAFilesBytes() {
		String abs = NodePath.resolve("/ws/pics/shot.png", null);
		assertEquals(new ImageRead(true, new ImageAttachment("host-img-1", "shot.png", "image/jpeg", base64(JPEG), JPEG.length, abs, null),
				null), Images.imageAttachmentFromBytes("host-img-1", "shot.png", JPEG, abs));
		ImageRead noPath = Images.imageAttachmentFromBytes("host-img-2", "a.gif", GIF89, null);
		assertTrue(noPath.ok());
		assertNull(noPath.image().path());
		assertEquals(PNG_1PX, Images.imageAttachmentFromBytes("host-img-3", "p.png", PNG, null).image().data());
	}

	@Test
	void imageAttachmentFromBytesAppliesTheSameRules() {
		ImageRead svg = Images.imageAttachmentFromBytes("i", "logo.svg", SVG, null);
		assertTrue(svg.reason().startsWith("logo.svg was not attached: only PNG, JPEG, GIF and WebP"), svg.reason());
		assertEquals("fake.png was not attached: it is not a PNG, JPEG, GIF or WebP image.",
				Images.imageAttachmentFromBytes("i", "fake.png", utf8("plain text"), null).reason());
		assertEquals("empty.png was not attached: the file is empty.",
				Images.imageAttachmentFromBytes("i", "empty.png", new byte[0], null).reason());
		assertEquals("big.png was not attached: it is 10 MB, images up to 5 MB can be sent.",
				Images.imageAttachmentFromBytes("i", "big.png", pngOfSize(Images.MAX_IMAGE_BYTES * 2), null).reason());
	}

	@Test
	void imageBlockCarriesTheFileUrlOnlyForImagesFromAFile() {
		String abs = NodePath.resolve("/ws/My Shot.png", null);
		assertEquals(json("{\"type\":\"image\",\"mimeType\":\"image/png\",\"data\":\"" + PNG_1PX + "\",\"uri\":" + url(abs) + "}"),
				Images.imageBlock(new ImageAttachment("img-1", "shot.png", "image/png", PNG_1PX, 1, abs, "data:image/png;base64,AAAA")));
		assertEquals(json("{\"type\":\"image\",\"mimeType\":\"image/png\",\"data\":\"" + PNG_1PX + "\"}"),
				Images.imageBlock(attachment(PNG)));
	}

	@Test
	void echoImageContentShowsThePreviewElseTheSmallOriginalElseNothing() {
		String preview = "data:image/jpeg;base64," + base64(JPEG);
		assertEquals(json("{\"type\":\"image\",\"mimeType\":\"image/jpeg\",\"data\":\"" + base64(JPEG) + "\"}"),
				Images.echoImageContent(new ImageAttachment("img-1", "shot.png", "image/png", PNG_1PX, 1, null, preview)));
		// An unsafe or broken preview is ignored: the (small) image itself is shown.
		for (String bad : List.of("data:image/svg+xml;base64,PHN2Zy8+", "javascript:alert(1)", "data:image/png;base64,AA AA")) {
			assertEquals(json("{\"type\":\"image\",\"mimeType\":\"image/png\",\"data\":\"" + PNG_1PX + "\"}"),
					Images.echoImageContent(new ImageAttachment("img-1", "shot.png", "image/png", PNG_1PX, 1, null, bad)), bad);
		}
		String large = "A".repeat(Images.MAX_INLINE_PREVIEW_CHARS + 4);
		assertEquals(json("{\"type\":\"image\",\"mimeType\":\"image/png\",\"data\":\"\"}"), Images.echoImageContent(attachment(large)));
		String abs = NodePath.resolve("/ws/shot.png", null);
		assertEquals(json("{\"type\":\"image\",\"mimeType\":\"image/png\",\"data\":\"\",\"uri\":" + url(abs) + "}"),
				Images.echoImageContent(new ImageAttachment("img-1", "shot.png", "image/png", large, 1, abs, null)));
	}

	// --- beyond the reference's cases: its shared rules (src/ui/images.ts) and the check of a whole prompt ---

	@Test
	void sizesAreWrittenAsTheReferenceWritesThem() {
		assertEquals("512 B", Images.formatBytes(512));
		assertEquals("1 KB", Images.formatBytes(1024));
		assertEquals("48 KB", Images.formatBytes(48 * 1024 + 300));
		assertEquals("2 KB", Images.formatBytes(1536), "half a kilobyte rounds up");
		assertEquals("5 MB", Images.formatBytes(5 * 1024 * 1024));
		assertEquals("5.1 MB", Images.formatBytes(5 * 1024 * 1024 + 1));
		assertEquals("7.2 MB", Images.formatBytes((long) (7.15 * 1024 * 1024)));
		assertEquals("10 MB", Images.formatBytes(10 * 1024 * 1024));
		assertEquals("11 MB", Images.formatBytes(10 * 1024 * 1024 + 1));
	}

	@Test
	void theTypeOfAFileNameIsTheOneOfItsExtension() {
		assertEquals("image/jpeg", Images.imageTypeForName("C:\\pics\\Photo.JPG"));
		assertEquals("image/jpeg", Images.imageTypeForName("photo.jpeg"));
		assertEquals("image/webp", Images.imageTypeForName("/a/b.c/shot.webp"));
		assertNull(Images.imageTypeForName("/a/b.png/readme"));
		assertNull(Images.imageTypeForName("logo.svg"));
		assertNull(Images.imageTypeForName("png"));
		assertTrue(Images.isAcceptedImageType("IMAGE/PNG"));
		assertFalse(Images.isAcceptedImageType("image/svg+xml"));
		assertEquals(List.of("png", "jpg", "jpeg", "gif", "webp"), Images.IMAGE_EXTENSIONS);
		assertEquals(Images.IMAGE_EXTENSIONS.size(), Images.IMAGE_TYPE_BY_EXTENSION.size());
	}

	@Test
	void onlySafeImageDataUrlsAreSplit() {
		assertEquals(List.of("image/webp", "AAAA"), List.of(Images.parseImageDataUrl("data:image/webp;base64,AAAA")));
		assertEquals(List.of("image/png", "AA=="), List.of(Images.parseImageDataUrl(Images.imageDataUrl("image/png", "AA=="))));
		for (String bad : List.of("data:image/png;base64,", "data:image/png;base64,==", "data:image/png;base64,AA===",
				"data:image/png;base64,AA=A", "data:IMAGE/PNG;base64,AAAA", "data:image/png,AAAA", "https://example.com/a.png")) {
			assertNull(Images.parseImageDataUrl(bad), bad);
			assertFalse(Images.isSafeImageDataUrl(bad), bad);
		}
	}

	@Test
	void aPromptsImagesAreCheckedTogetherAndEveryReasonIsNamedInOneWarning() {
		ImageAttachment good = new ImageAttachment("a", "a.png", "image/jpeg", PNG_1PX, 999, null, "p");
		PromptImages fine = Images.checkPromptImages(List.of(good), true);
		assertNull(fine.refusal());
		assertEquals(List.of(new ImageAttachment("a", "a.png", "image/png", PNG_1PX, PNG.length, null, "p")), fine.images(),
				"the type and the size are the ones of the bytes");

		PromptImages unsupported = Images.checkPromptImages(List.of(good), false);
		assertEquals("Kiro does not accept images, so the message was not sent.", unsupported.refusal());
		assertEquals(List.of(), unsupported.images());

		List<ImageAttachment> eleven = new ArrayList<>();
		for (int i = 0; i < 11; i++) {
			eleven.add(good);
		}
		assertEquals("A message can carry up to 10 images (this one had 11), so it was not sent.",
				Images.checkPromptImages(eleven, true).refusal());
		assertNull(Images.checkPromptImages(eleven.subList(0, 10), true).refusal());

		PromptImages mixed = Images.checkPromptImages(List.of(good, attachment(utf8("junk"), "junk.png", "image/png"), attachment("")),
				true);
		assertEquals("junk.png was not attached: it is not a PNG, JPEG, GIF or WebP image. "
				+ "shot.png was not attached: the file is empty. The message was not sent.", mixed.refusal());
		assertEquals(List.of(), mixed.images(), "one refused image keeps the whole message back");
	}
}
