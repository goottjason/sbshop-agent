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
}
