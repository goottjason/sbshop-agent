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
	private String accessKey;
	private String secretKey;
	private String refreshToken;
	private boolean hasAccessKey;
	private boolean hasSecretKey;
	private boolean hasRefreshToken;

	public static MarketCredentialDto fromEntity(MarketCredential credential) {
		MarketCredentialDto dto = new MarketCredentialDto();
		dto.setId(credential.getId());
		dto.setMarketType(credential.getMarketType());
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
