package com.sbshop.agent.core.application.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.domain.common.RecordStatus;
import com.sbshop.agent.core.domain.pricing.ForwarderShipping;
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
			field.equals("freeOver") ? value : new BigDecimal("40000"), null))
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
			BigDecimal.ZERO, 0, BigDecimal.ZERO, BigDecimal.ZERO, null);
		assertThat(saved).isSameAs(existing);
		assertThat(saved.getMarginRate()).isZero();
		assertThat(saved.getCouponRate()).isEqualByComparingTo("100");
		assertThat(saved.getShipBaseAmount()).isZero();
		assertThat(saved.getShipStepAmount()).isZero();
		assertThat(saved.getShipBaseWeightG()).isZero();
		assertThat(saved.getShipStepWeightG()).isZero();
		assertThat(saved.getDomesticFee()).isZero();
		assertThat(saved.getDomesticFreeOver()).isZero();

		service.upsert(VendorType.IHB, null, null, null, null, null, null, null, null, null, null, null);
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

	@Test
	@DisplayName("배대지 요금표 다섯 칸을 저장하고, 비우면 지운다 — 국내 배송비 0은 0으로 남는다")
	void upsert_savesAndClearsForwarderRate() {
		VendorPricePolicy existing = VendorPricePolicy.builder().vendor(VendorType.IHB).build();
		when(repository.findByVendorAndStatus(VendorType.IHB, RecordStatus.ACTIVE))
			.thenReturn(Optional.of(existing));
		when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
		var oregon = new ForwarderShipping.Rate(new BigDecimal("10500"), 500, new BigDecimal("2600"), 500, 200);

		service.upsert(VendorType.IHB, new BigDecimal("4"), BigDecimal.ZERO, BigDecimal.ZERO, "KRW", BigDecimal.ZERO,
			0, BigDecimal.ZERO, 0, BigDecimal.ZERO, BigDecimal.ZERO, oregon);
		assertThat(existing.forwarderRate()).isEqualTo(oregon);
		assertThat(existing.getForwarderBaseAmount()).isEqualByComparingTo("10500");
		assertThat(existing.getForwarderPackagingG()).isEqualTo(200);
		assertThat(existing.getDomesticFee()).isZero();
		assertThat(existing.getDomesticFreeOver()).isZero();

		service.upsert(VendorType.IHB, new BigDecimal("4"), BigDecimal.ZERO, BigDecimal.ZERO, "KRW", BigDecimal.ZERO,
			0, BigDecimal.ZERO, 0, BigDecimal.ZERO, BigDecimal.ZERO, null);
		assertThat(existing.getForwarderBaseAmount()).isNull();
		assertThat(existing.getForwarderBaseWeightG()).isNull();
		assertThat(existing.getForwarderStepAmount()).isNull();
		assertThat(existing.getForwarderStepWeightG()).isNull();
		assertThat(existing.getForwarderPackagingG()).isNull();
	}

	@Test
	@DisplayName("새 소싱처 정책 행에도 배대지 요금표를 저장한다")
	void upsert_newRowSavesForwarderRate() {
		when(repository.findByVendorAndStatus(VendorType.IHB, RecordStatus.ACTIVE)).thenReturn(Optional.empty());
		when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
		var oregon = new ForwarderShipping.Rate(new BigDecimal("10500"), 500, new BigDecimal("2600"), 500, 200);

		VendorPricePolicy saved = service.upsert(VendorType.IHB, null, null, null, "KRW", null, null, null, null,
			BigDecimal.ZERO, BigDecimal.ZERO, oregon);

		assertThat(saved.forwarderRate()).isEqualTo(oregon);
	}

	@ParameterizedTest
	@CsvSource(value = {"-0.01, 500, 2600, 500, 200", "100000000, 500, 2600, 500, 200",
		"10500, -1, 2600, 500, 200", "10500, 500, -0.01, 500, 200", "10500, 500, 2600, -1, 200",
		"10500, 500, 2600, 500, -1", "10500, NULL, 2600, 500, 200", "10500, 0, 2600, 500, 200",
		"10500, 500, NULL, 500, 200", "10500, 500, 2600, NULL, 200", "10500, 500, 2600, 0, 200"},
		nullValues = "NULL")
	@DisplayName("배대지 요금표가 음수이거나, 기본 금액이 있는데 기본 무게·단계 금액·단계 무게가 없으면 저장하지 않는다")
	void upsert_rejectsInvalidForwarderRate(BigDecimal base, Integer baseWeight, BigDecimal step,
		Integer stepWeight, Integer packaging) {
		assertThatThrownBy(() -> service.upsert(VendorType.IHB, null, null, null, "KRW", null, null, null, null,
			BigDecimal.ZERO, BigDecimal.ZERO, new ForwarderShipping.Rate(base, baseWeight, step, stepWeight, packaging)))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("배대지");
		verifyNoInteractions(repository);
	}

	@Test
	@DisplayName("배대지 기본 금액이 0이면 나머지 칸이 비어도 저장한다(배대지 미사용)")
	void upsert_allowsDisabledForwarderWithEmptyRest() {
		when(repository.findByVendorAndStatus(VendorType.IHB, RecordStatus.ACTIVE)).thenReturn(Optional.empty());
		when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

		VendorPricePolicy saved = service.upsert(VendorType.IHB, null, null, null, "KRW", null, null, null, null,
			null, null, new ForwarderShipping.Rate(BigDecimal.ZERO, null, null, null, null));

		assertThat(saved.getForwarderBaseAmount()).isZero();
		assertThat(saved.getForwarderBaseWeightG()).isNull();
	}
}
