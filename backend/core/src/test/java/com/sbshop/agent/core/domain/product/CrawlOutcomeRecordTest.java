package com.sbshop.agent.core.domain.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sbshop.agent.core.domain.product.dto.ProductCreateCommand;
import com.sbshop.agent.core.domain.product.enums.MeasureUnit;
import com.sbshop.agent.core.domain.product.enums.SourceGoneReason;
import com.sbshop.agent.core.domain.product.enums.StockStatus;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 크롤 시도 결과를 상품에 남긴다.
 *
 * <p>봇차단·일시 오류는 재고·가격을 <b>건드리면 안 되지만</b>(일시적일 수 있다),
 * "마지막 크롤이 실패했다"는 사실은 남아야 한다. 남지 않으면 그 상품은
 * 조용히 옛 가격으로 굳는다 — 2026-08-31 F&M 31건이 매 배치마다 실패했는데
 * 상품만 봐서는 알 수 없었다.
 *
 * <p>폐기 후보(`sourceGone`)와는 다른 축이다: 폐기는 "원본이 없다", 이쪽은 "확인하지 못했다".
 */
class CrawlOutcomeRecordTest {

	private Product product() {
		return Product.create("SB-1", new ProductCreateCommand(
			"https://example.com/p/1", new BigDecimal("10000"), "n", "on", "b", "US",
			BigDecimal.ONE, BigDecimal.TEN, MeasureUnit.TABLET, List.of(), List.of(), "h", "c",
			true, 1, new BigDecimal("25"), VendorType.FTN, null));
	}

	@Test
	@DisplayName("크롤 실패를 기록해도 재고·가격은 그대로다 — 일시 오류로 판매 상태를 흔들면 안 된다")
	void failureDoesNotTouchStockOrPrice() {
		Product p = product();
		BigDecimal priceBefore = p.getSalePrice();
		StockStatus stockBefore = p.getStockStatus();

		p.recordCrawlFailure("Cloudflare 차단 의심");

		assertThat(p.getSalePrice()).isEqualByComparingTo(priceBefore);
		assertThat(p.getStockStatus()).isEqualTo(stockBefore);
		assertThat(p.getLastCrawlError()).isEqualTo("Cloudflare 차단 의심");
		assertThat(p.getLastCrawlAt()).isNotNull();
	}

	@Test
	@DisplayName("성공하면 이전 실패 기록을 지운다 — 복구된 상품이 계속 실패로 보이면 안 된다")
	void successClearsPreviousFailure() {
		Product p = product();
		p.recordCrawlFailure("일시 오류");

		p.recordCrawlSuccess();

		assertThat(p.getLastCrawlError()).isNull();
		assertThat(p.getLastCrawlAt()).isNotNull();
	}

	@Test
	@DisplayName("사유 없는 실패 기록은 거부한다 — 무엇이 실패했는지 모르면 기록의 의미가 없다")
	void refusesBlankReason() {
		Product p = product();
		assertThatThrownBy(() -> p.recordCrawlFailure(null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> p.recordCrawlFailure("  ")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("긴 오류 메시지는 잘라 담는다 — 컬럼을 넘겨 저장이 통째로 실패하면 안 된다")
	void truncatesLongReason() {
		Product p = product();
		p.recordCrawlFailure("x".repeat(2000));

		assertThat(p.getLastCrawlError().length()).isLessThanOrEqualTo(500);
	}

	@Test
	@DisplayName("폐기 후보와 크롤 실패는 다른 축이다 — 실패 기록이 폐기 표시를 건드리지 않는다")
	void independentFromSourceGone() {
		Product p = product();
		p.markSourceGone(SourceGoneReason.LINK_DEAD);

		p.recordCrawlFailure("차단");

		assertThat(p.isSourceGone()).isTrue();
		assertThat(p.getSourceGoneReason()).isEqualTo(SourceGoneReason.LINK_DEAD);
	}
}
