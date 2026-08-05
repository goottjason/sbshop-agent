package com.sbshop.agent.core.application.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.domain.order.vo.ShippingData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * D-129: {@code trackingSentToMarket}의 의미를 <b>"마켓이 이 송장을 갖고 있는가"</b>로 정리한다.
 *
 * <p>종전에는 "우리 시스템이 전송해 성공했는가"만 뜻했다. 그래서 <b>마켓에서 동기화로 들어온 송장</b>은
 * 마켓이 명백히 보유하고 있음에도 플래그가 null로 남았다(라이브 실측: 송장 보유 라인아이템 중
 * 170건 이상이 null — 배송이 끝난 쿠팡 주문 145건 포함). 이 상태로는 화면에서
 * "저장됨 · 마켓 미반영"을 구분할 수 없다. 정상 건까지 미반영으로 보여 경고가 무의미해지기 때문이다.
 *
 * <p>규칙을 {@link ShippingData#marketOwnsTracking}에 한 곳으로 모으고 네 마켓 동기화가 모두
 * 이를 호출한다 — 마켓별로 규칙이 갈리면 이 결함이 그대로 재발한다.
 */
class MarketSourcedTrackingMarksSyncedTest {

	@Test
	@DisplayName("[D-129] 마켓이 실송장을 주면 마켓 보유(true)로 판정한다")
	void realMarketTrackingMeansMarketOwnsIt() {
		assertThat(ShippingData.marketOwnsTracking("424410280092")).isTrue();
		assertThat(ShippingData.marketOwnsTracking("6079990333504")).isTrue();
	}

	@Test
	@DisplayName("[D-129] 자리표시자·빈값은 마킹하지 않는다(null = 기존 값 유지)")
	void placeholderOrBlankDoesNotMark() {
		// 이 값들은 채택되지 않아 우리 송장이 그대로 남는다 — 마켓이 가진 게 아니므로 마킹하면 거짓이 된다.
		assertThat(ShippingData.marketOwnsTracking("00000000")).isNull();
		assertThat(ShippingData.marketOwnsTracking("")).isNull();
		assertThat(ShippingData.marketOwnsTracking(null)).isNull();
	}

	@Test
	@DisplayName("[D-129] 판정 기준은 송장 실값 판정(isMeaningfulTracking)과 일치한다")
	void agreesWithMeaningfulTracking() {
		// 두 판정이 갈리면 "채택은 했는데 마킹은 안 된" 또는 그 반대의 어긋난 상태가 생긴다.
		for (String candidate : new String[] {"424410280092", "00000000", "", "0-0-0", " 123 ", null}) {
			boolean adopted = ShippingData.isMeaningfulTracking(candidate);
			assertThat(ShippingData.marketOwnsTracking(candidate) != null)
				.as("candidate=%s", candidate)
				.isEqualTo(adopted);
		}
	}
}
