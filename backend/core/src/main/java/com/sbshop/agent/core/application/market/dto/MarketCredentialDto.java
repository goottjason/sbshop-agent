package com.sbshop.agent.core.application.market.dto;

import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import lombok.Data;

@Data
public class MarketCredentialDto {
	private Long id;
	private MarketType marketType;
	private String clientId;
	private String redirectUri;
	// 관리자 전용 화면 요구(2026-07-24 사용자 지시): 설정 페이지에서 저장된 시크릿을 직접 확인·수정할 수
	// 있도록 accessKey·secretKey·refreshToken 평문을 응답에 담는다. (F-CRED-1·7의 마스킹 정책을 관리자
	// 편의를 위해 되돌림 — 이 엔드포인트는 인증 뒤에 있어야 안전함에 유의.)
	private String accessKey;
	private String secretKey;
	private String refreshToken;
	// 하위호환: 프론트가 '설정 여부'만 참조하던 기존 로직도 계속 동작하도록 불리언 유지.
	private boolean hasAccessKey;
	private boolean hasSecretKey;
	private boolean hasRefreshToken;

	public static MarketCredentialDto fromEntity(MarketCredential credential) {
		MarketCredentialDto dto = new MarketCredentialDto();
		dto.setId(credential.getId());
		dto.setMarketType(credential.getMarketType());
		// clientId·redirectUri는 식별자/리다이렉트로 민감도가 낮고 프론트 OAuth 조립에 필요 — 평문 유지.
		dto.setClientId(credential.getClientId());
		dto.setRedirectUri(credential.getRedirectUri());
		dto.setAccessKey(credential.getAccessKey());
		dto.setSecretKey(credential.getSecretKey());
		dto.setRefreshToken(credential.getRefreshToken());
		dto.setHasAccessKey(isPresent(credential.getAccessKey()));
		dto.setHasSecretKey(isPresent(credential.getSecretKey()));
		dto.setHasRefreshToken(isPresent(credential.getRefreshToken()));
		return dto;
	}

	private static boolean isPresent(String value) {
		return value != null && !value.isBlank();
	}
}
