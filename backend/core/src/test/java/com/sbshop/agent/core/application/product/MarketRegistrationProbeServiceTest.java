package com.sbshop.agent.core.application.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.UnsyncReason;
import com.sbshop.agent.core.domain.market.client.MarketClient;
import com.sbshop.agent.core.domain.market.client.MarketClientRouter;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MarketRegistrationProbeServiceTest {

	@Mock
	private MarketRegistrationRepository marketRegistrationRepository;
	@Mock
	private MarketClientRouter marketClientRouter;
	@Mock
	private MarketClient client;

	private static final MarketType MARKET = MarketType.COUPANG;

	private MarketRegistrationProbeService service() {
		return new MarketRegistrationProbeService(marketRegistrationRepository, marketClientRouter);
	}

	private MarketRegistration unclassified() {
		MarketRegistration reg = MarketRegistration.builder()
			.productId(44L).marketType(MARKET)
			.marketIdentifiers("{\"sellerProductId\":\"11583638672\"}")
			.marketDetailedInfo("{}").build();
		when(marketRegistrationRepository.findUnclassifiedUnsynced(MARKET)).thenReturn(List.of(reg));
		when(marketClientRouter.hasClient(MARKET)).thenReturn(true);
		when(marketClientRouter.getClient(MARKET)).thenReturn(client);
		return reg;
	}

	@Test
	@DisplayName("D-222: 마켓 조회가 '이미 삭제된 상품'으로 실패하면 DELETED_ON_MARKET 을 확정 기록한다 — 배지가 갈릴 재료를 만든다")
	void deletedOnMarket_isRecorded() {
		MarketRegistration reg = unclassified();
		when(client.checkPresence(anyString()))
			.thenReturn(com.sbshop.agent.core.domain.market.MarketPresence.ABSENT);

		List<MarketRegistrationProbeService.ProbeOutcome> out = service().probe(MARKET, 10, 0, false);

		assertThat(out).singleElement()
			.satisfies(o -> assertThat(o.result()).isEqualTo("DELETED"));
		assertThat(reg.getUnsyncReason()).isEqualTo(UnsyncReason.DELETED_ON_MARKET);
		verify(marketRegistrationRepository).save(reg);
	}

	@Test
	@DisplayName("D-222: 마켓에 살아 있으면 아무것도 쓰지 않는다 — 프로브는 한 방향으로만 기록한다")
	void aliveOnMarket_writesNothing() {
		MarketRegistration reg = unclassified();
		when(client.checkPresence(anyString()))
			.thenReturn(com.sbshop.agent.core.domain.market.MarketPresence.PRESENT);

		List<MarketRegistrationProbeService.ProbeOutcome> out = service().probe(MARKET, 10, 0, false);

		assertThat(out).singleElement().satisfies(o -> assertThat(o.result()).isEqualTo("ALIVE"));
		assertThat(reg.getUnsyncReason()).isNull();
		verify(marketRegistrationRepository, never()).save(any());
	}

	@Test
	@DisplayName("D-222: 삭제인지 알 수 없는 실패(타임아웃 등)는 INCONCLUSIVE — 추측으로 삭제 처리하지 않는다")
	void ambiguousFailure_recordsNothing() {
		MarketRegistration reg = unclassified();
		when(client.checkPresence(anyString()))
			.thenReturn(com.sbshop.agent.core.domain.market.MarketPresence.UNKNOWN);

		List<MarketRegistrationProbeService.ProbeOutcome> out = service().probe(MARKET, 10, 0, false);

		assertThat(out).singleElement().satisfies(o -> assertThat(o.result()).isEqualTo("INCONCLUSIVE"));
		assertThat(reg.getUnsyncReason()).isNull();
		verify(marketRegistrationRepository, never()).save(any());
	}

	@Test
	@DisplayName("D-222: dryRun 은 마켓을 조회하지도, 기록하지도 않는다")
	void dryRun_touchesNothing() {
		unclassified();

		List<MarketRegistrationProbeService.ProbeOutcome> out = service().probe(MARKET, 10, 0, true);

		assertThat(out).singleElement().satisfies(o -> assertThat(o.result()).isEqualTo("DRY_RUN"));
		verify(client, never()).checkPresence(anyString());
		verify(marketRegistrationRepository, never()).save(any());
	}
}
