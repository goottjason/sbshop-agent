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

class OrderLineItemMatcherTest {
	@Test
	@DisplayName("이미 키가 붙은 행은 키로 정확히 매칭한다 — 배열 순서를 보지 않는다")
	void matchesByExactKeyRegardlessOfOrder() {
		OrderLineItem first = keyed("1", 100L);
		OrderLineItem second = keyed("2", 200L);

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
		assertThat(row.getMarketLineItemNo()).isEqualTo("1");
	}

	@Test
	@DisplayName("상품 ID가 달라도 1:1이면 채택한다 — 종전 assignProductId 동작을 보존한다")
	void adoptsSoleLegacyRowEvenWhenProductDiffers() {
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
		OrderLineItem row = legacy(312L);

		MatchResult result = OrderLineItemMatcher.matchAndAdopt(
			List.of(row),
			List.of(incoming("1", 312L), incoming("2", 999L)));

		assertThat(result.matched()).hasSize(1);
		assertThat(result.matched().get(0).dto().getMarketLineItemNo()).isEqualTo("1");
		assertThat(result.matched().get(0).lineItem()).isSameAs(row);
		assertThat(row.getMarketLineItemNo()).isEqualTo("1");

		assertThat(result.toCreate()).hasSize(1);
		assertThat(result.toCreate().get(0).getMarketLineItemNo()).isEqualTo("2");
	}

	@Test
	@DisplayName("한 레거시 행을 두 상품주문이 함께 채택하지 않는다")
	void neverAdoptsSameLegacyRowTwice() {
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
		OrderLineItem row = legacy(312L);

		MatchResult result = OrderLineItemMatcher.matchAndAdopt(
			List.of(row), List.of(incoming(null, 312L)));

		assertThat(result.toCreate()).isEmpty();
		assertThat(result.matched()).hasSize(1);
		assertThat(result.matched().get(0).lineItem()).isSameAs(row);
		assertThat(row.getMarketLineItemNo()).isNull();
	}

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
}
