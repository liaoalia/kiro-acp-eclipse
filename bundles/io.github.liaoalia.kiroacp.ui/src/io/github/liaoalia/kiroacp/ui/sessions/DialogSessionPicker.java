package io.github.liaoalia.kiroacp.ui.sessions;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import io.github.liaoalia.kiroacp.core.session.history.PastSessions;
import io.github.liaoalia.kiroacp.core.session.history.PastSessions.Entry;

/**
 * The choice of a past session as a modal JFace dialog, opened from an event
 * of its own on the UI thread: the chat that asked goes on, and the UI thread
 * is kept only the way any modal dialog keeps it. Once the display is gone
 * nothing is chosen.
 */
public final class DialogSessionPicker implements PastSessions.Picker {

	private final Display display;

	public DialogSessionPicker(Display display) {
		this.display = Objects.requireNonNull(display, "display");
	}

	@Override
	public CompletionStage<String> pick(List<Entry> entries) {
		CompletableFuture<String> picked = new CompletableFuture<>();
		Runnable ask = () -> {
			try {
				PastSessionsDialog dialog = new PastSessionsDialog(parentShell(), entries);
				picked.complete(dialog.open() == Window.OK ? dialog.picked() : null);
			} catch (RuntimeException e) {
				picked.completeExceptionally(e);
			}
		};
		try {
			if (display.isDisposed()) {
				picked.complete(null);
			} else {
				display.asyncExec(ask);
			}
		} catch (SWTException disposedMeanwhile) {
			picked.complete(null);
		}
		return picked;
	}

	/** The window the user works in, so the dialog comes up over it; null lets JFace choose. */
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
