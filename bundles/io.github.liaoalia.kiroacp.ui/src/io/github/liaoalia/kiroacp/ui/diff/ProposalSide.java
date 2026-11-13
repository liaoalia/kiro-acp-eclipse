package io.github.liaoalia.kiroacp.ui.diff;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import org.eclipse.compare.CompareUI;
import org.eclipse.compare.IEditableContent;
import org.eclipse.compare.IEncodedStreamContentAccessor;
import org.eclipse.compare.ITypedElement;
import org.eclipse.swt.graphics.Image;
import org.eclipse.ui.PlatformUI;

/**
 * One side of a proposal's compare editor: text that lives in memory. The
 * proposed side is editable; saving the editor hands the text to
 * {@code saved}.
 */
final class ProposalSide implements ITypedElement, IEncodedStreamContentAccessor, IEditableContent {

	private final String name;
	private final String type;
	private final Consumer<String> saved;
	private volatile String text;

	/** @param saved told the text whenever the editor saves this side; {@code null} for a side that cannot be edited */
	ProposalSide(String path, String text, Consumer<String> saved) {
		this.name = fileName(path);
		this.type = typeOf(name);
		this.text = text != null ? text : "";
		this.saved = saved;
	}

	String text() {
		return text;
	}

	@Override
	public String getName() {
		return name;
	}

	/** The icon of the file's type; the compare plug-in takes it from the workbench. */
	@Override
	public Image getImage() {
		return PlatformUI.isWorkbenchRunning() ? CompareUI.getImage(type) : null;
	}

	/** The file's extension: it chooses the viewer, as for a file of the workspace. */
	@Override
	public String getType() {
		return type;
	}

	@Override
	public InputStream getContents() {
		return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
	}

	@Override
	public String getCharset() {
		return StandardCharsets.UTF_8.name();
	}

	@Override
	public boolean isEditable() {
		return saved != null;
	}

	/** The compare editor saves the side it edited: the bytes are its text in {@link #getCharset()}. */
	@Override
	public void setContent(byte[] newContent) {
		if (saved == null || newContent == null) {
			return;
		}
		text = new String(newContent, StandardCharsets.UTF_8);
		saved.accept(text);
	}

	@Override
	public ITypedElement replace(ITypedElement dest, ITypedElement src) {
		return dest;
	}

	static String fileName(String path) {
		String given = path != null ? path : "";
		String name = given.substring(Math.max(given.lastIndexOf('/'), given.lastIndexOf('\\')) + 1);
		return name.isEmpty() ? "file" : name;
	}

	static String typeOf(String name) {
		int dot = name.lastIndexOf('.');
		return dot > 0 && dot < name.length() - 1 ? name.substring(dot + 1) : ITypedElement.TEXT_TYPE;
	}
}
