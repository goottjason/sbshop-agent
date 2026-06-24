package com.sbshop.agent.core.application.order.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.sbshop.agent.core.application.order.port.MarketOrderPort;
import com.sbshop.agent.core.application.order.port.SmartStoreOrderApiPort;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.application.order.dto.MarketOrderDto;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.application.order.mapper.SmartStoreStatusMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 스마트스토어 주문 API 어댑터
 * SmartStoreOrderApiPort를 MarketOrderPort로 래핑하여 통합 인터페이스 제공
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SmartStoreOrderAdapter implements MarketOrderPort {

	private final SmartStoreOrderApiPort smartStoreOrderApiPort;
	private final SmartStoreStatusMapper statusMapper;

	@Override
	public MarketType getMarketType() {
		return MarketType.SMART_STORE;
	}

	@Override
	public List<MarketOrderDto> fetchOrders(MarketCredential credential,
		LocalDate fromDate, LocalDate toDate) {
		List<MarketOrderDto> result = new ArrayList<>();

		String clientId = credential.getClientId();
		String secretKey = credential.getSecretKey();

		ZonedDateTime endDate = ZonedDateTime.now(ZoneId.of("UTC")).truncatedTo(ChronoUnit.SECONDS);
		ZonedDateTime startDate = fromDate.atStartOfDay(ZoneId.of("UTC"));
		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

		ZonedDateTime currentFrom = startDate;
		while (currentFrom.isBefore(endDate)) {
			ZonedDateTime currentTo = currentFrom.plusDays(1);
			if (currentTo.isAfter(endDate)) {
				currentTo = endDate;
			}

			String fromStr = currentFrom.format(formatter);
			String toStr = currentTo.format(formatter);

			try {
				JsonNode orders = smartStoreOrderApiPort.fetchOrders(clientId, secretKey, fromStr, toStr);

				if (orders != null && orders.isArray() && !orders.isEmpty()) {
					for (JsonNode orderNode : orders) {
						JsonNode orderInfo = orderNode.path("order");
						JsonNode productOrderInfo = orderNode.path("productOrder");
						JsonNode deliveryInfo = orderNode.path("delivery");

						String productOrderId = productOrderInfo.path("productOrderId").asText();
						String status = productOrderInfo.path("productOrderStatus").asText();

						if ("PAYMENT_WAITING".equalsIgnoreCase(status)) {
							continue;
						}

						MarketOrderDto dto = parseOrderNode(orderInfo, productOrderInfo, deliveryInfo, status);
						if (dto != null) {
							result.add(dto);
						}
					}
				}

				Thread.sleep(300);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			} catch (Exception e) {
				log.error("스마트스토어 주문 조회 실패 (기간: {} ~ {}): {}", fromStr, toStr, e.getMessage());
			}

			currentFrom = currentTo;
		}

		return result;
	}

	private MarketOrderDto parseOrderNode(JsonNode orderInfo, JsonNode productOrderInfo,
		JsonNode deliveryInfo, String status) {
		try {
			String productOrderId = productOrderInfo.path("productOrderId").asText();

			String orderDateStr = orderInfo.path("orderDate").asText();
			LocalDateTime orderDate = ZonedDateTime.parse(orderDateStr).toLocalDateTime();

			String recipientName = orderInfo.path("ordererName").asText();
			String recipientPhone = orderInfo.path("ordererTel").asText().replace("-", "");

			JsonNode shippingAddress = productOrderInfo.path("shippingAddress");
			String zipCode = shippingAddress.path("zipCode").asText();
			String address = shippingAddress.path("baseAddress").asText() + " "
				+ shippingAddress.path("detailedAddress").asText();
			String receiverName = shippingAddress.path("name").asText();
			String receiverPhone = shippingAddress.path("tel1").asText().replace("-", "");
			String message = productOrderInfo.path("shippingMemo").asText("");

			String customsClearanceNo = productOrderInfo.path("individualCustomUniqueCode").asText(null);
			if ("undefined".equals(customsClearanceNo) || customsClearanceNo == null
				|| customsClearanceNo.trim().isEmpty()) {
				customsClearanceNo = null;
			}

			String ordererName = orderInfo.path("ordererName").asText(null);
			String ordererPhone = orderInfo.path("ordererTel").asText(null);
			if (ordererPhone != null) {
				ordererPhone = ordererPhone.replace("-", "");
			}

			String placeOrderStatus = productOrderInfo.path("placeOrderStatus").asText(null);
			Map<String, String> statusMap = Map.of("status", status, "placeOrderStatus", placeOrderStatus);
			ShippingStatus shippingStatus = statusMapper.mapStatus(statusMap);

			String productName = productOrderInfo.path("productName").asText();
			String sellerProductCode = productOrderInfo.path("sellerProductCode").asText();
			int quantity = productOrderInfo.path("quantity").asInt(1);
			BigDecimal totalPaymentAmount = new BigDecimal(productOrderInfo.path("totalPaymentAmount").asText("0"));
			BigDecimal unitPrice = quantity > 0
				? totalPaymentAmount.divide(BigDecimal.valueOf(quantity), 2, RoundingMode.HALF_UP)
				: BigDecimal.ZERO;

			String trackingNo = deliveryInfo.path("trackingNumber").asText(null);
			String deliveryCompanyCode = deliveryInfo.path("deliveryCompany").asText(null);
			ShippingCarrier carrier = ShippingCarrier.fromMarketCode(deliveryCompanyCode);

			return MarketOrderDto.builder()
				.marketType(getMarketType())
				.marketOrderNo(productOrderId)
				.marketProductCode(sellerProductCode)
				.productName(productName)
				.quantity(quantity)
				.orderPrice(unitPrice)
				.totalAmount(totalPaymentAmount)
				.recipientName(receiverName.isEmpty() ? recipientName : receiverName)
				.recipientPhone(receiverPhone.isEmpty() ? recipientPhone : receiverPhone)
				.zipcode(zipCode)
				.address(address)
				.message(message)
				.ordererName(ordererName)
				.ordererPhone(ordererPhone)
				.customsClearanceNo(customsClearanceNo)
				.trackingNo(trackingNo)
				.carrier(carrier)
				.status(shippingStatus)
				.orderDate(orderDate)
				.build();

		} catch (Exception e) {
			log.error("스마트스토어 주문 파싱 실패: {}", e.getMessage());
			return null;
		}
	}

	private String mapCarrierCode(ShippingCarrier carrier) {
		if (carrier == null) {
			throw new IllegalArgumentException("배송사 정보가 없습니다.");
		}
		return switch (carrier) {
			case CJ_LOGISTICS -> "CJGLS";
			case HANJIN -> "HANJIN";
			case KOREA_POST -> "EPOST";
			case LOTTE_LOGISTICS -> "LOTTE";
			case ROCKET -> "COUPANG";
			default -> "CJGLS";
		};
	}

	@Override
	public void shipOrder(MarketCredential credential,
		Order order, OrderLineItem lineItem,
		String trackingNo, ShippingCarrier carrier) {
		String deliveryCompanyCode = mapCarrierCode(carrier);
		smartStoreOrderApiPort.shipOrder(
			credential.getClientId(),
			credential.getSecretKey(),
			order.getMarketOrderNo(),
			trackingNo,
			deliveryCompanyCode);
	}

	@Override
	public void acceptOrders(MarketCredential credential, Order order) {
		smartStoreOrderApiPort.confirmOrders(
			credential.getClientId(),
			credential.getSecretKey(),
			List.of(order.getMarketOrderNo()));
	}

	@Override
	public void cancelOrder(MarketCredential credential, Order order) {
		smartStoreOrderApiPort.cancelOrders(
			credential.getClientId(),
			credential.getSecretKey(),
			List.of(order.getMarketOrderNo()));
	}
}
