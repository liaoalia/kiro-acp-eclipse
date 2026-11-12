package io.github.liaoalia.kiroacp.core.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record MessageView(String id, MessageRole role, List<MessageSegment> segments, DeliveryState delivery) {
}
