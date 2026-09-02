package com.sbshop.agent.core.application.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sbshop.agent.core.application.order.dto.MarketLineItemDto;
import com.sbshop.agent.core.domain.order.enums.ClaimStage;
import com.sbshop.agent.core.domain.order.enums.ClaimType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;

class Cafe24ClaimAppliedTest {

	@Test
	@DisplayName("교환 코드는 DTO 에 클레임으로 실린다 — 배송 단계는 비어 온다")
	void exchangeCodeCarriesClaim() {
		MarketLineItemDto dto = MarketLineItemDto.builder()
			.status(Cafe24LineItemMapper.mapStatus("E40"))
			.claim(Cafe24LineItemMapper.mapClaim("E40"))
			.build();

		assertThat(dto.getStatus()).isEqualTo(ShippingStatus.UNKNOWN);
		assertThat(dto.getClaim().getClaimType()).isEqualTo(ClaimType.EXCHANGE);
		assertThat(dto.getClaim().getClaimStage()).isEqualTo(ClaimStage.DONE);
	}

	@Test
	@DisplayName("배송 코드는 클레임 없이 배송 단계만 싣는다")
	void deliveryCodeCarriesStageOnly() {
		MarketLineItemDto dto = MarketLineItemDto.builder()
			.status(Cafe24LineItemMapper.mapStatus("N30"))
			.claim(Cafe24LineItemMapper.mapClaim("N30"))
			.build();

		assertThat(dto.getStatus()).isEqualTo(ShippingStatus.SHIPPED);
		assertThat(dto.getClaim().getClaimType()).isEqualTo(ClaimType.NONE);
	}
}
