package com.sbshop.agent.core.application.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.client.MarketClient;
import com.sbshop.agent.core.domain.market.client.MarketClientRouter;
import com.sbshop.agent.core.domain.market.client.dto.MarketItemInfo;
import com.sbshop.agent.core.domain.common.exception.ResourceNotFoundException;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.component.ProductReader;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MarketRegistrationServiceTest {

	@Mock
	private MarketRegistrationRepository marketRegistrationRepository;
	@Mock
	private MarketClientRouter marketClientRouter;
	@Mock
	private ProductReader productReader;

	@Test
	@DisplayName("등록현황 목록 → 상품 존재 시 레포 findByProductId 결과 그대로 반환")
	void getRegistrations_returnsRepositoryResult() {
		when(productReader.findById(1L)).thenReturn(Optional.of(mock(Product.class)));
		MarketRegistration reg = mock(MarketRegistration.class);
		when(marketRegistrationRepository.findByProductId(1L)).thenReturn(List.of(reg));

		assertThat(service().getRegistrations(1L)).containsExactly(reg);
	}

	@Test
	@DisplayName("등록현황 목록 → 상품은 있고 등록 0건이면 빈 리스트 반환(정상)")
	void getRegistrations_productExistsButNoRegistrations_returnsEmpty() {
		when(productReader.findById(1L)).thenReturn(Optional.of(mock(Product.class)));
		when(marketRegistrationRepository.findByProductId(1L)).thenReturn(List.of());

		assertThat(service().getRegistrations(1L)).isEmpty();
	}

	@Test
	@DisplayName("등록현황 목록 → 상품 미존재면 ResourceNotFoundException(404)")
	void getRegistrations_productNotFound_throws() {
		when(productReader.findById(999L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service().getRegistrations(999L))
			.isInstanceOf(ResourceNotFoundException.class)
			.hasMessageContaining("999");
	}

	@Test
	@DisplayName("로컬 조회 → 존재하면 반환")
	void getLocalData_found() {
		MarketRegistration reg = mock(MarketRegistration.class);
		when(marketRegistrationRepository.findByProductIdAndMarketType(1L, MarketType.COUPANG))
			.thenReturn(Optional.of(reg));

		assertThat(service().getLocalData(1L, "coupang")).isSameAs(reg);
	}

	@Test
	@DisplayName("로컬 조회 → 없으면 IllegalArgumentException")
	void getLocalData_notFound() {
		when(marketRegistrationRepository.findByProductIdAndMarketType(1L, MarketType.COUPANG))
			.thenReturn(Optional.empty());

		assertThatThrownBy(() -> service().getLocalData(1L, "coupang"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("[D-206] sync → 쿠팡은 sellerProductId로 조회(로컬 PK 아님)")
	void syncMarketLive_coupang_usesSellerProductId() {
		MarketItemInfo info = stubLive(MarketType.COUPANG,
			"{\"sellerProductId\":\"14813281569\",\"vendorItemId\":\"89379432362\"}",
			"14813281569");

		assertThat(service().syncMarketLive(1592L, "coupang")).isSameAs(info);
	}

	@Test
	@DisplayName("[D-206] sync → 스마트스토어는 originProductNo로 조회(로컬 PK 아님)")
	void syncMarketLive_smartstore_usesOriginProductNo() {
		MarketItemInfo info = stubLive(MarketType.SMART_STORE,
			"{\"originProductNo\":\"6321468668\",\"channelProductNo\":\"6351684748\"}",
			"6321468668");

		assertThat(service().syncMarketLive(1592L, "smart_store")).isSameAs(info);
	}

	@Test
	@DisplayName("[D-206] sync → 11번가는 prdNo로 조회(로컬 PK 아님)")
	void syncMarketLive_elevenst_usesPrdNo() {
		MarketItemInfo info = stubLive(MarketType.ELEVEN_STREET,
			"{\"sellerPrdCd\":\"220227IHB052\",\"prdNo\":\"4193852605\"}",
			"4193852605");

		assertThat(service().syncMarketLive(1592L, "eleven_street")).isSameAs(info);
	}

	@Test
	@DisplayName("[D-206] sync → 카페24는 product_no로 조회(로컬 PK 아님)")
	void syncMarketLive_cafe24_usesProductNo() {
		MarketItemInfo info = stubLive(MarketType.CAFE24,
			"{\"product_no\":\"17624\",\"product_code\":\"P000BABW\"}",
			"17624");

		assertThat(service().syncMarketLive(1592L, "cafe24")).isSameAs(info);
	}

	@Test
	@DisplayName("[D-206] sync → 식별자 부재 시 로컬 PK 폴백 없이 IllegalStateException")
	void syncMarketLive_missingIdentifier_throwsInsteadOfLocalPkFallback() {
		registration(MarketType.SMART_STORE, "{\"channelProductNo\":\"6351684748\"}");

		assertThatThrownBy(() -> service().syncMarketLive(1592L, "smart_store"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("originProductNo");

		verifyNoInteractions(marketClientRouter);
	}

	@Test
	@DisplayName("[D-206] sync → 실시간 조회 미지원 마켓(G마켓)은 명확한 예외")
	void syncMarketLive_unsupportedMarket_throws() {
		registration(MarketType.GMARKET, "{\"gmarket_goodsNo\":\"G777\"}");

		assertThatThrownBy(() -> service().syncMarketLive(1592L, "gmarket"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("실시간 조회");

		verifyNoInteractions(marketClientRouter);
	}

	private MarketRegistration registration(MarketType type, String identifiers) {
		MarketRegistration reg = MarketRegistration.builder()
			.productId(1592L)
			.marketType(type)
			.marketIdentifiers(identifiers)
			.build();
		when(marketRegistrationRepository.findByProductIdAndMarketType(1592L, type))
			.thenReturn(Optional.of(reg));
		return reg;
	}

	private MarketItemInfo stubLive(MarketType type, String identifiers, String expectedLookupId) {
		registration(type, identifiers);
		MarketClient client = mock(MarketClient.class);
		when(marketClientRouter.getClient(type)).thenReturn(client);
		MarketItemInfo info = mock(MarketItemInfo.class);
		when(client.extractMarketItem(expectedLookupId)).thenReturn(info);
		return info;
	}

	private MarketRegistrationService service() {
		return new MarketRegistrationService(
			marketRegistrationRepository, marketClientRouter, productReader);
	}
}
