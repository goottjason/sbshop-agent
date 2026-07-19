package com.sbshop.agent.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.api.dto.product.ProductListResponse;
import com.sbshop.agent.core.application.product.ProductManageUseCase;
import com.sbshop.agent.core.application.product.ProductSearchUseCase;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.client.ImageDownloadClient;
import com.sbshop.agent.core.application.product.port.ProductInfoCrawlerPort;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;

/**
 * 상품 목록의 마켓 배지 링크 맵(마켓명 → 상품페이지 URL) 조립 검증.
 * - 응답 키가 프론트 소비 키(MarketType.name())와 일치.
 * - 값은 마켓 상품페이지 URL(등록됐으나 링크식별자 미확보면 빈 문자열).
 * - G마켓/옥션은 Cafe24 등록행에 백필된 식별자에서 파생.
 * - N+1(row별 findByProductId) 대신 배치 조회(findByProductIdIn) 사용.
 */
@ExtendWith(MockitoExtension.class)
class ProductControllerMarketMapTest {

	@Mock
	private ProductSearchUseCase productSearchUseCase;
	@Mock
	private ProductManageUseCase productManageUseCase;
	@Mock
	private ImageDownloadClient imageDownloadClient;
	@Mock
	private ProductInfoCrawlerPort productInfoCrawlerPort;
	@Mock
	private MarketRegistrationRepository marketRegistrationRepository;
	@Mock
	private com.sbshop.agent.core.application.actionlog.ActionLogService actionLogService;

	@Mock
	private Product product1;
	@Mock
	private Product product2;

	private ProductController controller() {
		return new ProductController(productSearchUseCase, productManageUseCase,
			imageDownloadClient, productInfoCrawlerPort, marketRegistrationRepository, actionLogService);
	}

	private MarketRegistration reg(Long productId, MarketType type, String identifiersJson) {
		return MarketRegistration.builder()
			.productId(productId)
			.marketType(type)
			.marketIdentifiers(identifiersJson)
			.build();
	}

	@Test
	@DisplayName("마켓 배지 링크 맵을 배치 조회(findByProductIdIn)로 조립하고, 키는 MarketType.name()과 일치한다")
	void getProducts_assemblesMarketMapWithBatchQuery() {
		when(product1.getId()).thenReturn(1L);
		when(product2.getId()).thenReturn(2L);
		Page<Product> page = new PageImpl<>(List.of(product1, product2), PageRequest.of(0, 50), 2);
		when(productSearchUseCase.searchProducts(any(), any())).thenReturn(page);
		when(marketRegistrationRepository.findByProductIdIn(List.of(1L, 2L)))
			.thenReturn(List.of(
				// 쿠팡: productId 있음 → 상품페이지 URL(vendorItemId 부가)
				reg(1L, MarketType.COUPANG, "{\"productId\":\"9334584158\",\"vendorItemId\":\"73567246734\"}"),
				// 스토어: channelProductNo 없음 → 등록됐으나 링크 미확보(빈 문자열)
				reg(2L, MarketType.SMART_STORE, "{}")));

		ResponseEntity<Page<ProductListResponse>> res =
			controller().getProducts(null, null, PageRequest.of(0, 50));

		List<ProductListResponse> content = res.getBody().getContent();
		assertThat(content).hasSize(2);
		assertThat(content.get(0).marketRegistrations())
			.containsEntry(MarketType.COUPANG.name(),
				"https://www.coupang.com/vp/products/9334584158?vendorItemId=73567246734");
		// channelProductNo 없으면 배지는 표시하되 링크 없음(빈 문자열)
		assertThat(content.get(1).marketRegistrations())
			.containsEntry(MarketType.SMART_STORE.name(), "");

		// N+1 제거: 배치 조회 1회, 개별 조회 0회
		verify(marketRegistrationRepository).findByProductIdIn(List.of(1L, 2L));
		verify(marketRegistrationRepository, never()).findByProductId(anyLong());
	}

	@Test
	@DisplayName("G마켓/옥션은 Cafe24 등록행에 백필된 식별자에서 배지 링크를 파생한다")
	void getProducts_derivesGmarketAuctionFromCafe24() {
		when(product1.getId()).thenReturn(1L);
		Page<Product> page = new PageImpl<>(List.of(product1), PageRequest.of(0, 50), 1);
		when(productSearchUseCase.searchProducts(any(), any())).thenReturn(page);
		when(marketRegistrationRepository.findByProductIdIn(List.of(1L)))
			.thenReturn(List.of(reg(1L, MarketType.CAFE24,
				"{\"product_no\":\"10615\",\"gmarket_goodsNo\":\"3490122824\",\"auction_goodsNo\":\"D888857683\"}")));

		ResponseEntity<Page<ProductListResponse>> res =
			controller().getProducts(null, null, PageRequest.of(0, 50));

		var links = res.getBody().getContent().get(0).marketRegistrations();
		assertThat(links).containsEntry("GMARKET", "http://item.gmarket.co.kr/Item?goodscode=3490122824");
		assertThat(links).containsEntry("AUCTION", "http://itempage3.auction.co.kr/DetailView.aspx?ItemNo=D888857683");
		// 카페24 자체는 배지 대상 아님
		assertThat(links).doesNotContainKey(MarketType.CAFE24.name());
	}
}
