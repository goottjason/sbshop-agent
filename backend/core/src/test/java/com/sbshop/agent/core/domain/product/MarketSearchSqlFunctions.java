package com.sbshop.agent.core.domain.product;

import com.fasterxml.jackson.databind.ObjectMapper;

/** H2 equivalent of the PostgreSQL function; its SQL implementation has a separate smoke check. */
public final class MarketSearchSqlFunctions {
	private MarketSearchSqlFunctions() {}

	public static boolean hasIdentifier(String document, String key) {
		try {
			var value = new ObjectMapper().readTree(document).path(key);
			return (value.isTextual() || value.isNumber()) && !value.asText().trim().isEmpty();
		} catch (Exception ignored) {
			return false;
		}
	}

	public static boolean identifierEquals(String document, String key, String expected) {
		try {
			var value = new ObjectMapper().readTree(document).path(key);
			return expected != null && !expected.isEmpty() && (value.isTextual() || value.isNumber())
				&& value.asText().equals(expected);
		} catch (Exception ignored) {
			return false;
		}
	}
}
