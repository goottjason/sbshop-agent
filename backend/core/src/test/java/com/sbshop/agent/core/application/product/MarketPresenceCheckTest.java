package com.sbshop.agent.core.application.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.domain.market.MarketFailureClassifier;
import com.sbshop.agent.core.domain.market.MarketPresence;
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
class MarketPresenceCheckTest {

	@Mock
	private MarketRegistrationRepository marketRegistrationRepository;
	@Mock
	private MarketClientRouter marketClientRouter;
	@Mock
	private MarketClient client;

	private static final MarketType MARKET = MarketType.COUPANG;

	@Test
	@DisplayName("D-232: 마켓이 '상품삭제' 상태를 돌려주면 삭제다 — 조회가 성공했다고 존재하는 게 아니다")
	void deletedStatusName_isAbsence() {
		assertThat(MarketFailureClassifier.indicatesDeletedStatus("상품삭제")).isTrue();
		assertThat(MarketFailureClassifier.indicatesDeletedStatus("승인완료")).isFalse();
		assertThat(MarketFailureClassifier.indicatesDeletedStatus("심사중")).isFalse();
		assertThat(MarketFailureClassifier.indicatesDeletedStatus(null)).isFalse();
	}

	@Test
	@DisplayName("D-232: 프로브가 존재 판정을 클라이언트에 위임한다 — ABSENT 면 삭제로 기록")
	void probeUsesPresenceCheck_absentIsRecorded() {
		MarketRegistration reg = unclassified();
		when(client.checkPresence(anyString())).thenReturn(MarketPresence.ABSENT);

		List<MarketRegistrationProbeService.ProbeOutcome> out = service().probe(MARKET, 10, 0, false, false);

		assertThat(out).singleElement().satisfies(o -> assertThat(o.result()).isEqualTo("DELETED"));
		assertThat(reg.getUnsyncReason()).isEqualTo(UnsyncReason.DELETED_ON_MARKET);
	}

	@Test
	@DisplayName("D-232: PRESENT 여야만 승격한다 — 삭제 상태 상품을 살아있다고 올리지 않는다")
	void onlyPresentIsPromoted() {
		MarketRegistration reg = unclassified();
		when(client.checkPresence(anyString())).thenReturn(MarketPresence.PRESENT);

		List<MarketRegistrationProbeService.ProbeOutcome> out = service().probe(MARKET, 10, 0, false, true);

		assertThat(out).singleElement().satisfies(o -> assertThat(o.result()).isEqualTo("PROMOTED"));
		assertThat(reg.getIsSynced()).isTrue();
	}

	@Test
	@DisplayName("D-232: UNKNOWN 은 아무것도 쓰지 않는다 — 판정 불가는 판정이 아니다")
	void unknownWritesNothing() {
		MarketRegistration reg = unclassified();
		when(client.checkPresence(anyString())).thenReturn(MarketPresence.UNKNOWN);

		List<MarketRegistrationProbeService.ProbeOutcome> out = service().probe(MARKET, 10, 0, false, true);

		assertThat(out).singleElement().satisfies(o -> assertThat(o.result()).isEqualTo("INCONCLUSIVE"));
		assertThat(reg.getIsSynced()).isFalse();
		assertThat(reg.getUnsyncReason()).isNull();
		verify(marketRegistrationRepository, never()).save(any());
	}

	private MarketRegistrationProbeService service() {
		return new MarketRegistrationProbeService(marketRegistrationRepository, marketClientRouter);
	}

	private MarketRegistration unclassified() {
		MarketRegistration reg = MarketRegistration.builder()
			.productId(44L).marketType(MARKET)
			.marketIdentifiers("{\"sellerProductId\":\"11002709448\"}")
			.marketDetailedInfo("{}").build();
		when(marketRegistrationRepository.findUnclassifiedUnsynced(MARKET)).thenReturn(List.of(reg));
		when(marketClientRouter.hasClient(MARKET)).thenReturn(true);
		when(marketClientRouter.getClient(MARKET)).thenReturn(client);
		return reg;
	}
}
