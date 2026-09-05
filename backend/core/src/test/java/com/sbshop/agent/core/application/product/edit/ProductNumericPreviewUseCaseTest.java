package com.sbshop.agent.core.application.product.edit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.UnsyncReason;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.component.ProductReader;
import com.sbshop.agent.core.domain.product.vo.PriceInfo;
import com.sbshop.agent.core.domain.product.vo.LogisticsInfo;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProductNumericPreviewUseCaseTest {
	private final ProductReader reader = mock(ProductReader.class);
	private final MarketRegistrationRepository registrations = mock(MarketRegistrationRepository.class);
	private final ProductNumericPreviewUseCase preview = new ProductNumericPreviewUseCase(reader, registrations);

	@BeforeEach
	void noRegistrations() {
		when(registrations.findByProductIdIn(any())).thenReturn(List.of());
	}

	@Test
	void mixedResultsPreserveSelectionOrderAndDoNotWrite() {
		Product valid = product(1L, "2000");
		Product overflow = product(2L, "999999999999999");
		Product unchanged = product(3L, "0");
		Product deleted = product(4L, "100");
		when(deleted.getDeletedAt()).thenReturn(LocalDateTime.now());
		var ids = List.of(3L, 1L, 2L, 4L, 99L);
		when(reader.findAllByIds(ids)).thenReturn(List.of(valid, overflow, unchanged, deleted));
		var response = preview.preview(new ProductNumericPreviewUseCase.Request(ids,
			List.of(new NumericChange(ProductNumericField.SALE_PRICE, NumericChange.Operation.PERCENT,
				new BigDecimal("5"))),
			null));
		assertThat(response.mode()).isEqualTo("READ_ONLY");
		assertThat(response.generatedAt()).isNotNull();
		assertThat(response.valid()).isEqualTo(1);
		assertThat(response.unchanged()).isEqualTo(1);
		assertThat(response.invalid()).isEqualTo(1);
		assertThat(response.notFound()).isEqualTo(2);
		assertThat(response.items()).extracting(ProductNumericPreviewUseCase.Item::productId)
			.containsExactlyElementsOf(ids);
		assertThat(response.items().get(1).fields().getFirst().after()).isEqualTo("2100");
		verify(valid, never()).update(any());
		verify(registrations, never()).save(any());
		verify(reader).findAllByIds(ids);
		verify(registrations).findByProductIdIn(ids);
	}

	@Test
	void defaultPolicyShowsPriceRoundingAndQuantityTruncationWithoutSaving() {
		Product product = product(1L, "12650");
		when(product.getLogisticsInfo()).thenReturn(LogisticsInfo.builder().stock(3).bundleQuantity(3).build());
		when(reader.findAllByIds(List.of(1L))).thenReturn(List.of(product));
		var request = new ProductNumericPreviewUseCase.Request(List.of(1L), List.of(
			new NumericChange(ProductNumericField.SALE_PRICE, NumericChange.Operation.SET, new BigDecimal("12345")),
			new NumericChange(ProductNumericField.STOCK, NumericChange.Operation.PERCENT, new BigDecimal("50")),
			new NumericChange(ProductNumericField.BUNDLE_QUANTITY, NumericChange.Operation.PERCENT,
				new BigDecimal("50"))),
			null);
		assertThat(request.fractionPolicy()).isEqualTo(NumericChange.FractionPolicy.APPLY_FIELD_RULES);
		var result = preview.preview(request);
		assertThat(result.valid()).isEqualTo(1);
		assertThat(result.items().getFirst().fields()).extracting(NumericChangeCalculator.Result::calculated)
			.containsExactly("12345", "4.5", "4.5");
		assertThat(result.items().getFirst().fields()).extracting(NumericChangeCalculator.Result::after)
			.containsExactly("12300", "4", "4");
		assertThat(result.items().getFirst().fields()).allMatch(NumericChangeCalculator.Result::rounded);
		verify(product, never()).update(any());
		verify(registrations, never()).save(any());
	}

	@Test
	void aSingleInvalidFieldExcludesTheProductFromNumericValidCount() {
		Product product = product(1L, "100");
		when(reader.findAllByIds(List.of(1L))).thenReturn(List.of(product));
		var response = preview.preview(new ProductNumericPreviewUseCase.Request(List.of(1L), List.of(
			new NumericChange(ProductNumericField.SALE_PRICE, NumericChange.Operation.ADD, BigDecimal.TEN),
			new NumericChange(ProductNumericField.BUNDLE_QUANTITY, NumericChange.Operation.SET, BigDecimal.ZERO)),
			null));
		assertThat(response.valid()).isZero();
		assertThat(response.invalid()).isEqualTo(1);
		assertThat(response.items().getFirst().fields()).hasSize(2);
	}

	@Test
	void legacyDeletionAndCafe24ChildrenStillRequireExplicitConnectionAndFieldReview() {
		Product product = product(1L, "100");
		when(reader.findAllByIds(List.of(1L))).thenReturn(List.of(product));
		MarketRegistration reg = MarketRegistration.builder().productId(1L).marketType(MarketType.CAFE24)
			.marketIdentifiers("{\"product_no\":\"1\",\"gmarket_goodsNo\":\"2\",\"auction_goodsNo\":\"3\"}").build();
		reg.markAbsentFromMarket(UnsyncReason.DELETED_ON_MARKET);
		when(registrations.findByProductIdIn(List.of(1L))).thenReturn(List.of(reg));
		var response = preview.preview(new ProductNumericPreviewUseCase.Request(List.of(1L),
			List.of(new NumericChange(ProductNumericField.SALE_PRICE, NumericChange.Operation.SET, BigDecimal.TEN)),
			null));
		assertThat(response.items().getFirst().marketCheck())
			.isEqualTo(ProductNumericPreviewUseCase.MarketCheck.REQUIRED);
		assertThat(response.items().getFirst().markets()).containsExactly("AUCTION", "CAFE24", "GMARKET");
		verify(registrations, never()).save(any());
	}

	@Test
	void rejectsDuplicateFieldsAndInvalidIds() {
		var change = new NumericChange(ProductNumericField.SALE_PRICE, NumericChange.Operation.SET, BigDecimal.TEN);
		assertThatThrownBy(() -> new ProductNumericPreviewUseCase.Request(List.of(1L), List.of(change, change), null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ProductNumericPreviewUseCase.Request(List.of(0L), List.of(change), null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(new ProductNumericPreviewUseCase.Request(List.of(1L, 1L), List.of(change), null).productIds())
			.containsExactly(1L);
	}

	private Product product(Long id, String price) {
		Product product = mock(Product.class);
		when(product.getId()).thenReturn(id);
		when(product.getSbCode()).thenReturn("SB" + id);
		when(product.getPriceInfo()).thenReturn(PriceInfo.builder().salePrice(new BigDecimal(price)).build());
		return product;
	}
}
