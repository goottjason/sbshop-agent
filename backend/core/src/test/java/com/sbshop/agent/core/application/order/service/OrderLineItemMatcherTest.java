package com.sbshop.agent.core.application.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sbshop.agent.core.application.order.dto.MarketLineItemDto;
import com.sbshop.agent.core.application.order.service.OrderLineItemMatcher.Incoming;
import com.sbshop.agent.core.application.order.service.OrderLineItemMatcher.MatchResult;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.vo.ShippingData;

/**
 * D-132: 레거시 라인아이템 채택(백필) 경로.
 *
 * <p>운영 DB의 라인아이템 240행 전부가 {@code market_line_item_no}가 NULL이다(2026-08-06 실측).
 * 어댑터가 상품주문 식별자를 채우기 시작하는 순간, 이 행들은 새 매칭에 걸리지 않아
 * <b>같은 상품주문에 라인아이템이 두 벌 생긴다</b> — 그리고 우리 고유 정보(소싱처·실구매가·
 * 구매상태)는 아무도 보지 않는 옛 행에 남는다.
 *
 * <p>별도 백필 배치를 두지 않는다. 동기화가 30일 창을 매 사이클 다시 훑으므로,
 * 매칭 시점에 레거시 행을 <b>채택</b>(키 부여)하는 것이 곧 백필이다(설계 §5.4).
 */
class OrderLineItemMatcherTest {

	private static OrderLineItem legacy(Long productId) {
		return OrderLineItem.builder().orderId(1L).quantity(1).productId(productId).build();
	}

	private static OrderLineItem legacyWithTracking(Long productId, String trackingNo) {
		return OrderLineItem.builder()
			.orderId(1L).quantity(1).productId(productId)
			.shippingData(ShippingData.builder().trackingNo(trackingNo).build())
			.build();
	}

	private static OrderLineItem keyed(String marketLineItemNo, Long productId) {
		return OrderLineItem.builder()
			.orderId(1L).quantity(1).productId(productId)
			.marketLineItemNo(marketLineItemNo)
			.build();
	}

	private static Incoming incoming(String marketLineItemNo, Long resolvedProductId) {
		return new Incoming(
			MarketLineItemDto.builder().marketLineItemNo(marketLineItemNo).build(),
			resolvedProductId);
	}

	@Test
	@DisplayName("이미 키가 붙은 행은 키로 정확히 매칭한다 — 배열 순서를 보지 않는다")
	void matchesByExactKeyRegardlessOfOrder() {
		OrderLineItem first = keyed("1", 100L);
		OrderLineItem second = keyed("2", 200L);

		// 마켓이 순서를 뒤집어 줘도 키로 짝지어야 한다(Cafe24 인덱스 짝짓기의 결함).
		MatchResult result = OrderLineItemMatcher.matchAndAdopt(
			List.of(first, second),
			List.of(incoming("2", 200L), incoming("1", 100L)));

		assertThat(result.toCreate()).isEmpty();
		assertThat(result.matched()).hasSize(2);
		assertThat(result.matched()).anySatisfy(a -> {
			assertThat(a.dto().getMarketLineItemNo()).isEqualTo("2");
			assertThat(a.lineItem()).isSameAs(second);
		});
		assertThat(result.matched()).anySatisfy(a -> {
			assertThat(a.dto().getMarketLineItemNo()).isEqualTo("1");
			assertThat(a.lineItem()).isSameAs(first);
		});
	}

	@Test
	@DisplayName("레거시 1행 + 상품주문 1건이면 채택하고 키를 부여한다 — 현재 240행 전부의 형태")
	void adoptsSoleLegacyRowForSoleIncoming() {
		OrderLineItem row = legacy(312L);

		MatchResult result = OrderLineItemMatcher.matchAndAdopt(
			List.of(row), List.of(incoming("1", 312L)));

		assertThat(result.toCreate()).isEmpty();
		assertThat(result.matched()).hasSize(1);
		assertThat(result.matched().get(0).lineItem()).isSameAs(row);
		// 채택이 곧 백필이다 — 키가 실제로 부여돼야 다음 사이클부터 정확키 경로를 탄다.
		assertThat(row.getMarketLineItemNo()).isEqualTo("1");
	}

	@Test
	@DisplayName("상품 ID가 달라도 1:1이면 채택한다 — 종전 assignProductId 동작을 보존한다")
	void adoptsSoleLegacyRowEvenWhenProductDiffers() {
		// 종전 updateLineItemFromDto는 상품 ID가 다르면 그냥 재할당했다(마켓 sbCode 재매핑 등).
		// 여기서 채택을 거부하면 중복 행이 생겨 오히려 퇴행한다.
		OrderLineItem row = legacy(312L);

		MatchResult result = OrderLineItemMatcher.matchAndAdopt(
			List.of(row), List.of(incoming("1", 999L)));

		assertThat(result.toCreate()).isEmpty();
		assertThat(result.matched()).hasSize(1);
		assertThat(row.getMarketLineItemNo()).isEqualTo("1");
	}

	@Test
	@DisplayName("상품 ID가 null인 레거시 행도 1:1이면 채택한다 — sbCode 미매핑 행")
	void adoptsSoleLegacyRowWithNullProduct() {
		OrderLineItem row = legacy(null);

		MatchResult result = OrderLineItemMatcher.matchAndAdopt(
			List.of(row), List.of(incoming("1", null)));

		assertThat(result.toCreate()).isEmpty();
		assertThat(row.getMarketLineItemNo()).isEqualTo("1");
	}

	@Test
	@DisplayName("정나영 형태: 레거시 1행이 2건으로 쪼개질 때 상품 ID가 맞는 쪽이 채택된다")
	void splitsLegacyRowByProductId() {
		// 11번가 20260731088778989 — 순번1 Calcium Magnesium(product 312, 결제완료),
		// 순번2 베이직 뉴트리언트(product 999, 발송완료). DB에는 순번1 상품으로 1행만 있었다.
		OrderLineItem row = legacy(312L);

		MatchResult result = OrderLineItemMatcher.matchAndAdopt(
			List.of(row),
			List.of(incoming("1", 312L), incoming("2", 999L)));

		assertThat(result.matched()).hasSize(1);
		assertThat(result.matched().get(0).dto().getMarketLineItemNo()).isEqualTo("1");
		assertThat(result.matched().get(0).lineItem()).isSameAs(row);
		assertThat(row.getMarketLineItemNo()).isEqualTo("1");

		// 순번2는 시스템에 존재하지 않았다 — 신규 생성 대상이고, 구매정보 없음(미구매)이 정확하다.
		assertThat(result.toCreate()).hasSize(1);
		assertThat(result.toCreate().get(0).getMarketLineItemNo()).isEqualTo("2");
	}

	@Test
	@DisplayName("한 레거시 행을 두 상품주문이 함께 채택하지 않는다")
	void neverAdoptsSameLegacyRowTwice() {
		// 같은 상품이 순번을 달리해 두 번 담긴 주문. 채택은 한 번만 일어나야 하고
		// 나머지는 신규 생성이어야 한다 — 아니면 두 상품주문이 한 행을 덮어써 D-130이 재발한다.
		OrderLineItem row = legacy(312L);

		MatchResult result = OrderLineItemMatcher.matchAndAdopt(
			List.of(row),
			List.of(incoming("1", 312L), incoming("2", 312L)));

		assertThat(result.matched()).hasSize(1);
		assertThat(result.toCreate()).hasSize(1);
	}

	@Test
	@DisplayName("쪼개짐이 일어났고 채택한 행에 송장이 있으면 ⚠ 확인 필요로 남긴다")
	void warnsWhenAdoptedRowCarriesForeignTracking() {
		// 정나영 건의 실제 형태 — 한 행이 순번1의 상품·정산액과 순번2의 송장을 함께 갖고 있었다.
		// 어느 쪽이 맞는지 자동으로 알 수 없으므로 지우지 않고 사람이 판단하게 남긴다(설계 §5.4-3).
		OrderLineItem row = legacyWithTracking(312L, "424079080471");

		MatchResult result = OrderLineItemMatcher.matchAndAdopt(
			List.of(row),
			List.of(incoming("1", 312L), incoming("2", 999L)));

		assertThat(result.warnings()).isNotEmpty();
		assertThat(result.warnings().toString()).contains("424079080471");
	}

	@Test
	@DisplayName("쪼개짐이 없으면 송장이 있어도 경고하지 않는다")
	void doesNotWarnWithoutSplit() {
		OrderLineItem row = legacyWithTracking(312L, "424079080471");

		MatchResult result = OrderLineItemMatcher.matchAndAdopt(
			List.of(row), List.of(incoming("1", 312L)));

		assertThat(result.warnings()).isEmpty();
	}

	@Test
	@DisplayName("같은 상품의 레거시 후보가 여럿이면 하나만 채택하고 모호함을 경고한다")
	void warnsOnAmbiguousProductMatch() {
		OrderLineItem a = legacy(312L);
		OrderLineItem b = legacy(312L);

		MatchResult result = OrderLineItemMatcher.matchAndAdopt(
			List.of(a, b),
			List.of(incoming("1", 312L), incoming("2", 999L)));

		assertThat(result.matched()).hasSize(1);
		assertThat(result.warnings()).isNotEmpty();
		// 남은 레거시 행은 삭제 대상이 아니다 — 우리 고유 정보가 붙어 있을 수 있다.
		assertThat(result.unclaimed()).hasSize(1);
	}

	@Test
	@DisplayName("매칭되지 않은 기존 행은 unclaimed로 남고 아무 변경도 받지 않는다")
	void leavesUnmatchedRowsAlone() {
		OrderLineItem stale = keyed("9", 500L);

		MatchResult result = OrderLineItemMatcher.matchAndAdopt(
			List.of(stale), List.of(incoming("1", 100L)));

		assertThat(result.matched()).isEmpty();
		assertThat(result.toCreate()).hasSize(1);
		assertThat(result.unclaimed()).containsExactly(stale);
		assertThat(stale.getMarketLineItemNo()).isEqualTo("9");
	}

	@Test
	@DisplayName("기존 행이 없으면 전부 신규 생성이다")
	void createsAllWhenNoExistingRows() {
		MatchResult result = OrderLineItemMatcher.matchAndAdopt(
			List.of(), List.of(incoming("1", 100L), incoming("2", 200L)));

		assertThat(result.matched()).isEmpty();
		assertThat(result.toCreate()).hasSize(2);
		assertThat(result.unclaimed()).isEmpty();
	}

	@Test
	@DisplayName("상품주문 식별자가 없는 평면 DTO(전환 전 마켓)도 1:1이면 기존 행을 재사용한다")
	void reusesLegacyRowWhenIncomingHasNoKeyEither() {
		// D-131로 정규화기가 상품주문 키를 비운다. 전환 전 마켓은 양쪽이 null이므로
		// 카디널리티로만 짝지어야 하고, 이때 키를 위조해 부여하지 않는다.
		OrderLineItem row = legacy(312L);

		MatchResult result = OrderLineItemMatcher.matchAndAdopt(
			List.of(row), List.of(incoming(null, 312L)));

		assertThat(result.toCreate()).isEmpty();
		assertThat(result.matched()).hasSize(1);
		assertThat(result.matched().get(0).lineItem()).isSameAs(row);
		assertThat(row.getMarketLineItemNo()).isNull();
	}
}
