package com.sbshop.agent.core.application.product;

import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.util.List;
import java.util.Map;

/**
 * 완전 상품 삭제(F-PROD-27/28)에서 연동 마켓 리스팅 삭제 결과.
 *
 * <p>{@code deleted} = 마켓 리스팅 삭제 성공, {@code skipped} = 클라이언트 없는 마켓(GMARKET/AUCTION),
 * {@code failed} = 마켓별 삭제 실패 사유. best-effort라 failed가 있어도 등록행·Product는 삭제되므로,
 * failed는 사용자가 해당 마켓 리스팅을 수동 정리하기 위한 유일한 추적 근거다(등록행은 이미 삭제됨).
 */
public record ProductDeleteResult(
	List<MarketType> deleted,
	List<MarketType> skipped,
	Map<MarketType, String> failed) {
}
