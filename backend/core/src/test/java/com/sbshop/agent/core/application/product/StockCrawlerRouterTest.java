package com.sbshop.agent.core.application.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.product.dto.StockCheckResult;
import com.sbshop.agent.core.application.product.port.VendorAwareStockCrawler;
import com.sbshop.agent.core.domain.product.enums.StockStatus;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StockCrawlerRouterTest {

	@Test
	@DisplayName("D-239: 크롤러가 없는 소싱처는 다른 크롤러로 넘기지 않고 실패한다 — 코스트코 URL 을 아이허브 파서에 넣으면 안 된다")
	void unregisteredVendor_failsInsteadOfFallingBack() {
		VendorAwareStockCrawler iherb = crawler(VendorType.IHB);
		StockCrawlerRouter router = new StockCrawlerRouter(List.of(iherb));

		assertThatThrownBy(() -> router.checkStockWithDetails(VendorType.COK, "https://www.costco.co.uk/x"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("COK");

		verify(iherb, never()).checkStockWithDetails("https://www.costco.co.uk/x");
	}

	@Test
	@DisplayName("D-239: 등록된 소싱처는 자기 크롤러로 간다")
	void registeredVendor_usesOwnCrawler() {
		VendorAwareStockCrawler iherb = crawler(VendorType.IHB);
		VendorAwareStockCrawler ftn = crawler(VendorType.FTN);
		when(ftn.checkStockWithDetails("u")).thenReturn(
			new StockCheckResult(StockStatus.IN_STOCK, null, 10, null));
		StockCrawlerRouter router = new StockCrawlerRouter(List.of(iherb, ftn));

		StockCheckResult result = router.checkStockWithDetails(VendorType.FTN, "u");

		assertThat(result.status()).isEqualTo(StockStatus.IN_STOCK);
		verify(iherb, never()).checkStockWithDetails("u");
	}

	@Test
	@DisplayName("D-239: 어떤 소싱처를 지원하는지 물어볼 수 있어야 한다 — 스케줄 대상 선정의 근거")
	void exposesSupportedVendors() {
		StockCrawlerRouter router = new StockCrawlerRouter(
			List.of(crawler(VendorType.IHB), crawler(VendorType.FTN)));

		assertThat(router.supportedVendors()).containsExactlyInAnyOrder(VendorType.IHB, VendorType.FTN);
		assertThat(router.supports(VendorType.COK)).isFalse();
		assertThat(router.supports(VendorType.IHB)).isTrue();
	}

	private VendorAwareStockCrawler crawler(VendorType vendor) {
		VendorAwareStockCrawler c = mock(VendorAwareStockCrawler.class);
		when(c.vendor()).thenReturn(vendor);
		return c;
	}
}
