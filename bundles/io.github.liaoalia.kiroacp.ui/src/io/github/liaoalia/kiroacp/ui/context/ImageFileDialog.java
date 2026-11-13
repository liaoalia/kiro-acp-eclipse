package io.github.liaoalia.kiroacp.ui.context;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import io.github.liaoalia.kiroacp.core.session.context.ImagePicker;

/**
 * The composer's attach button as the platform's file dialog, for several
 * files at once.
 *
 * <p>The dialog is opened from an event of its own on the UI thread, so the
 * chat's handler has returned by then; it is modal for the window it comes
 * up over. Without a window to come up over, or once the display is gone,
 * nothing is chosen.
 */
public final class ImageFileDialog implements ImagePicker {

	private final Display display;

	public ImageFileDialog(Display display) {
		this.display = Objects.requireNonNull(display, "display");
	}

	@Override
	public CompletionStage<List<Path>> pickImages(List<String> extensions) {
		CompletableFuture<List<Path>> chosen = new CompletableFuture<>();
		if (display.isDisposed()) {
			chosen.complete(List.of());
			return chosen;
		}
		try {
			display.asyncExec(() -> {
				try {
					chosen.complete(open(extensions));
				} catch (RuntimeException e) {
					chosen.completeExceptionally(e);
				}
			});
		} catch (SWTException disposedMeanwhile) {
			chosen.complete(List.of());
		}
		return chosen;
	}

	private List<Path> open(List<String> extensions) {
		Shell parent = parentShell();
		if (parent == null || parent.isDisposed()) {
			return List.of();
		}
		FileDialog dialog = new FileDialog(parent, SWT.OPEN | SWT.MULTI);
		dialog.setText("Attach Images");
		dialog.setFilterNames(new String[] { filterName(extensions) });
		dialog.setFilterExtensions(new String[] { filterPattern(extensions) });
		if (dialog.open() == null) {
			return List.of();
		}
		return files(dialog.getFilterPath(), dialog.getFileNames());
	}

	/** {@code Images (*.png, *.jpg)}. */
	static String filterName(List<String> extensions) {
		return "Images (" + extensions.stream().map(extension -> "*." + extension).collect(Collectors.joining(", ")) + ")";
	}

	/** {@code *.png;*.jpg}: one filter that shows every accepted type. */
	static String filterPattern(List<String> extensions) {
		return extensions.stream().map(extension -> "*." + extension).collect(Collectors.joining(";"));
	}

	/** The dialog names the folder once and the files by name; a name it gives in full is taken as it is. */
	static List<Path> files(String folder, String[] names) {
		List<Path> files = new ArrayList<>();
		for (String name : names == null ? new String[0] : names) {
			try {
				Path file = Path.of(name);
				files.add(file.isAbsolute() || folder == null || folder.isEmpty() ? file : Path.of(folder).resolve(file));
			} catch (InvalidPathException e) {
				// Not a file this platform can name: there is nothing to read.
			}
		}
		return files;
	}

	/** The window the user works in, so the dialog comes up over it. */
	private Shell parentShell() {
		if (PlatformUI.isWorkbenchRunning()) {
			IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
			if (window != null) {
				return window.getShell();
			}
		}
		return display.getActiveShell();
	}
}
