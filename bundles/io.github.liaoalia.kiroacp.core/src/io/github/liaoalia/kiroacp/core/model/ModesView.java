package io.github.liaoalia.kiroacp.core.model;

import java.util.List;

/** The {@code modes} of a session: {@code UiState["modes"]} in protocol.ts. */
public record ModesView(String currentModeId, List<ModeView> availableModes) {
}
