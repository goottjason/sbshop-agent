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
	// F-CRED-1·7: 시크릿(accessKey·secretKey·refreshToken) 평문은 응답에 담지 않는다.
	// 무인증 API로 전 마켓 시크릿이 노출되던 결함 — refreshToken의 hasRefreshToken 패턴을 확장해
	// '설정 여부'만 불리언으로 노출한다. 평문 필드(accessKey·secretKey)는 응답에서 제거.
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
		dto.setHasAccessKey(isPresent(credential.getAccessKey()));
		dto.setHasSecretKey(isPresent(credential.getSecretKey()));
		dto.setHasRefreshToken(isPresent(credential.getRefreshToken()));
		return dto;
	}

	private static boolean isPresent(String value) {
		return value != null && !value.isBlank();
	}
}
