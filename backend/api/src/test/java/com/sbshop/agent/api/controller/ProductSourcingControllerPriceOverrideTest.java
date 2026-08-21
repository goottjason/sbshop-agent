package com.sbshop.agent.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.api.dto.product.MarketPublishPriceRequest;
import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.product.ProductCreateUseCase;
import com.sbshop.agent.core.application.product.ProductPublishUseCase;
import com.sbshop.agent.core.application.product.dto.MarketPublishOutcome;
import com.sbshop.agent.core.application.product.dto.MarketSalePriceOverrides;
import com.sbshop.agent.core.application.sourcing.ProductSourcingUseCase;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProductSourcingControllerPriceOverrideTest {

	@Mock
	private ProductSourcingUseCase productSourcingUseCase;
	@Mock
	private ProductCreateUseCase productCreateUseCase;
	@Mock
	private ProductPublishUseCase productPublishUseCase;
	@Mock
	private MarketRegistrationRepository marketRegistrationRepository;
	@Mock
	private ActionLogService actionLogService;

	private ProductSourcingController controller() {
		return new ProductSourcingController(productSourcingUseCase, productCreateUseCase,
			productPublishUseCase, marketRegistrationRepository, actionLogService);
	}

	@Test
	@DisplayName("가격 바디가 있으면 오버라이드로 변환해 UseCase에 넘긴다")
	void publishToMarket_withPriceBody_passesOverrides() {
		Long productId = 1L;
		MarketPublishPriceRequest body = new MarketPublishPriceRequest(new BigDecimal("15"), new BigDecimal("20"),
			new BigDecimal("5000"));
		when(productPublishUseCase.publishToMarket(eq(productId), eq(MarketType.ELEVEN_STREET), any()))
			.thenReturn(new MarketPublishOutcome(MarketType.ELEVEN_STREET, Map.of("elevenstId", "1"), true));
		when(marketRegistrationRepository.findByProductIdAndMarketType(productId, MarketType.ELEVEN_STREET))
			.thenReturn(Optional.empty());

		controller().publishToMarket(productId, "eleven_street", body);

		verify(productPublishUseCase).publishToMarket(productId, MarketType.ELEVEN_STREET,
			new MarketSalePriceOverrides(new BigDecimal("15"), new BigDecimal("20"), new BigDecimal("5000")));
	}

	@Test
	@DisplayName("가격 바디가 없으면(null) 오버라이드 없이 넘긴다 — 기존 호출부 비파괴")
	void publishToMarket_withoutPriceBody_passesNull() {
		Long productId = 2L;
		when(productPublishUseCase.publishToMarket(eq(productId), eq(MarketType.CAFE24), isNull()))
			.thenReturn(new MarketPublishOutcome(MarketType.CAFE24, Map.of("product_no", "1"), true));
		when(marketRegistrationRepository.findByProductIdAndMarketType(productId, MarketType.CAFE24))
			.thenReturn(Optional.empty());

		controller().publishToMarket(productId, "cafe24", null);

		verify(productPublishUseCase).publishToMarket(productId, MarketType.CAFE24, null);
	}
}
