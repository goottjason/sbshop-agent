package com.sbshop.agent.core.application.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * F-PSRC-13 / R3 멱등성: {@link MarketRegistrationTxService#savePending}의 재게시 멱등 동작 검증.
 * <ul>
 *   <li>순차 재호출: 기존 행이 있으면 재사용(insert 안 함).</li>
 *   <li>동시 재호출 경쟁: insert가 유니크 제약 위반(DataIntegrityViolationException)을 받으면
 *       상대 트랜잭션이 커밋한 행을 재조회해 재사용(멱등 보장).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class MarketRegistrationTxServiceSavePendingTest {

	@Mock
	private MarketRegistrationRepository repository;

	@InjectMocks
	private MarketRegistrationTxService service;

	private static final Long PRODUCT_ID = 1L;
	private static final MarketType MARKET = MarketType.COUPANG;

	@Test
	@DisplayName("순차 재호출: 기존 등록행이 있으면 재사용하고 새로 insert하지 않는다")
	void sequentialRecall_reusesExistingRow() {
		MarketRegistration existing = MarketRegistration.builder()
			.productId(PRODUCT_ID).marketType(MARKET).marketProductName("기존").build();
		when(repository.findByProductIdAndMarketType(PRODUCT_ID, MARKET))
			.thenReturn(Optional.of(existing));

		MarketRegistration result = service.savePending(PRODUCT_ID, MARKET, "새이름");

		assertThat(result).isSameAs(existing);
		verify(repository, never()).save(any());
	}

	@Test
	@DisplayName("동시 경쟁: insert가 유니크 제약 위반을 받으면 상대가 커밋한 행을 재조회해 재사용한다")
	void concurrentRace_recoversByRequery() {
		MarketRegistration winner = MarketRegistration.builder()
			.productId(PRODUCT_ID).marketType(MARKET).marketProductName("먼저커밋").build();
		// 1차 조회는 비어있어 insert 시도 → 제약 위반 → 재조회 시 상대 행 발견.
		when(repository.findByProductIdAndMarketType(PRODUCT_ID, MARKET))
			.thenReturn(Optional.empty())
			.thenReturn(Optional.of(winner));
		when(repository.save(any())).thenThrow(new DataIntegrityViolationException("uk violation"));

		MarketRegistration result = service.savePending(PRODUCT_ID, MARKET, "이름");

		assertThat(result).isSameAs(winner);
		verify(repository).save(any());
	}

	@Test
	@DisplayName("제약 위반 후 재조회도 비어있으면(비정상) 원래 예외를 전파한다")
	void constraintViolationButRequeryEmpty_rethrows() {
		when(repository.findByProductIdAndMarketType(PRODUCT_ID, MARKET))
			.thenReturn(Optional.empty())
			.thenReturn(Optional.empty());
		when(repository.save(any())).thenThrow(new DataIntegrityViolationException("uk violation"));

		assertThatThrownBy(() -> service.savePending(PRODUCT_ID, MARKET, "이름"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}
}
