package com.sbshop.agent.core.application.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.UnsyncReason;
import com.sbshop.agent.core.domain.market.UnsyncReasonClassifier;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UnsyncReasonClassifierTest {

	@Test
	@DisplayName("D-224: 쿠팡 '이미 삭제된 상품입니다'는 DELETED_ON_MARKET 으로 분류한다 — 재등록 대상")
	void coupangDeletedMessage_classifiedAsDeletedOnMarket() {
		assertThat(UnsyncReasonClassifier.classify("해당 상품은 이미 삭제된 상품입니다"))
			.isEqualTo(UnsyncReason.DELETED_ON_MARKET);
	}

	@Test
	@DisplayName("D-224: 존재하지 않는 상품 응답도 DELETED_ON_MARKET 이다")
	void notFoundMessage_classifiedAsDeletedOnMarket() {
		assertThat(UnsyncReasonClassifier.classify("존재하지 않는 상품입니다"))
			.isEqualTo(UnsyncReason.DELETED_ON_MARKET);
	}

	@Test
	@DisplayName("D-224: 검증 거부는 VALIDATION_FAILED 다 — 데이터 고쳐야 하므로 재시도로는 안 풀린다")
	void validationMessage_classifiedAsValidationFailed() {
		assertThat(UnsyncReasonClassifier.classify("유효하지 않은 구매 옵션 값입니다"))
			.isEqualTo(UnsyncReason.VALIDATION_FAILED);
		assertThat(UnsyncReasonClassifier.classify("Enum값을 입력하지 않았거나 허용되지 않은 값입니다"))
			.isEqualTo(UnsyncReason.VALIDATION_FAILED);
	}

	@Test
	@DisplayName("D-224: 순단·타임아웃은 TRANSIENT_ERROR 다 — 그냥 재시도하면 된다")
	void timeoutMessage_classifiedAsTransient() {
		assertThat(UnsyncReasonClassifier.classify("Read timed out"))
			.isEqualTo(UnsyncReason.TRANSIENT_ERROR);
		assertThat(UnsyncReasonClassifier.classify((String)null))
			.isEqualTo(UnsyncReason.TRANSIENT_ERROR);
	}

	@Test
	@DisplayName("D-224: 예외를 넘기면 원인 사슬 끝까지 뒤져 분류한다")
	void throwable_classifiedByRootCause() {
		Throwable wrapped = new RuntimeException("전송 실패",
			new IllegalStateException("해당 상품은 이미 삭제된 상품입니다"));
		assertThat(UnsyncReasonClassifier.classify(wrapped))
			.isEqualTo(UnsyncReason.DELETED_ON_MARKET);
	}

	@Test
	@DisplayName("D-224: 실패 사유가 등록행에 남고, 다시 성공하면 지워진다")
	void registrationRecordsAndClearsReason() {
		MarketRegistration reg = MarketRegistration.builder()
			.productId(1L).marketType(MarketType.COUPANG).build();

		reg.markSyncFailed(UnsyncReason.DELETED_ON_MARKET);
		assertThat(reg.getIsSynced()).isFalse();
		assertThat(reg.getUnsyncReason()).isEqualTo(UnsyncReason.DELETED_ON_MARKET);

		reg.markSynced();
		assertThat(reg.getIsSynced()).isTrue();
		assertThat(reg.getUnsyncReason()).isNull();
	}
}
