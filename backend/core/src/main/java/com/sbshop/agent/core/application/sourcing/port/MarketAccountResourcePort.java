package com.sbshop.agent.core.application.sourcing.port;

import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.util.Map;

/**
 * 마켓 계정에 종속된 등록 리소스를 마켓 API로 <b>자동 조회</b>한다
 * (스마트스토어 주소록 ID, Cafe24 진열분류 번호 등).
 *
 * <p>이런 값은 판매자 계정마다 다르고 화면에서 찾아 옮겨 적기 번거롭지만,
 * 한 번 정해지면 거의 바뀌지 않는다. 그래서 <b>조회 후 캐시</b>가 맞다 —
 * 상품마다 API를 치면 등록 속도만 느려진다.
 *
 * <p>조회 실패는 예외가 아니라 <b>빈 맵</b>이다. 그러면 해당 필드가 비고
 * {@code MarketRequiredFieldValidator}가 "필수필드 미충족"으로 잡아 검수 화면에 표시한다 —
 * 조용히 빈 값으로 마켓에 보내 400을 받는 것보다 낫다.
 */
public interface MarketAccountResourcePort {

	MarketType market();

	/**
	 * 마켓별 계정 리소스. 키는 {@code MarketDraft.extraFields}에 그대로 들어가는 이름이다.
	 * (스마트스토어: {@code shippingAddressId}, {@code returnAddressId})
	 */
	Map<String, String> resolve();

	/** 캐시를 비우고 다음 호출 때 다시 조회하게 한다(주소록을 바꿨을 때). */
	void invalidate();
}
