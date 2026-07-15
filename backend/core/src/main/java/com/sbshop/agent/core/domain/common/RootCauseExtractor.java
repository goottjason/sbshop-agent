package com.sbshop.agent.core.domain.common;

/**
 * 예외 체인의 최심(root) 원인 메시지를 추출하는 유틸리티.
 * wrapping 예외가 원인을 은폐할 때 실제 근본 원인 메시지를 표면화하는 데 사용한다.
 */
public final class RootCauseExtractor {

	private RootCauseExtractor() {}

	/**
	 * 예외 체인을 끝까지 따라가 최심 원인의 메시지를 반환한다.
	 * 순환(getCause()가 자기 자신) 방어. 최심 원인의 메시지가 없으면 null을 반환한다.
	 */
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
