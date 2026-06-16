package com.sbshop.agent.core.application.order;

import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import org.springframework.stereotype.Component;

/**
 * ESM+(G마켓/옥션) 배송 상태를 내부 ShippingStatus로 변환
 *
 * ESM+ deliveryStatusCode:
 * - 1010: 결제완료 → NEW
 * - 1020: 상품준비중 → PREPARING
 * - 1030: 발송준비중 → PURCHASED
 * - 1040: 배송중 → SHIPPED
 * - 1050: 배송완료 → DELIVERED
 * - 1060: 구매결정완료 → DELIVERED
 * - 5010: 송금완료 → DELIVERED
 * - 2010: 주문취소접수 → CANCELED
 * - 2020: 주문취소완료 → CANCELED
 * - 2030: 반품접수 → RETURNED
 * - 2040: 반품완료 → RETURNED
 * - 3030: 반품수거완료 → RETURNED
 * - 2050: 교환접수 → EXCHANGED
 * - 2060: 교환완료 → EXCHANGED
 * - 2070: 취소완료 → CANCELED
 */
@Component
public class EsmplusStatusMapper {

	public ShippingStatus mapStatus(int deliveryStatusCode) {
		return switch (deliveryStatusCode) {
			case 1010 -> ShippingStatus.NEW;
			case 1020 -> ShippingStatus.PREPARING;
			case 1030 -> ShippingStatus.PURCHASED;
			case 1040 -> ShippingStatus.SHIPPED;
			case 1050, 1060, 5010 -> ShippingStatus.DELIVERED;
			case 2010, 2020, 2070 -> ShippingStatus.CANCELED;
			case 2030, 2040, 3030 -> ShippingStatus.RETURNED;
			case 2050, 2060 -> ShippingStatus.EXCHANGED;
			default -> ShippingStatus.UNKNOWN;
		};
	}

	public ShippingStatus mapStatus(String deliveryStatus) {
		if (deliveryStatus == null) {
			return ShippingStatus.UNKNOWN;
		}
		return switch (deliveryStatus) {
			case "결제완료" -> ShippingStatus.NEW;
			case "상품준비중" -> ShippingStatus.PREPARING;
			case "발송준비중" -> ShippingStatus.PURCHASED;
			case "배송중" -> ShippingStatus.SHIPPED;
			case "배송완료", "구매결정완료", "송금완료" -> ShippingStatus.DELIVERED;
			case "주문취소", "취소접수", "취소완료" -> ShippingStatus.CANCELED;
			case "반품접수", "반품완료", "반품수거완료" -> ShippingStatus.RETURNED;
			case "교환접수", "교환완료" -> ShippingStatus.EXCHANGED;
			default -> ShippingStatus.UNKNOWN;
		};
	}
}
