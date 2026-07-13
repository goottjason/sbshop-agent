package com.sbshop.agent.core.domain.order.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ShippingCarrierTest {

	@Test
	@DisplayName("null 코드는 택배사 없음(null)으로 매핑된다")
	void nullCodeMapsToNull() {
		assertThat(ShippingCarrier.fromMarketCode(null)).isNull();
	}

	@Test
	@DisplayName("빈 문자열/공백 코드는 ETC가 아니라 null(미입력)로 매핑된다 — D-058")
	void blankCodeMapsToNullNotEtc() {
		// 미배송 주문의 빈 택배사가 ETC로 표시되던 회귀 재현
		assertThat(ShippingCarrier.fromMarketCode("")).isNull();
		assertThat(ShippingCarrier.fromMarketCode("   ")).isNull();
	}

	@Test
	@DisplayName("알려진 택배사 코드는 정확히 매핑된다")
	void knownCodesMap() {
		assertThat(ShippingCarrier.fromMarketCode("CJGLS")).isEqualTo(ShippingCarrier.CJ_LOGISTICS);
		assertThat(ShippingCarrier.fromMarketCode("HANJIN")).isEqualTo(ShippingCarrier.HANJIN);
		assertThat(ShippingCarrier.fromMarketCode("LOTTE")).isEqualTo(ShippingCarrier.LOTTE_LOGISTICS);
		assertThat(ShippingCarrier.fromMarketCode("한진택배")).isEqualTo(ShippingCarrier.HANJIN);
		assertThat(ShippingCarrier.fromMarketCode("우체국")).isEqualTo(ShippingCarrier.KOREA_POST);
	}

	@Test
	@DisplayName("인식 불가한 코드는 ETC가 아니라 미매핑(null)으로 처리된다 — 화면에 'ETC' 노출 방지")
	void unknownCodeMapsToNull() {
		assertThat(ShippingCarrier.fromMarketCode("SOME_UNKNOWN_XYZ")).isNull();
		assertThat(ShippingCarrier.fromMarketCode("DHL")).isNull();
	}
}
