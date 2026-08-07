package com.sbshop.agent.infrastructure.client.smartstore;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 스마트스토어 발송 API 응답이 <b>실제로 수락됐는지</b> 판정한다 (D-145).
 *
 * <p>이 마켓은 <b>HTTP 200 본문 안에 실패를 담아</b> 준다:
 *
 * <pre>
 * {"data":{"successProductOrderIds":[],
 *          "failProductOrderInfos":[{"productOrderId":"…","code":"9999",
 *                                    "message":"주문상태 및 클레임상태를 확인하세요"}]}}
 * </pre>
 *
 * <p>종전에는 최상위 {@code code}만 검사해 이 응답이 "전송 완료"로 기록됐다. 그 거짓 성공이
 * {@code trackingSentToMarket=true}까지 찍어, 마켓에 없는 송장을 있다고 표시하고 다음 동기화가
 * 마켓 값으로 되돌렸다(2026-08-07 실측: 교정 11:34 → 원복 11:38).
 *
 * <p><b>모르는 형태는 실패로 위조하지 않는다.</b> 판정 근거(성공/실패 목록·최상위 코드)가 응답에
 * 없으면 통과시킨다 — 마켓이 응답 형태를 바꿨을 때 멀쩡한 전송을 실패로 만들지 않기 위해서다.
 */
public final class SmartStoreDispatchResult {

	private SmartStoreDispatchResult() {}

	/**
	 * @param response       발송 API 응답 본문
	 * @param productOrderId 이번에 발송 처리한 상품주문번호(실패 메시지에 담는다)
	 * @throws RuntimeException 마켓이 거부했으면 코드와 사유를 담아 던진다
	 */
	public static void verifyAccepted(JsonNode response, String productOrderId) {
		if (response == null) {
			return;
		}

		// 종전 형태: 최상위 code가 채워진 오류 응답(인증 실패 등).
		String topLevelCode = response.path("code").asText("");
		if (!topLevelCode.isEmpty()) {
			throw new RuntimeException("스마트스토어 발송 실패(" + topLevelCode + "): "
				+ response.path("message").asText(""));
		}

		JsonNode failures = response.path("data").path("failProductOrderInfos");
		if (failures.isArray() && !failures.isEmpty()) {
			JsonNode first = failures.get(0);
			throw new RuntimeException("스마트스토어 발송 실패("
				+ first.path("code").asText("") + "): " + first.path("message").asText("")
				+ " — 상품주문 " + first.path("productOrderId").asText(productOrderId));
		}
	}
}
