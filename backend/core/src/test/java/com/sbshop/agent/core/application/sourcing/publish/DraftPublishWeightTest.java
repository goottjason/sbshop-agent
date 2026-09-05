package com.sbshop.agent.core.application.sourcing.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.product.MarketRegistrationTxService;
import com.sbshop.agent.core.application.product.ProductCreateUseCase;
import com.sbshop.agent.core.application.product.dto.BulkProductCreateResult;
import com.sbshop.agent.core.domain.market.client.MarketClientRouter;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.pricing.LandedCostCalculator;
import com.sbshop.agent.core.domain.pricing.VendorPricePolicy;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.dto.ProductCreateCommand;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import com.sbshop.agent.core.domain.sourcing.MarketDraft;
import com.sbshop.agent.core.domain.sourcing.ProductDraft;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DraftPublishWeightTest {

	@Mock
	private ProductCreateUseCase productCreateUseCase;
	@Mock
	private MarketClientRouter marketClientRouter;
	@Mock
	private MarketRegistrationTxService registrationTxService;
	@Mock
	private DraftPublishTxService draftPublishTxService;
	@Spy
	private ObjectMapper objectMapper = new ObjectMapper();
	@InjectMocks
	private DraftPublishUseCase useCase;

	@ParameterizedTest
	@CsvSource({"100, 0.1", "125, 0.125", "125.01, 0.12501", "1200, 1.2", "0, 0"})
	void gramsReachProductAsExactKilograms(String grams, String kilograms) {
		Product product = publish(new BigDecimal(grams));
		assertThat(product.getLogisticsInfo().getWeight()).isEqualByComparingTo(kilograms);
	}

	@Test
	void convertedWeightUsesCorrectShippingTier() {
		Product product = publish(new BigDecimal("1200"));
		VendorPricePolicy policy = VendorPricePolicy.builder().vendor(VendorType.COK)
			.shipBaseAmount(new BigDecimal("10.5")).shipBaseWeightG(500)
			.shipStepAmount(new BigDecimal("2")).shipStepWeightG(500).build();
		assertThat(LandedCostCalculator.buyPricePerUnit(new BigDecimal("10000"),
			product.getLogisticsInfo().getWeight(), 1, policy, new BigDecimal("1000")))
			.isEqualByComparingTo("24500");
	}

	@Test
	void missingWeightIsNotInventedAtTheConversionBoundary() {
		publish(null);
		ArgumentCaptor<List<ProductCreateCommand>> captor = ArgumentCaptor.forClass(List.class);
		verify(productCreateUseCase).createBulk(captor.capture());
		assertThat(captor.getValue().getFirst().weight()).isNull();
	}

	@ParameterizedTest
	@CsvSource({"-1", "125.001"})
	void invalidWeightFailsBeforeAnyPublishingStateOrMarketWrite(String grams) {
		when(draftPublishTxService.requireDraft(1L)).thenReturn(draft(new BigDecimal(grams)));
		assertThatThrownBy(() -> useCase.publish(1L)).isInstanceOf(IllegalArgumentException.class);
		verify(draftPublishTxService, never()).markPublishing(any());
		verifyNoInteractions(productCreateUseCase, marketClientRouter, registrationTxService);
	}

	private Product publish(BigDecimal grams) {
		when(draftPublishTxService.requireDraft(1L)).thenReturn(draft(grams));
		var created = new java.util.ArrayList<Product>();
		when(productCreateUseCase.createBulk(any())).thenAnswer(invocation -> {
			List<ProductCreateCommand> commands = invocation.getArgument(0);
			Product product = Product.create("SB-WEIGHT", commands.getFirst());
			created.add(product);
			return new BulkProductCreateResult(List.of(new BulkProductCreateResult.Success(0, product)), List.of());
		});
		useCase.publish(1L);
		return created.getFirst();
	}

	private ProductDraft draft(BigDecimal grams) {
		ProductDraft draft = ProductDraft.builder().baseNameKo("상품").brand("브랜드")
			.vendor("IHB").bundleQty(1).marginRate(new BigDecimal("20"))
			.weightG(grams).costPrice(BigDecimal.TEN).build();
		draft.acknowledgeCustoms(true);
		MarketDraft market = MarketDraft.builder().marketType(MarketType.COUPANG)
			.productName("상품").categoryId("1").salePrice(new BigDecimal("10000")).build();
		market.applyValidation("[]", true);
		draft.putMarketDraft(market);
		return draft;
	}
}
