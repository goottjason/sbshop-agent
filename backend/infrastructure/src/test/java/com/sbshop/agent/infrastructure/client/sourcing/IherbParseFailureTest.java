package com.sbshop.agent.infrastructure.client.sourcing;

import com.sbshop.agent.core.application.product.dto.StockCheckResult;
import com.sbshop.agent.core.domain.product.enums.StockStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IherbParseFailureTest {

	private final IherbScraperClient client = new IherbScraperClient(new com.fasterxml.jackson.databind.ObjectMapper());

	@Test
	@DisplayName("D-289: 응답이 JSON 이 아니면 품절로 단정하지 않고 예외를 던진다 — 모르면 모른다")
	void malformedBody_throwsInsteadOfAssertingOutOfStock() {
		assertThatThrownBy(() -> client.parseResponse("<html>Service Unavailable</html>"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("판정 불가");
	}

	@Test
	@DisplayName("D-289: 빈 응답도 품절이 아니다 — Jackson 은 예외를 안 던지므로 우리가 막아야 한다")
	void emptyBody_throws() {
		assertThatThrownBy(() -> client.parseResponse(""))
			.isInstanceOf(IllegalStateException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = {"{}", "{\"isAvailableToPurchase\":null}",
		"{\"isAvailableToPurchase\":\"true\"}", "{\"isAvailableToPurchase\":\"false\"}",
		"{\"isAvailableToPurchase\":0}", "{\"isAvailableToPurchase\":1}",
		"{\"isAvailableToPurchase\":[]}", "{\"isAvailableToPurchase\":{}}"})
	@DisplayName("판매 가능 여부 누락과 잘못된 타입은 품절이나 정상 재고로 변환하지 않는다")
	void missingOrNonBooleanAvailability_throws(String body) {
		assertThatThrownBy(() -> client.parseResponse(body))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("판정 불가")
			.hasRootCauseMessage("판매 가능 여부(isAvailableToPurchase)가 boolean이 아니다");
	}

	@ParameterizedTest
	@ValueSource(strings = {"-1", "1.5", "1.0", "\"7\"", "true", "[]", "{}", "2147483648",
		"9223372036854775808"})
	@DisplayName("명시된 재고 수량이 정수 범위를 벗어나거나 잘못된 타입이면 수집에 실패한다")
	void invalidStockQuantity_throws(String stock) {
		assertThatThrownBy(() -> client.parseResponse(
			"{\"isAvailableToPurchase\":true,\"stockQuantity\":" + stock + "}"))
			.isInstanceOf(IllegalStateException.class)
			.hasRootCauseMessage("재고 수량(stockQuantity)이 유효한 비음수 정수가 아니다");
	}

	@Test
	@DisplayName("D-289: 정상 응답은 그대로 판정한다 — 재고 있는 상품")
	void validBody_parsesInStock() {
		StockCheckResult result = client.parseResponse(
			"{\"isAvailableToPurchase\":true,\"stockQuantity\":7,\"listPriceAmount\":1234}");

		assertThat(result.status()).isEqualTo(StockStatus.IN_STOCK);
		assertThat(result.stock()).isEqualTo(7);
	}

	@Test
	@DisplayName("D-289: 정상 응답의 품절은 그대로 품절이다 — 진짜 품절까지 막지 않는다")
	void validBody_parsesOutOfStock() {
		StockCheckResult result = client.parseResponse(
			"{\"isAvailableToPurchase\":false,\"stockQuantity\":0}");

		assertThat(result.status()).isEqualTo(StockStatus.OUT_OF_STOCK);
		assertThat(result.stock()).isZero();
	}
}
