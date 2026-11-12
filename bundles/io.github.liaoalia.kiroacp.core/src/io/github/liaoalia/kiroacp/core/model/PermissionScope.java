package io.github.liaoalia.kiroacp.core.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** What a client-side "always" policy is keyed by. */
public enum PermissionScope {
	@JsonProperty("kind")
	KIND,
	@JsonProperty("capability")
	CAPABILITY,
	@JsonProperty("resource")
	RESOURCE
}
