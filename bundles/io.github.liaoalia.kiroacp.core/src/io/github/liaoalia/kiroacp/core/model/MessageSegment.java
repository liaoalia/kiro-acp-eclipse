package io.github.liaoalia.kiroacp.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({ @JsonSubTypes.Type(value = MessageSegment.Text.class, name = "text"),
		@JsonSubTypes.Type(value = MessageSegment.Resource.class, name = "resource"),
		@JsonSubTypes.Type(value = MessageSegment.Image.class, name = "image") })
public sealed interface MessageSegment {

	record Text(String text) implements MessageSegment {
	}

	record Resource(String uri, String label) implements MessageSegment {
	}

	/** {@code src} is a {@code data:} URL to show; absent when too large or unknown. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record Image(String mimeType, String name, String src) implements MessageSegment {
	}
}
