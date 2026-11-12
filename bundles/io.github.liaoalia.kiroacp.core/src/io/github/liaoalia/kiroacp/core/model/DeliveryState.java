package io.github.liaoalia.kiroacp.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Delivery of a user message typed while a turn was running ({@code MessageDelivery} in protocol.ts); absent means delivered normally. */
public enum DeliveryState {
	@JsonProperty("steering")
	STEERING,
	@JsonProperty("queued")
	QUEUED
}
