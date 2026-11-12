package io.github.liaoalia.kiroacp.core.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Chat page to host: {@code FromWebview} in the reference's protocol.ts.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({ @JsonSubTypes.Type(value = FromWebview.Ready.class, name = "ready"),
		@JsonSubTypes.Type(value = FromWebview.Prompt.class, name = "prompt"),
		@JsonSubTypes.Type(value = FromWebview.Cancel.class, name = "cancel"),
		@JsonSubTypes.Type(value = FromWebview.PickImages.class, name = "pickImages"),
		@JsonSubTypes.Type(value = FromWebview.DropFiles.class, name = "dropFiles"),
		@JsonSubTypes.Type(value = FromWebview.NewSession.class, name = "newSession"),
		@JsonSubTypes.Type(value = FromWebview.SelectSession.class, name = "selectSession"),
		@JsonSubTypes.Type(value = FromWebview.CloseSession.class, name = "closeSession"),
		@JsonSubTypes.Type(value = FromWebview.BrowseSessions.class, name = "browseSessions"),
		@JsonSubTypes.Type(value = FromWebview.Restart.class, name = "restart"),
		@JsonSubTypes.Type(value = FromWebview.Login.class, name = "login"),
		@JsonSubTypes.Type(value = FromWebview.PermissionResponse.class, name = "permissionResponse"),
		@JsonSubTypes.Type(value = FromWebview.PermissionAlways.class, name = "permissionAlways"),
		@JsonSubTypes.Type(value = FromWebview.SetMode.class, name = "setMode"),
		@JsonSubTypes.Type(value = FromWebview.SetConfigOption.class, name = "setConfigOption"),
		@JsonSubTypes.Type(value = FromWebview.SetModel.class, name = "setModel"),
		@JsonSubTypes.Type(value = FromWebview.QueryFiles.class, name = "queryFiles"),
		@JsonSubTypes.Type(value = FromWebview.RemoveChip.class, name = "removeChip"),
		@JsonSubTypes.Type(value = FromWebview.OpenFile.class, name = "openFile"),
		@JsonSubTypes.Type(value = FromWebview.OpenDiff.class, name = "openDiff"),
		@JsonSubTypes.Type(value = FromWebview.ShowTerminal.class, name = "showTerminal"),
		@JsonSubTypes.Type(value = FromWebview.OpenPlan.class, name = "openPlan"),
		@JsonSubTypes.Type(value = FromWebview.ShowLog.class, name = "showLog") })
public sealed interface FromWebview {

	record Ready() implements FromWebview {
	}

	/** {@code images} are sent as {@code image} blocks after the text. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record Prompt(String text, List<ContextChip> chips, List<ImageAttachment> images) implements FromWebview {
	}

	record Cancel() implements FromWebview {
	}

	/** Attach button: the host opens a file dialog and answers with {@code imagesAttached}. */
	record PickImages() implements FromWebview {
	}

	/** URIs dropped on the composer. */
	record DropFiles(List<String> uris) implements FromWebview {
	}

	record NewSession() implements FromWebview {
	}

	record SelectSession(String sessionId) implements FromWebview {
	}

	record CloseSession(String sessionId) implements FromWebview {
	}

	/** Open the host's picker of past sessions. */
	record BrowseSessions() implements FromWebview {
	}

	record Restart() implements FromWebview {
	}

	record Login(String methodId) implements FromWebview {
	}

	record PermissionResponse(String requestId, String optionId) implements FromWebview {
	}

	/** Remember this choice for the rest of the session; {@code scope} defaults to the tool kind. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record PermissionAlways(String requestId, Decision decision, PermissionScope scope) implements FromWebview {
	}

	record SetMode(String modeId) implements FromWebview {
	}

	/** {@code value} is a string or a boolean. */
	record SetConfigOption(String configId, JsonNode value) implements FromWebview {
	}

	record SetModel(String modelId) implements FromWebview {
	}

	record QueryFiles(String query) implements FromWebview {
	}

	record RemoveChip(String id) implements FromWebview {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record OpenFile(String path, Integer line) implements FromWebview {
	}

	record OpenDiff(String toolCallId, int index) implements FromWebview {
	}

	record ShowTerminal(String terminalId) implements FromWebview {
	}

	record OpenPlan() implements FromWebview {
	}

	record ShowLog() implements FromWebview {
	}

	enum Decision {
		@JsonProperty("allow")
		ALLOW,
		@JsonProperty("reject")
		REJECT
	}
}
