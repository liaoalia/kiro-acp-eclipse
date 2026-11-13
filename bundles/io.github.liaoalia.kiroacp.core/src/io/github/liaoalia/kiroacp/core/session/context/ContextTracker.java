package io.github.liaoalia.kiroacp.core.session.context;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

import io.github.liaoalia.kiroacp.core.model.ContextChip;
import io.github.liaoalia.kiroacp.core.session.context.EditorContext.TextSelection;

/**
 * The context chips the host owns: the implicit chip of the active editor's
 * file and the explicit ones (selections, dropped files). The reference's
 * {@code ContextTracker} without its editor listeners, which
 * {@link ChatContext} feeds in. The chips of {@code @} mentions are the
 * page's own until they arrive with a prompt.
 *
 * <p>One thread only (the controller's).
 */
public final class ContextTracker {

	private final Function<Path, String> label;
	private final BooleanSupplier attachActiveFile;
	private final Consumer<List<ContextChip>> onChange;
	private final Map<String, ContextChip> explicit = new LinkedHashMap<>();
	private ContextChip implicit;
	private boolean implicitRemoved;

	/**
	 * @param label            the label of a file: its path relative to the workspace
	 * @param attachActiveFile the setting: whether the active editor's file is a chip at all
	 * @param onChange         gets the full chip list after any change
	 */
	public ContextTracker(Function<Path, String> label, BooleanSupplier attachActiveFile, Consumer<List<ContextChip>> onChange) {
		this.label = label;
		this.attachActiveFile = attachActiveFile;
		this.onChange = onChange;
	}

	public static String fileChipId(String absPath) {
		return "file:" + absPath;
	}

	public static String selectionChipId(String absPath, int start, int end) {
		return "sel:" + absPath + ":" + start + "-" + end;
	}

	/**
	 * The 1-based, inclusive lines a selection stands for: the caret's line
	 * when nothing is selected; a selection ending at the start of a line
	 * does not include that line.
	 *
	 * @return {@code [start, end]}
	 */
	public static int[] selectionLines(TextSelection selection) {
		if (selection.isEmpty()) {
			return new int[] { selection.startLine() + 1, selection.startLine() + 1 };
		}
		int start = selection.startLine() + 1;
		int end = selection.endLine() + 1;
		if (selection.endColumn() == 0 && end > start) {
			end -= 1;
		}
		return new int[] { start, end };
	}

	/** Current chips: the implicit active-file chip (if any) followed by the explicit ones. */
	public List<ContextChip> chips() {
		List<ContextChip> out = new ArrayList<>();
		if (implicit != null && !implicitRemoved && attachActiveFile.getAsBoolean()) {
			boolean duplicate = explicit.values().stream()
					.anyMatch(chip -> chip.kind() == ContextChip.Kind.FILE && chip.path().equals(implicit.path()));
			if (!duplicate) {
				out.add(implicit);
			}
		}
		out.addAll(explicit.values());
		return List.copyOf(out);
	}

	/**
	 * Remove a chip; the implicit chip stays removed until the next editor
	 * change or prompt.
	 *
	 * <p>An explicit chip of the active editor's file has the id of the
	 * implicit one and is the one on show ({@link #chips()}), so it is the one
	 * removed. The reference looks at the implicit chip first and then leaves
	 * the explicit one where it is: a file dropped on the composer while it is
	 * open in the active editor cannot be removed there.
	 */
	public void removeChip(String id) {
		if (explicit.remove(id) == null) {
			if (implicit == null || !implicit.id().equals(id) || implicitRemoved) {
				return;
			}
			implicitRemoved = true;
		}
		fire();
	}

	/** Add an explicit file chip. */
	public ContextChip addFileChip(Path file) {
		String path = file.toString();
		ContextChip chip = new ContextChip(fileChipId(path), ContextChip.Kind.FILE, path, label.apply(file), null, null, false);
		explicit.put(chip.id(), chip);
		fire();
		return chip;
	}

	/**
	 * Add a chip for a selection (the whole current line when it is empty).
	 *
	 * @return the chip, or {@code null} when there is no selection because there is no editor of a file
	 */
	public ContextChip addSelectionChip(TextSelection selection) {
		if (selection == null || selection.file() == null) {
			return null;
		}
		int[] lines = selectionLines(selection);
		String path = selection.file().toString();
		ContextChip chip = new ContextChip(selectionChipId(path, lines[0], lines[1]), ContextChip.Kind.SELECTION, path,
				label.apply(selection.file()), lines[0], lines[1], false);
		explicit.put(chip.id(), chip);
		fire();
		return chip;
	}

	/** After a prompt: drop the explicit chips, attach the implicit one again. */
	public void clearExplicit() {
		explicit.clear();
		implicitRemoved = false;
		fire();
	}

	/**
	 * Another editor is the active one.
	 *
	 * @param file its file; {@code null} (not an editor of a file) keeps the previous chip
	 */
	public void activeFileChanged(Path file) {
		if (file == null) {
			return;
		}
		String path = file.toString();
		if (implicit != null && implicit.path().equals(path) && !implicitRemoved) {
			return;
		}
		implicit = new ContextChip(fileChipId(path), ContextChip.Kind.FILE, path, label.apply(file), null, null, true);
		implicitRemoved = false;
		fire();
	}

	/** Something the chips depend on changed elsewhere (the setting): tell the listener the list again. */
	public void fire() {
		onChange.accept(chips());
	}
}
