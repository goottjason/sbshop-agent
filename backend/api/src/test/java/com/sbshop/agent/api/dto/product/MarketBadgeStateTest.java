package com.sbshop.agent.api.dto.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.domain.market.UnsyncReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MarketBadgeStateTest {

	private static final String URL = "https://www.coupang.com/vp/products/123";

	@Test
	@DisplayName("D-222: 마켓에서 삭제된 등록은 DELETED — 등록됨과 눈으로 갈려야 재등록을 유도할 수 있다")
	void deletedOnMarket_yieldsDeletedStatus() {
		MarketBadgeState s = MarketBadgeState.of(true, false, UnsyncReason.DELETED_ON_MARKET, URL);
		assertThat(s.status()).isEqualTo("DELETED");
		assertThat(s.reason()).isEqualTo("DELETED_ON_MARKET");
	}

	@Test
	@DisplayName("D-222: 검증 실패·일시 오류는 FAILED — 사유를 실어 보내 툴팁으로 보여준다")
	void syncFailures_yieldFailedStatusWithReason() {
		assertThat(MarketBadgeState.of(true, false, UnsyncReason.VALIDATION_FAILED, URL).status())
			.isEqualTo("FAILED");
		assertThat(MarketBadgeState.of(true, false, UnsyncReason.VALIDATION_FAILED, URL).reason())
			.isEqualTo("VALIDATION_FAILED");
		assertThat(MarketBadgeState.of(true, false, UnsyncReason.TRANSIENT_ERROR, URL).status())
			.isEqualTo("FAILED");
	}

	@Test
	@DisplayName("D-222: 사유가 없으면 기존 판정을 유지한다 — 레거시 임포트 2,021건을 거짓 경고로 물들이지 않는다")
	void noReason_keepsLegacyBehaviour() {
		assertThat(MarketBadgeState.of(true, false, null, URL).status()).isEqualTo("SYNCED");
		assertThat(MarketBadgeState.of(false, false, null, null).status()).isEqualTo("PENDING");
	}

	@Test
	@DisplayName("D-222: 동기화 성공 상태면 사유가 남아 있어도 SYNCED 가 이긴다")
	void syncedWins_overStaleReason() {
		assertThat(MarketBadgeState.of(true, true, UnsyncReason.DELETED_ON_MARKET, URL).status())
			.isEqualTo("SYNCED");
	}

	@Test
	@DisplayName("D-222: 삭제·실패여도 링크는 유지한다 — 마켓에서 실제 상태를 확인할 통로가 필요하다")
	void urlKept_forDeletedAndFailed() {
		assertThat(MarketBadgeState.of(true, false, UnsyncReason.DELETED_ON_MARKET, URL).url()).isEqualTo(URL);
		assertThat(MarketBadgeState.of(true, false, UnsyncReason.TRANSIENT_ERROR, "  ").url()).isNull();
	}
}
