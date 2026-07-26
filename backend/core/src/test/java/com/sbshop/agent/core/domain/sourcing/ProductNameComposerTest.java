package com.sbshop.agent.core.domain.sourcing;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.sourcing.component.MarketProductRules;
import com.sbshop.agent.core.domain.sourcing.component.ProductNameComposer;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 마켓 상품명 조립·길이 제한 규칙을 고정한다. */
class ProductNameComposerTest {

	@Test
	@DisplayName("브랜드·핵심명·용량·묶음수를 순서대로 조립한다")
	void composesInOrder() {
		String name = ProductNameComposer.compose(
			"캘리포니아골드뉴트리션", "비타민D3 K2 MK-7", new BigDecimal("180"), "정(타블렛)",
			2, MarketType.COUPANG);

		assertThat(name).isEqualTo("캘리포니아골드뉴트리션 비타민D3 K2 MK-7 180정(타블렛) 2개");
	}

	@Test
	@DisplayName("묶음 1개는 표기하지 않는다 — 검색에 도움이 안 되고 글자수만 먹는다")
	void omitsSingleBundle() {
		String name = ProductNameComposer.compose(
			"나우푸드", "비타민C 1000", new BigDecimal("250"), "정", 1, MarketType.COUPANG);

		assertThat(name).doesNotContain("1개");
		assertThat(name).isEqualTo("나우푸드 비타민C 1000 250정");
	}

	@Test
	@DisplayName("용량 소수점 0은 떼고 표기한다")
	void stripsTrailingZeros() {
		String name = ProductNameComposer.compose(
			"브랜드", "제품", new BigDecimal("180.00"), "정", 1, MarketType.COUPANG);

		assertThat(name).contains("180정").doesNotContain("180.00");
	}

	@Test
	@DisplayName("빈 값은 건너뛰고 공백이 겹치지 않는다")
	void skipsBlankParts() {
		String name = ProductNameComposer.compose(
			null, "루테인 지아잔틴", null, null, 1, MarketType.COUPANG);

		assertThat(name).isEqualTo("루테인 지아잔틴");
	}

	@Test
	@DisplayName("마켓 상품명 길이 상한을 넘기지 않는다")
	void respectsMarketLengthLimit() {
		String longBase = "아주긴상품명".repeat(40);

		assertThat(ProductNameComposer.compose("브랜드", longBase, null, null, 1, MarketType.COUPANG))
			.hasSizeLessThanOrEqualTo(100);
		assertThat(ProductNameComposer.compose("브랜드", longBase, null, null, 1, MarketType.SMART_STORE))
			.hasSizeLessThanOrEqualTo(100);
		// Cafe24는 자사몰이라 250자까지 허용된다.
		assertThat(ProductNameComposer.compose("브랜드", longBase, null, null, 1, MarketType.CAFE24))
			.hasSizeLessThanOrEqualTo(250);
	}

	@Test
	@DisplayName("길이 초과 시 단어 중간이 아니라 공백에서 자른다")
	void cutsAtWordBoundary() {
		String base = "가나다라마바사 ".repeat(20).trim();

		String name = MarketProductRules.fitName(base, MarketType.COUPANG);

		assertThat(name).hasSizeLessThanOrEqualTo(100);
		assertThat(name).doesNotEndWith(" ");
		// 마지막 토큰이 온전해야 한다.
		assertThat(name.substring(name.lastIndexOf(' ') + 1)).isEqualTo("가나다라마바사");
	}

	@Test
	@DisplayName("마켓이 거부하는 특수문자를 제거한다")
	void stripsForbiddenChars() {
		String name = MarketProductRules.fitName("Synbiotic+® <민트> \"프리미엄\"", MarketType.COUPANG);

		assertThat(name).doesNotContain("®").doesNotContain("<").doesNotContain("\"");
		assertThat(name).contains("Synbiotic+").contains("민트").contains("프리미엄");
	}
}
