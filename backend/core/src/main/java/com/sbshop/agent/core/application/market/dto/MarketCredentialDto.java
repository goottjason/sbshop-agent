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
	// 시크릿 평문(accessKey·secretKey·refreshToken)을 응답에 담는다. 이 DTO를 내려주는
	// /api/v1/market-credentials/** 엔드포인트는 SecurityConfig에서 관리자 HTTP Basic 인증으로
	// 보호되므로(F-CRED-1·7의 무인증 노출 결함이 인증으로 차단됨) 설정 화면에서 저장값을 확인·수정할 수 있다.
	private String accessKey;
	private String secretKey;
	private String refreshToken;
	// 하위호환: '설정 여부'만 참조하던 기존 로직 유지.
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
