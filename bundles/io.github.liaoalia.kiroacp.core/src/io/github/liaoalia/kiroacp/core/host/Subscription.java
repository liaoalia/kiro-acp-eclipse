package io.github.liaoalia.kiroacp.core.host;

/** Undoes a registration. Closing twice is harmless. */
@FunctionalInterface
public interface Subscription extends AutoCloseable {

	@Override
	void close();
}
