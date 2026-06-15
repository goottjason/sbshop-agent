package com.sbshop.agent.core.application.order.port;

import com.sbshop.agent.core.domain.order.dto.MarketOrderDto;
import java.time.LocalDate;
import java.util.List;

/**
 * ESM+(G마켓/옥션) 주문 스크래핑 포트
 * Selenium 기반 웹 스크래핑으로 주문 데이터 수집
 */
public interface EsmplusOrderApiPort {

	/**
	 * ESM+ 로그인 후 주문 데이터 스크래핑
	 *
	 * @param masterId 마스터 ID
	 * @param password 비밀번호
	 * @param fromDate 조회 시작일
	 * @param toDate 조회 종료일
	 * @return 주문 DTO 목록
	 */
	List<MarketOrderDto> fetchOrders(String masterId, String password,
		LocalDate fromDate, LocalDate toDate);
}
