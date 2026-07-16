package com.sbshop.agent.core.application.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
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

/**
 * SP-9 서비스 계층 추출: MarketRegistrationController의 등록현황/로컬조회/sync 로직을
 * MarketRegistrationService로 이동. 동작 보존을 서비스 단위로 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class MarketRegistrationServiceTest {

	@Mock private MarketRegistrationRepository marketRegistrationRepository;
	@Mock private MarketClientRouter marketClientRouter;
	@Mock private ProductReader productReader;

	private MarketRegistrationService service() {
		return new MarketRegistrationService(
			marketRegistrationRepository, marketClientRouter, productReader);
	}

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
	@DisplayName("sync → vendorItemId 있으면 그대로 사용해 extractMarketItem 호출")
	void syncMarketLive_usesVendorItemId() {
		MarketRegistration reg = mock(MarketRegistration.class);
		when(reg.extractVendorItemId()).thenReturn("VI-123");
		when(marketRegistrationRepository.findByProductIdAndMarketType(1L, MarketType.COUPANG))
			.thenReturn(Optional.of(reg));
		MarketClient client = mock(MarketClient.class);
		when(marketClientRouter.getClient(MarketType.COUPANG)).thenReturn(client);
		MarketItemInfo info = mock(MarketItemInfo.class);
		when(client.extractMarketItem("VI-123")).thenReturn(info);

		assertThat(service().syncMarketLive(1L, "coupang")).isSameAs(info);
	}

	@Test
	@DisplayName("sync → vendorItemId 비면 productId로 폴백(기존 분기 보존)")
	void syncMarketLive_fallbackToProductId() {
		MarketRegistration reg = mock(MarketRegistration.class);
		when(reg.extractVendorItemId()).thenReturn("");
		when(reg.getProductId()).thenReturn(77L);
		when(marketRegistrationRepository.findByProductIdAndMarketType(1L, MarketType.COUPANG))
			.thenReturn(Optional.of(reg));
		MarketClient client = mock(MarketClient.class);
		when(marketClientRouter.getClient(MarketType.COUPANG)).thenReturn(client);
		MarketItemInfo info = mock(MarketItemInfo.class);
		when(client.extractMarketItem("77")).thenReturn(info);

		assertThat(service().syncMarketLive(1L, "coupang")).isSameAs(info);
	}
}
