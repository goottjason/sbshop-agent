package com.sbshop.agent.infrastructure.client.smartstore;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * D-145: 스마트스토어 발송 API는 <b>HTTP 200 본문 안에 실패를 담아</b> 준다.
 *
 * <p>종전에는 최상위 {@code code}만 검사해서, 아래 라이브 응답이 그대로 "전송 완료"로 기록됐다.
 * 그 결과 마켓에 반영되지 않은 송장이 반영됨으로 마킹되고, 다음 동기화가 마켓 값으로 되돌렸다
 * (2026-08-07 실측: 교정 11:34 → 원복 11:38).
 */
class SmartStoreDispatchResponseTest {

	private final ObjectMapper mapper = new ObjectMapper();

	private void verify(String json) throws Exception {
		SmartStoreDispatchResult.verifyAccepted(mapper.readTree(json), "2026073137353041");
	}

	@Test
	@DisplayName("실패 목록(failProductOrderInfos)이 있으면 실패로 판정한다 — 코드와 사유를 담아 던진다")
	void failListMeansFailure() {
		String live = """
			{"data":{"successProductOrderIds":[],
			  "failProductOrderInfos":[{"productOrderId":"2026073137353041",
			    "code":"9999","message":"주문상태 및 클레임상태를 확인하세요"}]}}""";

		assertThatThrownBy(() -> verify(live))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("9999")
			.hasMessageContaining("주문상태 및 클레임상태를 확인하세요");
	}

	@Test
	@DisplayName("택배사 코드 오류(104119)도 실패다 — 종전에는 이것도 성공으로 기록됐다")
	void carrierCodeErrorMeansFailure() {
		String live = """
			{"data":{"successProductOrderIds":[],
			  "failProductOrderInfos":[{"productOrderId":"2026073137353041",
			    "code":"104119","message":"택배사코드 확인"}]}}""";

		assertThatThrownBy(() -> verify(live))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("104119");
	}

	@Test
	@DisplayName("성공 목록에 상품주문번호가 있으면 통과한다")
	void successListPasses() {
		String ok = """
			{"data":{"successProductOrderIds":["2026073137353041"],"failProductOrderInfos":[]}}""";

		assertThatCode(() -> verify(ok)).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("최상위 code가 있는 종전 형태의 오류도 계속 실패로 판정한다(회귀)")
	void legacyTopLevelCodeStillFails() {
		String legacy = """
			{"code":"GW.AUTHN","message":"인증에 실패했습니다"}""";

		assertThatThrownBy(() -> verify(legacy))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("인증에 실패했습니다");
	}

	@Test
	@DisplayName("판정 근거가 없는 응답은 통과시킨다 — 모르는 형태를 실패로 위조하지 않는다")
	void unknownShapePasses() {
		assertThatCode(() -> verify("{\"data\":{}}")).doesNotThrowAnyException();
	}
}
