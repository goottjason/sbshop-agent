package com.sbshop.agent.core.application.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.domain.common.RecordStatus;
import com.sbshop.agent.core.domain.pricing.VendorPricePolicy;
import com.sbshop.agent.core.domain.pricing.repository.VendorPricePolicyRepository;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class VendorPricePolicyServiceTest {

	@Mock
	private VendorPricePolicyRepository repository;
	@InjectMocks
	private VendorPricePolicyService service;

	@ParameterizedTest
	@CsvSource({"margin, -0.01", "margin, 100", "coupon, -0.01", "coupon, 100.01",
		"minimum, -0.01", "minimum, 10000000000000", "base, -0.01", "base, 100000000",
		"baseWeight, -1", "step, -0.01", "step, 100000000", "stepWeight, -1",
		"domestic, -0.01", "domestic, 100000000", "freeOver, -0.01", "freeOver, 10000000000000"})
	@DisplayName("공급처 정책의 잘못된 비율·금액·무게는 저장하거나 기존 정책을 변경하지 않는다")
	void upsert_rejectsInvalidNumbersBeforeRepositoryAccess(String field, BigDecimal value) {
		assertThatThrownBy(() -> service.upsert(VendorType.IHB,
			field.equals("margin") ? value : new BigDecimal("15"),
			field.equals("coupon") ? value : new BigDecimal("20"),
			field.equals("minimum") ? value : new BigDecimal("5000"), "USD",
			field.equals("base") ? value : new BigDecimal("10"),
			field.equals("baseWeight") ? value.intValueExact() : 1000,
			field.equals("step") ? value : new BigDecimal("2"),
			field.equals("stepWeight") ? value.intValueExact() : 500,
			field.equals("domestic") ? value : new BigDecimal("6000"),
			field.equals("freeOver") ? value : new BigDecimal("40000")))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("이어야 합니다");
		verifyNoInteractions(repository);
	}

	@Test
	@DisplayName("무료배송 0과 무게 구간 미사용 0 및 정책 초기화 null은 유지한다")
	void upsert_preservesZeroAndNullablePolicyReset() {
		VendorPricePolicy existing = VendorPricePolicy.builder().vendor(VendorType.IHB).build();
		when(repository.findByVendorAndStatus(VendorType.IHB, RecordStatus.ACTIVE))
			.thenReturn(Optional.of(existing));
		when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

		VendorPricePolicy saved = service.upsert(VendorType.IHB, BigDecimal.ZERO,
			new BigDecimal("100"), BigDecimal.ZERO, "USD", BigDecimal.ZERO, 0,
			BigDecimal.ZERO, 0, BigDecimal.ZERO, BigDecimal.ZERO);
		assertThat(saved).isSameAs(existing);
		assertThat(saved.getMarginRate()).isZero();
		assertThat(saved.getCouponRate()).isEqualByComparingTo("100");
		assertThat(saved.getShipBaseAmount()).isZero();
		assertThat(saved.getShipStepAmount()).isZero();
		assertThat(saved.getShipBaseWeightG()).isZero();
		assertThat(saved.getShipStepWeightG()).isZero();
		assertThat(saved.getDomesticFee()).isZero();
		assertThat(saved.getDomesticFreeOver()).isZero();

		service.upsert(VendorType.IHB, null, null, null, null, null, null, null, null, null, null);
		assertThat(existing.getMarginRate()).isNull();
		assertThat(existing.getCouponRate()).isNull();
		assertThat(existing.getMinMarginPrice()).isNull();
		assertThat(existing.getShipBaseAmount()).isNull();
		assertThat(existing.getShipStepAmount()).isNull();
		assertThat(existing.getShipBaseWeightG()).isNull();
		assertThat(existing.getShipStepWeightG()).isNull();
		assertThat(existing.getDomesticFee()).isNull();
		assertThat(existing.getDomesticFreeOver()).isNull();
	}
}
