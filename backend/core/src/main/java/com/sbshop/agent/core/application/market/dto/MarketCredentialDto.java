package com.sbshop.agent.core.application.market.dto;

import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import lombok.Data;

@Data
public class MarketCredentialDto {
	private Long id;
	private MarketType marketType;
	private String clientId;
	private String accessKey;
	private String secretKey;
	private String redirectUri;
	private boolean hasRefreshToken;

	public static MarketCredentialDto fromEntity(MarketCredential credential) {
		MarketCredentialDto dto = new MarketCredentialDto();
		dto.setId(credential.getId());
		dto.setMarketType(credential.getMarketType());
		dto.setClientId(credential.getClientId());
		dto.setAccessKey(credential.getAccessKey());
		dto.setSecretKey(credential.getSecretKey());
		dto.setRedirectUri(credential.getRedirectUri());
		dto.setHasRefreshToken(
			credential.getRefreshToken() != null && !credential.getRefreshToken().isBlank());
		return dto;
	}
}
