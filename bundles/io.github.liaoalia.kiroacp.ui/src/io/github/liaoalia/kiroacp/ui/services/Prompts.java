package io.github.liaoalia.kiroacp.ui.services;

import java.util.concurrent.CompletionStage;

/**
 * Everything the host adapters ask the user: the one place a dialog comes
 * from, so tests answer in its stead and never show one.
 *
 * <p>Both methods are callable from any thread and return at once; the stages
 * complete later, on an unspecified thread ({@link DialogPrompts} completes
 * them on the UI thread).
 */
public interface Prompts {

	/**
	 * A question with two answers.
	 *
	 * @param message the question itself
	 * @param detail  shown below the message; {@code null} for none
	 * @param accept  label of the answer that lets the action happen
	 * @param refuse  label of the other answer
	 * @param warning whether agreeing is the risky answer: the question is then shown as a warning
	 *                and {@code refuse} is the default
	 */
	record Question(String title, String message, String detail, String accept, String refuse, boolean warning) {
	}

	/** @return completes with {@code true} when the user chose {@link Question#accept}; closing the question is a refusal */
	CompletionStage<Boolean> ask(Question question);

	/**
	 * Tell the user something that stays in view without blocking the IDE until
	 * they acknowledge it or it is withdrawn.
	 *
	 * @param done label of the acknowledging button
	 */
	Notice tell(String title, String message, String done);

	/** A message on show. */
	interface Notice {

		/** Completes when the user acknowledged or closed the message; never when it was {@link #dismiss() withdrawn}. */
		CompletionStage<Void> acknowledged();

		/** Takes the message away. Callable from any thread, more than once. */
		void dismiss();
	}
}
