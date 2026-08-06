package com.sbshop.agent.core.application.order.port;

import java.util.List;
import org.w3c.dom.Element;

/**
 * 11번가 주문 API 포트
 */
public interface ElevenstOrderApiPort {

	/**
	 * 결제완료 주문 목록 조회
	 *
	 * @param apiKey 11번가 API 키
	 * @param startTime 검색시작일 (YYYYMMDDhhmm)
	 * @param endTime 검색종료일 (YYYYMMDDhhmm)
	 * @return 주문 목록 XML 엘리먼트
	 */
	List<Element> fetchCompletedOrders(String apiKey, String startTime, String endTime);

	/**
	 * 발주확인처리
	 *
	 * @param apiKey 11번가 API 키
	 * @param ordNo 주문번호
	 * @param ordPrdSeq 주문순번
	 * @param addPrdYn 추가구성상품여부
	 * @param addPrdNo 추가구성상품번호
	 * @param dlvNo 배송번호
	 */
	void confirmOrder(String apiKey, String ordNo, String ordPrdSeq,
		String addPrdYn, String addPrdNo, String dlvNo);

	/**
	 * 배송준비중 주문 목록 조회
	 *
	 * @param apiKey 11번가 API 키
	 * @param startTime 검색시작일 (YYYYMMDDhhmm)
	 * @param endTime 검색종료일 (YYYYMMDDhhmm)
	 * @return 주문 목록 XML 엘리먼트
	 */
	List<Element> fetchPackagingOrders(String apiKey, String startTime, String endTime);

	/**
	 * 발송처리 (송장 등록)
	 *
	 * @param apiKey 11번가 API 키
	 * @param sendDt 보낸일자 (YYYYMMDDhhmm)
	 * @param dlvMthdCd 배송방식
	 * @param dlvEtprsCd 배송업체코드
	 * @param invcNo 송장번호
	 * @param dlvNo 배송번호
	 */
	void shipOrder(String apiKey, String sendDt, String dlvMthdCd,
		String dlvEtprsCd, String invcNo, String dlvNo);

	/**
	 * 부분발송처리 — 배송 안의 <b>지정한 상품주문만</b> 발송 처리한다.
	 *
	 * <p>{@link #shipOrder}(전체)는 <b>묶음배송번호가 같은 주문번호를 모두</b> 발송 처리한다
	 * (에러코드 -3308 설명). 다품목·묶음배송 주문에서 한 상품주문만 보내려는데 묶음 전체가
	 * 나가면, 아직 준비되지 않은 상품이 발송된 것으로 마켓에 기록된다.
	 *
	 * <p>배송주체가 "업체배송"인 주문만 사용 가능하며 추가구성상품만 부분발송은 불가하다.
	 *
	 * @param ordPrdSeq 대상 상품주문 순번. 문서상 복수 지정 가능({@code "1,2"})
	 */
	void shipOrderPartial(String apiKey, String sendDt, String dlvMthdCd, String dlvEtprsCd,
		String invcNo, String dlvNo, String ordNo, String ordPrdSeq);

	/**
	 * 배송중 주문 목록 조회
	 *
	 * @param apiKey 11번가 API 키
	 * @param startTime 검색시작일 (YYYYMMDDhhmm)
	 * @param endTime 검색종료일 (YYYYMMDDhhmm)
	 * @return 주문 목록 XML 엘리먼트
	 */
	List<Element> fetchShippingOrders(String apiKey, String startTime, String endTime);

	/**
	 * 배송완료 주문 목록 조회
	 *
	 * @param apiKey 11번가 API 키
	 * @param startTime 검색시작일 (YYYYMMDDhhmm)
	 * @param endTime 검색종료일 (YYYYMMDDhhmm)
	 * @return 주문 목록 XML 엘리먼트
	 */
	List<Element> fetchCompletedDeliveryOrders(String apiKey, String startTime, String endTime);

	/**
	 * 개별 주문 상세 조회 (주문번호별 배송정보)
	 *
	 * @param apiKey 11번가 API 키
	 * @param ordNo 주문번호
	 * @return 주문 상세 XML 엘리먼트
	 */
	List<Element> fetchOrderDetail(String apiKey, String ordNo);

	/**
	 * 상품주문별 상태 조회 ({@code claimservice/orderlistall}).
	 *
	 * <p>2단계에서 도입. 응답이 <b>{@code ordPrdSeq}별 행</b>으로 오고 행마다
	 * {@code ordPrdStatNm}·{@code dlvNo}·{@code prdNm}·{@code ordQty}를 준다. 즉 상품주문 상태를
	 * 직접 알려주므로, 종전처럼 "어느 목록에서 왔는가"로 상태를 추론할 필요가 없다(D-126의 구조적 원인).
	 *
	 * <p>{@code orderlistalladdr}(주소 포함 상세)와는 <b>다른 API</b>다.
	 *
	 * @param apiKey  11번가 API 키
	 * @param ordNos  주문번호 콤마 구분 문자열. <b>최대 100건</b> — 호출자가 나눠 넣는다
	 * @return 상품주문 행 XML 엘리먼트
	 */
	List<Element> fetchProductOrderStatuses(String apiKey, String ordNos);
}
