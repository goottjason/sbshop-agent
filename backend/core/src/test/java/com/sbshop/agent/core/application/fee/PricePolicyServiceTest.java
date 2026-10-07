package com.sbshop.agent.core.application.fee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.domain.common.RecordStatus;
import com.sbshop.agent.core.domain.fee.PricePolicy;
import com.sbshop.agent.core.domain.fee.repository.PricePolicyRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@ExtendWith(MockitoExtension.class)
class PricePolicyServiceTest {

	@Mock
	private PricePolicyRepository pricePolicyRepository;

	@InjectMocks
	private PricePolicyService pricePolicyService;

	@Test
	@DisplayName("ACTIVE 정책 행이 있으면 그 행을 반환한다")
	void get_returnsActivePolicy() {
		when(pricePolicyRepository.findFirstByStatusOrderByIdAsc(RecordStatus.ACTIVE))
			.thenReturn(Optional.of(policy("15", "20", "5000")));

		PricePolicy policy = pricePolicyService.get();

		assertThat(policy.getMarginRate()).isEqualByComparingTo("15");
		assertThat(policy.getCouponRate()).isEqualByComparingTo("20");
		assertThat(policy.getMinMarginPrice()).isEqualByComparingTo("5000");
	}

	@Test
	@DisplayName("정책 행이 없으면 null을 반환한다 — 호출부는 현행 동작을 유지한다")
	void get_noRow_returnsNull() {
		when(pricePolicyRepository.findFirstByStatusOrderByIdAsc(RecordStatus.ACTIVE))
			.thenReturn(Optional.empty());

		assertThat(pricePolicyService.get()).isNull();
	}

	@Test
	@DisplayName("정책 행이 없으면 새로 생성해 저장한다")
	void update_noRow_createsPolicy() {
		when(pricePolicyRepository.findFirstByStatusOrderByIdAsc(RecordStatus.ACTIVE))
			.thenReturn(Optional.empty());
		when(pricePolicyRepository.save(any())).thenAnswer(i -> i.getArgument(0));
		ArgumentCaptor<PricePolicy> captor = ArgumentCaptor.forClass(PricePolicy.class);

		pricePolicyService.update(new BigDecimal("15"), new BigDecimal("20"), new BigDecimal("5000"));

		verify(pricePolicyRepository).save(captor.capture());
		assertThat(captor.getValue().getMarginRate()).isEqualByComparingTo("15");
		assertThat(captor.getValue().getCouponRate()).isEqualByComparingTo("20");
		assertThat(captor.getValue().getMinMarginPrice()).isEqualByComparingTo("5000");
	}

	@Test
	@DisplayName("정책 행이 있으면 새 행을 만들지 않고 기존 행을 갱신한다")
	void update_existingRow_updatesInPlace() {
		PricePolicy existing = policy("15", "20", "5000");
		when(pricePolicyRepository.findFirstByStatusOrderByIdAsc(RecordStatus.ACTIVE))
			.thenReturn(Optional.of(existing));
		when(pricePolicyRepository.save(any())).thenAnswer(i -> i.getArgument(0));

		PricePolicy saved = pricePolicyService.update(new BigDecimal("18"), new BigDecimal("25"),
			new BigDecimal("7000"));

		assertThat(saved).isSameAs(existing);
		assertThat(existing.getMarginRate()).isEqualByComparingTo("18");
		assertThat(existing.getCouponRate()).isEqualByComparingTo("25");
		assertThat(existing.getMinMarginPrice()).isEqualByComparingTo("7000");
	}

	@ParameterizedTest
	@CsvSource({"-0.01, 20, 5000", "100, 20, 5000", "99.999, 20, 5000",
		"15, -0.01, 5000", "15, 100.01, 5000", "15, 20, -0.01",
		"15, 20, 10000000000000"})
	@DisplayName("가격 계산을 깨뜨리거나 저장 범위를 넘는 정책은 기존 정책에 접근하기 전에 거부한다")
	void update_rejectsInvalidNumbersBeforeRepositoryAccess(BigDecimal marginRate,
		BigDecimal couponRate, BigDecimal minMarginPrice) {
		assertThatThrownBy(() -> pricePolicyService.update(marginRate, couponRate, minMarginPrice))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("이어야 합니다");
		verifyNoInteractions(pricePolicyRepository);
	}

	@Test
	@DisplayName("마진과 최소마진 0, 쿠폰 100%, 정책 초기화 null은 기존 허용 동작을 유지한다")
	void update_preservesZeroFullCouponAndReset() {
		PricePolicy existing = policy("15", "20", "5000");
		when(pricePolicyRepository.findFirstByStatusOrderByIdAsc(RecordStatus.ACTIVE))
			.thenReturn(Optional.of(existing));
		when(pricePolicyRepository.save(any())).thenAnswer(i -> i.getArgument(0));

		pricePolicyService.update(BigDecimal.ZERO, new BigDecimal("100"), BigDecimal.ZERO);
		assertThat(existing.getMarginRate()).isZero();
		assertThat(existing.getCouponRate()).isEqualByComparingTo("100");
		assertThat(existing.getMinMarginPrice()).isZero();

		pricePolicyService.update(null, null, null);
		assertThat(existing.getMarginRate()).isNull();
		assertThat(existing.getCouponRate()).isNull();
		assertThat(existing.getMinMarginPrice()).isNull();
	}

	private PricePolicy policy(String marginRate, String couponRate, String minMarginPrice) {
		return PricePolicy.builder()
			.marginRate(new BigDecimal(marginRate))
			.couponRate(new BigDecimal(couponRate))
			.minMarginPrice(new BigDecimal(minMarginPrice))
			.build();
	}
}
