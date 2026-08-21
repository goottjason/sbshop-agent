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

	@Test
	@DisplayName("코드가 미매핑이면 이름으로 폴백한다 — 부분 신호가 더 나은 신호를 가리지 않는다")
	void unmappedCodeFallsBackToName() {
		// 2026-08-06 라이브: Cafe24가 shipping_company_code='0006', shipping_company_name='CJ대한통운'을
		// 함께 준다. 종전 호출부는 firstNonBlank(code, name)로 코드만 넘겨, 미매핑 코드가 매핑 가능한
		// 이름을 가려 택배사가 유실됐다(G마켓/옥션 주문의 택배사가 화면에 안 뜸).
		assertThat(ShippingCarrier.resolve("0006", "CJ대한통운")).isEqualTo(ShippingCarrier.CJ_LOGISTICS);
	}

	@Test
	@DisplayName("코드가 매핑되면 코드를 쓴다 — 코드가 더 권위 있다")
	void prefersCodeWhenMapped() {
		assertThat(ShippingCarrier.resolve("CJGLS", "롯데택배")).isEqualTo(ShippingCarrier.CJ_LOGISTICS);
	}

	@Test
	@DisplayName("둘 다 미매핑이면 null이다 — ETC로 위조하지 않는다")
	void bothUnmappedIsNull() {
		assertThat(ShippingCarrier.resolve("9999", "듣보잡택배")).isNull();
		assertThat(ShippingCarrier.resolve(null, null)).isNull();
		assertThat(ShippingCarrier.resolve("  ", "")).isNull();
	}

}
