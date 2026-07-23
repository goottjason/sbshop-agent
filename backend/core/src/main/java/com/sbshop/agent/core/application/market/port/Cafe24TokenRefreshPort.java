package com.sbshop.agent.core.application.market.port;

/**
 * Cafe24 리프레시 토큰을 트래픽과 독립적으로 선제 회전시키는 포트(D-103).
 *
 * <p>Cafe24 OAuth의 리프레시 토큰은 유효기간 2주이며 refresh 호출 때마다 새 토큰으로
 * 회전·연장된다. 토큰 갱신이 주문 동기화 트래픽에만 의존하면(온디맨드) 2주 이상 API 호출
 * 공백이 생길 때 리프레시 토큰이 만료되어 재인증 외 복구가 불가능해진다. 스케줄러가 이
 * 포트를 주기적으로 호출해 시한을 항상 갱신한다.
 */
public interface Cafe24TokenRefreshPort {

	/**
	 * 리프레시 토큰이 있으면 access token 유효 여부와 무관하게 refresh를 강제해 토큰을 회전시킨다.
	 * 리프레시 토큰이 없으면(재인증 필요) 조용히 건너뛰고, refresh 실패 시 예외를 삼켜
	 * 호출한 스케줄러가 중단되지 않도록 한다.
	 */
	void refreshProactively();
}
