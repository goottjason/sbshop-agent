package com.sbshop.agent.infrastructure.client.sourcing;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IherbStockUnknownTest {

	private final IherbScraperClient client = new IherbScraperClient(new com.fasterxml.jackson.databind.ObjectMapper());

	@Test
	@DisplayName("D-239: 아이허브 URL 이 아니면 품절로 단정하지 않고 실패한다 — 모르는 것을 없다고 기록하지 않는다")
	void nonIherbUrl_throwsInsteadOfDeclaringOutOfStock() {
		assertThatThrownBy(() -> client.checkStockWithDetails(
			"https://www.costco.co.uk/Grocery-Household/Tea-Coffee/p/12345"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("재고 판정 불가");
	}

	@Test
	@DisplayName("D-239: 빈 URL 도 품절이 아니라 판정 불가다")
	void blankUrl_throws() {
		assertThatThrownBy(() -> client.checkStockWithDetails(""))
			.isInstanceOf(IllegalStateException.class);
	}
}
