package io.github.liaoalia.kiroacp.ui.services;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

/**
 * {@link Prompts} as JFace dialogs.
 *
 * <p>A question is a modal dialog, opened from an event of its own on the UI
 * thread, so the asking thread is never kept and the UI thread is blocked only
 * the way any modal dialog blocks it (its event loop keeps running). A notice
 * is a modeless dialog: plain JFace has no notification that stays without
 * taking the window.
 *
 * <p>Once the display is gone every question is refused and every notice
 * counts as never acknowledged.
 */
public final class DialogPrompts implements Prompts {

	private final Display display;

	public DialogPrompts(Display display) {
		this.display = Objects.requireNonNull(display, "display");
	}

	@Override
	public CompletionStage<Boolean> ask(Question question) {
		CompletableFuture<Boolean> answer = new CompletableFuture<>();
		boolean queued = async(() -> {
			try {
				String text = question.detail() == null ? question.message()
						: question.message() + "\n\n" + question.detail();
				MessageDialog dialog = new MessageDialog(parentShell(), question.title(), null, text,
						question.warning() ? MessageDialog.WARNING : MessageDialog.QUESTION, question.warning() ? 1 : 0,
						question.accept(), question.refuse());
				answer.complete(dialog.open() == 0);
			} catch (RuntimeException e) {
				answer.completeExceptionally(e);
			}
		});
		if (!queued) {
			answer.complete(Boolean.FALSE);
		}
		return answer;
	}

	@Override
	public Notice tell(String title, String message, String done) {
		DialogNotice notice = new DialogNotice();
		async(() -> notice.open(title, message, done));
		return notice;
	}

	private final class DialogNotice implements Notice {

		private final CompletableFuture<Void> acknowledged = new CompletableFuture<>();
		// UI thread only.
		private MessageDialog dialog;
		private boolean dismissed;

		void open(String title, String message, String done) {
			if (dismissed) {
				return;
			}
			dialog = new MessageDialog(parentShell(), title, null, message, MessageDialog.INFORMATION, 0, done) {
				{
					setShellStyle(SWT.CLOSE | SWT.MODELESS | SWT.BORDER | SWT.TITLE);
					setBlockOnOpen(false);
				}

				@Override
				public boolean close() {
					boolean closed = super.close();
					// The button and the window's close box mean the same: whoever waits
					// must not wait for ever because the message was closed the other way.
					if (closed && !dismissed) {
						acknowledged.complete(null);
					}
					return closed;
				}
			};
			dialog.open();
		}

		@Override
		public CompletionStage<Void> acknowledged() {
			return acknowledged;
		}

		@Override
		public void dismiss() {
			async(() -> {
				dismissed = true;
				if (dialog != null) {
					dialog.close();
				}
			});
		}
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

	/** @return whether the work was handed to the UI thread */
	private boolean async(Runnable work) {
		if (display.isDisposed()) {
			return false;
		}
		try {
			display.asyncExec(work);
			return true;
		} catch (SWTException disposedMeanwhile) {
			return false;
		}
	}
}
