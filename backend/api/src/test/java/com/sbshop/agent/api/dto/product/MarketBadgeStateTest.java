package com.sbshop.agent.api.dto.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.domain.market.SyncErrorType;
import com.sbshop.agent.core.domain.market.UnsyncReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MarketBadgeStateTest {

	private static final String URL = "https://www.coupang.com/vp/products/123";

	@Test
	@DisplayName("D-222: 마켓에서 삭제된 등록은 DELETED — 등록됨과 눈으로 갈려야 재등록을 유도할 수 있다")
	void deletedOnMarket_yieldsDeletedStatus() {
		MarketBadgeState s = MarketBadgeState.of(true, false, UnsyncReason.DELETED_ON_MARKET, null, URL);
		assertThat(s.status()).isEqualTo("DELETED");
		assertThat(s.reason()).isEqualTo("DELETED_ON_MARKET");
	}

	@Test
	@DisplayName("A안: 마켓엔 있는데 마지막 쓰기가 실패한 상태를 표현한다 — 삭제와 다르다")
	void presentButLastWriteFailed_yieldsFailed() {
		MarketBadgeState s = MarketBadgeState.of(true, true, null, SyncErrorType.BLOCKED_BY_MARKET, URL);
		assertThat(s.status()).isEqualTo("FAILED");
		assertThat(s.reason()).isEqualTo("BLOCKED_BY_MARKET");
		assertThat(s.url()).isEqualTo(URL);
	}

	@Test
	@DisplayName("A안: 검증 실패도 존재를 부정하지 않는다 — 링크는 유지된다")
	void validationFailure_keepsLink() {
		MarketBadgeState s = MarketBadgeState.of(true, true, null, SyncErrorType.VALIDATION_FAILED, URL);
		assertThat(s.status()).isEqualTo("FAILED");
		assertThat(s.url()).isEqualTo(URL);
	}

	@Test
	@DisplayName("D-222: 사유가 없으면 기존 판정을 유지한다 — 레거시 미분류 행을 거짓 경고로 물들이지 않는다")
	void noReason_keepsLegacyBehaviour() {
		assertThat(MarketBadgeState.of(true, false, null, null, URL).status()).isEqualTo("SYNCED");
		assertThat(MarketBadgeState.of(false, false, null, null, null).status()).isEqualTo("PENDING");
	}

	@Test
	@DisplayName("A안: 삭제 판정이 쓰기 오류보다 우선한다 — 없는 상품에 재시도를 권하면 안 된다")
	void deletedWinsOverWriteError() {
		MarketBadgeState s = MarketBadgeState.of(true, false, UnsyncReason.DELETED_ON_MARKET,
			SyncErrorType.VALIDATION_FAILED, URL);
		assertThat(s.status()).isEqualTo("DELETED");
	}

	@Test
	@DisplayName("D-222: 삭제·실패여도 링크는 유지한다 — 마켓에서 실제 상태를 확인할 통로가 필요하다")
	void urlKept_forDeletedAndFailed() {
		assertThat(MarketBadgeState.of(true, false, UnsyncReason.DELETED_ON_MARKET, null, URL).url())
			.isEqualTo(URL);
		assertThat(MarketBadgeState.of(true, true, null, SyncErrorType.TRANSIENT_ERROR, "  ").url()).isNull();
	}

	@Test
	@DisplayName("A안: 식별자가 없으면 쓰기 오류가 있어도 PENDING 이다 — 아직 마켓에 올라간 적이 없다")
	void noIdentifiers_stayPending() {
		assertThat(MarketBadgeState.of(false, false, null, SyncErrorType.TRANSIENT_ERROR, null).status())
			.isEqualTo("PENDING");
	}
}
