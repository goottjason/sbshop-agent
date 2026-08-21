package com.sbshop.agent.core.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class InternalAccessGuard {

	public static final String HEADER_NAME = "X-Internal-Token";

	private final String configuredToken;

	public InternalAccessGuard(@Value("${INTERNAL_API_TOKEN:}")
	String configuredToken) {
		this.configuredToken = configuredToken == null ? "" : configuredToken.trim();
	}

	public boolean isEnabled() {
		return !configuredToken.isEmpty();
	}

	public boolean isAllowed(String providedToken) {
		if (!isEnabled()) {
			return true;
		}
		return configuredToken.equals(providedToken);
	}
}
