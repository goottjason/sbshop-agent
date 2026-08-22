package com.sbshop.agent.infrastructure.client.coupang;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.vo.LogisticsInfo;
import com.sbshop.agent.infrastructure.client.coupang.dto.CoupangProductPayload;
import com.sbshop.agent.infrastructure.client.coupang.dto.CoupangProductPayload.Item;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CoupangProductPayloadTest {

	@Test
	@DisplayName("D-194: 1원 단위 판매가는 10원 단위로 내림하고 정상가도 10원 단위로 맞춘다")
	void oddSalePrice_flooredToTenWon() {
		Item item = itemOf(50403);

		assertThat(item.salePrice()).isEqualTo(50400);
		assertThat(item.originalPrice()).isEqualTo(67030);
	}

	@Test
	@DisplayName("D-194: 이미 10원 단위인 판매가는 그대로 두고 정상가만 내림한다")
	void tenWonSalePrice_unchanged() {
		Item item = itemOf(50400);

		assertThat(item.salePrice()).isEqualTo(50400);
		assertThat(item.originalPrice()).isEqualTo(67030);
	}

	@Test
	@DisplayName("D-194: 정상가·판매가 모두 10원으로 나누어떨어진다")
	void bothPrices_divisibleByTenWon() {
		Item item = itemOf(12345);

		assertThat(item.salePrice() % 10).isZero();
		assertThat(item.originalPrice() % 10).isZero();
		assertThat(item.salePrice()).isEqualTo(12340);
	}

	private Item itemOf(int salePrice) {
		CoupangProductPayload payload = CoupangProductPayload.create(
			product(), 73134L, "마스터명", "일반명", "브랜드", salePrice,
			List.of("태그"), List.of(), List.of(), List.of(), "<p>상세</p>");
		return payload.items().get(0);
	}

	private Product product() {
		Product product = mock(Product.class);
		lenient().when(product.getLogisticsInfo())
			.thenReturn(LogisticsInfo.builder().bundleQuantity(3).build());
		lenient().when(product.getSbCode()).thenReturn("SB-0001");
		return product;
	}
}
