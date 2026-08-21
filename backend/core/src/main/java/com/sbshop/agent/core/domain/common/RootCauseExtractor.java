package com.sbshop.agent.core.domain.common;

public final class RootCauseExtractor {

	private RootCauseExtractor() {}

	public static String rootMessage(Throwable throwable) {
		if (throwable == null) {
			return null;
		}
		Throwable root = throwable;
		while (root.getCause() != null && root.getCause() != root) {
			root = root.getCause();
		}
		return root.getMessage();
	}
}
