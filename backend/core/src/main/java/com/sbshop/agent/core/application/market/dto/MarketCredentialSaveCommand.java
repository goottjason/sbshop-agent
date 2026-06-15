package com.sbshop.agent.core.application.market.dto;

import com.sbshop.agent.core.domain.order.enums.MarketType;
import lombok.Data;

@Data
public class MarketCredentialSaveCommand {
	private MarketType marketType;
	private String clientId;
	private String accessKey;
	private String secretKey;
	private String redirectUri;
}
