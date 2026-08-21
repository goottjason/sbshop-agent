package com.sbshop.agent.core.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * F-MISC-17·7: 내부/무인증 트리거 엔드포인트용 공유시크릿 헤더 가드.
 *
 * <p>이 프로젝트는 인증 프레임워크(Spring Security 등)가 없고 CORS가 개방(*)이라,
 * "포트 미노출" 관례에만 의존하던 내부 트리거(EmailFetchController, ProductSyncController)를
 * 최소한의 공유시크릿으로 보호한다.
 *
 * <p>동작:
 * <ul>
 *   <li>{@code INTERNAL_API_TOKEN} env(프로퍼티) 미설정(빈 값) → 가드 <b>비활성</b>:
 *       모든 요청 통과(현재 동작 유지, 무파손). 옵트인 성격.</li>
 *   <li>토큰 설정 시 → 요청 헤더 값이 시크릿과 정확히 일치할 때만 통과.</li>
 * </ul>
 *
 * <p>servlet 의존을 두지 않기 위해 헤더 추출은 각 컨트롤러가 수행하고
 * 여기서는 순수 문자열 비교만 한다(모듈 경계 유지 — core는 web starter 미포함).
 */
@Component
public class InternalAccessGuard {

	/** 공유시크릿 헤더 이름(관례) — 각 컨트롤러의 {@code @RequestHeader} 에서 참조. */
	public static final String HEADER_NAME = "X-Internal-Token";

	private final String configuredToken;

	public InternalAccessGuard(@Value("${INTERNAL_API_TOKEN:}")
	String configuredToken) {
		this.configuredToken = configuredToken == null ? "" : configuredToken.trim();
	}

	/** 토큰이 설정되어 가드가 강제되는 상태인지 여부. */
	public boolean isEnabled() {
		return !configuredToken.isEmpty();
	}

	/**
	 * 요청 헤더로 전달된 토큰이 접근 허용되는지 판정한다.
	 * 가드 비활성(토큰 미설정)이면 항상 true.
	 */
	public boolean isAllowed(String providedToken) {
		if (!isEnabled()) {
			return true;
		}
		return configuredToken.equals(providedToken);
	}
}
