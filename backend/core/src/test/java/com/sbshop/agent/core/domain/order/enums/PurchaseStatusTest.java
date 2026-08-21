package com.sbshop.agent.core.domain.order.enums;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class PurchaseStatusTest {
	@Test
	void 세_가지_값이_존재한다() {
		assertThat(PurchaseStatus.values()).hasSize(3);
		assertThat(PurchaseStatus.valueOf("NOT_PURCHASED")).isEqualTo(PurchaseStatus.NOT_PURCHASED);
		assertThat(PurchaseStatus.valueOf("PURCHASED")).isEqualTo(PurchaseStatus.PURCHASED);
		assertThat(PurchaseStatus.valueOf("WAITING_STOCK")).isEqualTo(PurchaseStatus.WAITING_STOCK);
	}

	@Test
	void label_확인() {
		assertThat(PurchaseStatus.NOT_PURCHASED.getLabel()).isEqualTo("미구매");
		assertThat(PurchaseStatus.PURCHASED.getLabel()).isEqualTo("구매완료");
		assertThat(PurchaseStatus.WAITING_STOCK.getLabel()).isEqualTo("입고대기");
	}
}
