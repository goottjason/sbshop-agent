package com.sbshop.agent.core.application.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sbshop.agent.core.domain.market.MarketFailureClassifier;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.SyncErrorType;
import com.sbshop.agent.core.domain.market.UnsyncReason;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MarketFailureClassifierTest {

	@Test
	@DisplayName("A안: 부재 신호만 삭제로 본다 — 쿠팡 두 표현 모두 인식")
	void deletedSignals() {
		assertThat(MarketFailureClassifier.indicatesDeleted("해당 상품은 이미 삭제된 상품입니다")).isTrue();
		assertThat(MarketFailureClassifier.indicatesDeleted(
			"400 Bad Request: \"{\"message\":\"Product(14813282146) data not found.\"}\"")).isTrue();
	}

	@Test
	@DisplayName("A안: 쓰기 실패는 부재가 아니다 — 상품은 마켓에 그대로 있다")
	void writeFailuresAreNotAbsence() {
		assertThat(MarketFailureClassifier.indicatesDeleted("해당 상품은 심사가 진행중입니다.")).isFalse();
		assertThat(MarketFailureClassifier.indicatesDeleted("유효하지 않은 구매 옵션 값입니다")).isFalse();
		assertThat(MarketFailureClassifier.indicatesDeleted("Read timed out")).isFalse();
	}

	@Test
	@DisplayName("상품번호에 404 가 들어 있다고 삭제로 판정하지 않는다 — 숫자 상태코드 매칭의 함정")
	void productIdContaining404_isNotMistakenForDeleted() {
		assertThat(MarketFailureClassifier.indicatesDeleted(
			"400 Bad Request: \"{\"message\":\"Product(14813281404) is invalid.\"}\"")).isFalse();
	}

	@Test
	@DisplayName("BLOCKED_BY_MARKET 신설: 심사중·판매중지는 재시도로 안 풀린다")
	void blockedByMarket() {
		assertThat(MarketFailureClassifier.classifyError("해당 상품은 심사가 진행중입니다."))
			.isEqualTo(SyncErrorType.BLOCKED_BY_MARKET);
		assertThat(MarketFailureClassifier.classifyError("판매중지 상태입니다"))
			.isEqualTo(SyncErrorType.BLOCKED_BY_MARKET);
	}

	@Test
	@DisplayName("검증 거부는 VALIDATION_FAILED, 나머지는 TRANSIENT_ERROR")
	void validationAndTransient() {
		assertThat(MarketFailureClassifier.classifyError("유효하지 않은 구매 옵션 값입니다"))
			.isEqualTo(SyncErrorType.VALIDATION_FAILED);
		assertThat(MarketFailureClassifier.classifyError("Enum값을 입력하지 않았거나 허용되지 않은 값입니다"))
			.isEqualTo(SyncErrorType.VALIDATION_FAILED);
		assertThat(MarketFailureClassifier.classifyError("Read timed out"))
			.isEqualTo(SyncErrorType.TRANSIENT_ERROR);
		assertThat(MarketFailureClassifier.classifyError((String)null))
			.isEqualTo(SyncErrorType.TRANSIENT_ERROR);
	}

	@Test
	@DisplayName("예외는 원인 사슬 끝까지 훑는다 — 겉면은 대개 무의미한 래핑이다")
	void walksCauseChain() {
		Throwable wrapped = new RuntimeException("Coupang API 호출 실패",
			new IllegalStateException("해당 상품은 이미 삭제된 상품입니다"));
		assertThat(MarketFailureClassifier.indicatesDeleted(wrapped)).isTrue();
		assertThat(MarketFailureClassifier.classifyError(
			new RuntimeException("전송 실패", new IllegalStateException("심사가 진행중입니다"))))
			.isEqualTo(SyncErrorType.BLOCKED_BY_MARKET);
	}

	@Test
	@DisplayName("A안 불변식: 부재는 사유 없이 만들 수 없다")
	void absenceRequiresReason() {
		MarketRegistration reg = MarketRegistration.builder()
			.productId(1L).marketType(MarketType.COUPANG).build();
		assertThatThrownBy(() -> reg.markAbsentFromMarket(null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("A안: 쓰기 오류를 기록해도 is_synced 는 건드리지 않는다 — 존재와 쓰기결과는 별개다")
	void recordSyncErrorDoesNotTouchPresence() {
		MarketRegistration reg = MarketRegistration.builder()
			.productId(1L).marketType(MarketType.COUPANG).build();
		reg.markSynced();

		reg.recordSyncError(SyncErrorType.BLOCKED_BY_MARKET);

		assertThat(reg.getIsSynced()).isTrue();
		assertThat(reg.getUnsyncReason()).isNull();
		assertThat(reg.getLastSyncError()).isEqualTo(SyncErrorType.BLOCKED_BY_MARKET);
	}

	@Test
	@DisplayName("A안: 쓰기 성공은 두 필드를 모두 지운다")
	void successClearsBoth() {
		MarketRegistration reg = MarketRegistration.builder()
			.productId(1L).marketType(MarketType.COUPANG).build();
		reg.markAbsentFromMarket(UnsyncReason.DELETED_ON_MARKET);
		reg.recordSyncError(SyncErrorType.VALIDATION_FAILED);

		reg.markSynced();

		assertThat(reg.getIsSynced()).isTrue();
		assertThat(reg.getUnsyncReason()).isNull();
		assertThat(reg.getLastSyncError()).isNull();
	}

	@Test
	@DisplayName("A안: 존재 확인은 부재 사유만 지운다 — 쓰기 오류 이력은 남긴다")
	void confirmPresentKeepsWriteError() {
		MarketRegistration reg = MarketRegistration.builder()
			.productId(1L).marketType(MarketType.COUPANG).build();
		reg.markAbsentFromMarket(UnsyncReason.DELETED_ON_MARKET);
		reg.recordSyncError(SyncErrorType.VALIDATION_FAILED);

		reg.confirmPresentOnMarket();

		assertThat(reg.getIsSynced()).isTrue();
		assertThat(reg.getUnsyncReason()).isNull();
		assertThat(reg.getLastSyncError()).isEqualTo(SyncErrorType.VALIDATION_FAILED);
	}
}
