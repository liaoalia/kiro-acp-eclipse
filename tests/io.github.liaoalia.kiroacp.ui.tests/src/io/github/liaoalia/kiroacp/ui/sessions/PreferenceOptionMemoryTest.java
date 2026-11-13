package io.github.liaoalia.kiroacp.ui.sessions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.liaoalia.kiroacp.core.host.Logger;
import io.github.liaoalia.kiroacp.core.host.Subscription;
import io.github.liaoalia.kiroacp.core.session.history.RememberedOptions;
import io.github.liaoalia.kiroacp.ui.services.PreferenceSettings;

/** The remembered session options in the real preferences of the workspace, emptied around each test. */
class PreferenceOptionMemoryTest {

	private final IEclipsePreferences bundle = InstanceScope.INSTANCE.getNode(PreferenceSettings.QUALIFIER);
	private final List<String> warnings = new ArrayList<>();
	private final Logger logger = new Logger() {
		@Override
		public void info(String message) {
		}

		@Override
		public synchronized void warn(String message) {
			warnings.add(message);
		}

		@Override
		public void error(String message, Throwable cause) {
		}
	};
	private final PreferenceOptionMemory memory = new PreferenceOptionMemory(logger);

	@BeforeEach
	@AfterEach
	void forgetStoredValues() throws Exception {
		memory.node().clear();
		bundle.clear();
	}

	@Test
	void whatIsWrittenIsReadBackByAnyoneAndForgottenWithNull() {
		assertNull(memory.read(), "nothing is remembered in a new workspace");

		memory.write("{\"modeId\":\"plan\",\"configOptions\":{}}");

		assertEquals("{\"modeId\":\"plan\",\"configOptions\":{}}", memory.read());
		assertEquals("{\"modeId\":\"plan\",\"configOptions\":{}}", new PreferenceOptionMemory(logger).read(),
				"the command's own memory sees what the chat's wrote");
		assertEquals("{\"modeId\":\"plan\",\"configOptions\":{}}",
				bundle.node(PreferenceOptionMemory.NODE).get(PreferenceOptionMemory.KEY, null), "below the bundle's node");

		memory.write(null);

		assertNull(memory.read());
		assertNull(new PreferenceOptionMemory(logger).read());
	}

	@Test
	void theOptionsSurviveBeingWrittenToTheDisk() throws Exception {
		memory.write("{\"modeId\":\"plan\",\"configOptions\":{}}");

		memory.save(memory.node());
		memory.node().sync();

		assertEquals("{\"modeId\":\"plan\",\"configOptions\":{}}", memory.read());
		assertEquals(List.of(), warnings);
	}

	@Test
	void rememberingIsNoChangeOfASettingAndRestoringTheDefaultsForgetsNothing() throws Exception {
		PreferenceSettings settings = new PreferenceSettings();
		AtomicInteger changes = new AtomicInteger();
		try (Subscription listening = settings.onChange(changes::incrementAndGet)) {
			memory.write("{\"modeId\":\"plan\",\"configOptions\":{}}");
			memory.write(null);
			memory.write("{\"modeId\":\"vibe\",\"configOptions\":{}}");
			// A setting does tell: the listener is alive.
			bundle.putBoolean(PreferenceSettings.LOG_PROTOCOL, true);
			assertTrue(changes.get() >= 1, "the listener hears of a setting");
			int afterTheSetting = changes.get();
			memory.write("{\"modeId\":\"plan\",\"configOptions\":{}}");
			assertEquals(afterTheSetting, changes.get(), "but not of what is remembered");
		}

		// "Restore Defaults" empties the bundle's node.
		bundle.clear();

		assertEquals("{\"modeId\":\"plan\",\"configOptions\":{}}", memory.read());
	}

	@Test
	void theForgetCommandsWorkIsDoneOnTheRealMemory() {
		memory.write("{\"modeId\":\"plan\",\"modelId\":\"nova-pro\",\"configOptions\":{\"model\":\"claude-opus-4\",\"autoCompact\":false}}");

		// What ForgetSessionOptionsHandler does before it tells the user.
		RememberedOptions.forget(new PreferenceOptionMemory(logger), logger);

		assertNull(memory.read());
	}
}
