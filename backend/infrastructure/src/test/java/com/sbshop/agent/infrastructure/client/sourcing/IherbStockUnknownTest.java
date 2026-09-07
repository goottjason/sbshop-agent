package com.sbshop.agent.infrastructure.client.sourcing;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.application.product.dto.StockCheckResult;
import com.sbshop.agent.core.domain.product.enums.StockStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	@DisplayName("수량이 없거나 null이면 판매 가능 여부만 판정하고 임의의 0개나 100개를 만들지 않는다")
	void absentStockRemainsUnknown(boolean available) {
		for (String stockField : new String[] {"", ",\"stockQuantity\":null"}) {
			StockCheckResult result = client.parseResponse(
				"{\"isAvailableToPurchase\":" + available + stockField + "}");
			assertThat(result.status()).isEqualTo(available ? StockStatus.IN_STOCK : StockStatus.OUT_OF_STOCK);
			assertThat(result.stock()).isNull();
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	@DisplayName("명시된 재고 0은 판매 가능 여부와 별도로 그대로 보존한다")
	void explicitZeroStockRemainsZero(boolean available) {
		StockCheckResult result = client.parseResponse(
			"{\"isAvailableToPurchase\":" + available + ",\"stockQuantity\":0}");
		assertThat(result.status()).isEqualTo(available ? StockStatus.IN_STOCK : StockStatus.OUT_OF_STOCK);
		assertThat(result.stock()).isZero();
	}

	@Test
	@DisplayName("재고 정수의 최댓값은 잘림 없이 보존한다")
	void maximumIntegerStockIsPreserved() {
		StockCheckResult result = client.parseResponse(
			"{\"isAvailableToPurchase\":true,\"stockQuantity\":2147483647}");
		assertThat(result.stock()).isEqualTo(Integer.MAX_VALUE);
	}
}
