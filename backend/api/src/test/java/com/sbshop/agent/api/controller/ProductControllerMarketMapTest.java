package com.sbshop.agent.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.api.dto.product.MarketBadgeState;
import com.sbshop.agent.api.dto.product.ProductListResponse;
import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.product.ProductManageUseCase;
import com.sbshop.agent.core.application.product.ProductSearchUseCase;
import com.sbshop.agent.core.application.product.port.ProductInfoCrawlerPort;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.client.ImageDownloadClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;

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
	private ActionLogService actionLogService;

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
				reg(1L, MarketType.COUPANG, "{\"productId\":\"9334584158\",\"vendorItemId\":\"73567246734\"}"),
				reg(2L, MarketType.SMART_STORE, "{}")));

		ResponseEntity<Page<ProductListResponse>> res =
			controller().getProducts(null, null, PageRequest.of(0, 50));

		List<ProductListResponse> content = res.getBody().getContent();
		assertThat(content).hasSize(2);
		assertThat(content.get(0).marketRegistrations().get(MarketType.COUPANG.name()).url())
			.isEqualTo("https://www.coupang.com/vp/products/9334584158?vendorItemId=73567246734");
		assertThat(content.get(1).marketRegistrations().get(MarketType.SMART_STORE.name()).url()).isNull();

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
		assertThat(links.get("GMARKET").url()).isEqualTo("http://item.gmarket.co.kr/Item?goodscode=3490122824");
		assertThat(links.get("AUCTION").url())
			.isEqualTo("http://itempage3.auction.co.kr/DetailView.aspx?ItemNo=D888857683");
		assertThat(links).containsKey(MarketType.CAFE24.name());
	}

	@Test
	@DisplayName("CAFE24 등록행은 CAFE24 키로도 내려간다 — 프론트가 카페24 등록 여부를 알아야 G마켓/옥션 선행조건을 판정한다")
	void getProducts_includesCafe24Key() {
		when(product1.getId()).thenReturn(1L);
		Page<Product> page = new PageImpl<>(List.of(product1), PageRequest.of(0, 50), 1);
		when(productSearchUseCase.searchProducts(any(), any())).thenReturn(page);
		when(marketRegistrationRepository.findByProductIdIn(List.of(1L)))
			.thenReturn(List.of(reg(1L, MarketType.CAFE24, "{\"cafe24ProductNo\":\"77\"}")));

		ResponseEntity<Page<ProductListResponse>> res =
			controller().getProducts(null, null, PageRequest.of(0, 50));

		Map<String, MarketBadgeState> map = res.getBody().getContent().get(0).marketRegistrations();
		assertThat(map).containsKey("CAFE24");
		assertThat(map).doesNotContainKey("GMARKET");
	}

	@Test
	@DisplayName("식별자가 비어 있으면 status=PENDING — savePending이 게시 전에 만든 미완료 행이 이 모양이다")
	void getProducts_pendingStatusWhenNoIdentifiers() {
		when(product1.getId()).thenReturn(1L);
		Page<Product> page = new PageImpl<>(List.of(product1), PageRequest.of(0, 50), 1);
		when(productSearchUseCase.searchProducts(any(), any())).thenReturn(page);
		when(marketRegistrationRepository.findByProductIdIn(List.of(1L)))
			.thenReturn(List.of(reg(1L, MarketType.COUPANG, "{}")));

		ResponseEntity<Page<ProductListResponse>> res =
			controller().getProducts(null, null, PageRequest.of(0, 50));

		MarketBadgeState state = res.getBody().getContent().get(0).marketRegistrations().get("COUPANG");
		assertThat(state.status()).isEqualTo("PENDING");
		assertThat(state.url()).isNull();
	}

	@Test
	@DisplayName("식별자가 있으면 isSynced가 false여도 SYNCED — 레거시 임포트 행을 거짓 미완료로 경고하지 않는다")
	void getProducts_syncedWhenIdentifiersPresentDespiteUnsyncedFlag() {
		when(product1.getId()).thenReturn(1L);
		Page<Product> page = new PageImpl<>(List.of(product1), PageRequest.of(0, 50), 1);
		when(productSearchUseCase.searchProducts(any(), any())).thenReturn(page);
		when(marketRegistrationRepository.findByProductIdIn(List.of(1L)))
			.thenReturn(List.of(reg(1L, MarketType.COUPANG,
				"{\"productId\":\"123\",\"vendorItemId\":\"456\"}")));

		ResponseEntity<Page<ProductListResponse>> res =
			controller().getProducts(null, null, PageRequest.of(0, 50));

		MarketBadgeState state = res.getBody().getContent().get(0).marketRegistrations().get("COUPANG");
		assertThat(state.status()).isEqualTo("SYNCED");
		assertThat(state.url()).isEqualTo("https://www.coupang.com/vp/products/123?vendorItemId=456");
	}
}
