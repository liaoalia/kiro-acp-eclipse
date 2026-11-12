package io.github.liaoalia.kiroacp.core.pure.transcript;

import io.github.liaoalia.kiroacp.core.model.MessageRole;

/**
 * The message that still accepts chunks.
 *
 * @param id		view id of the message (the one {@code chunk} posts carry)
 * @param messageId protocol {@code messageId} of that message, or {@code null} while unknown
 */
public record OpenMessage(String id, MessageRole role, String messageId) {
}
