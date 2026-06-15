package com.sbshop.agent.core.application.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.sbshop.agent.core.application.order.port.CoupangOrderApiPort;
import com.sbshop.agent.core.application.order.port.MarketOrderPort;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.dto.MarketOrderDto;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 쿠팡 주문 API 어댑터
 * CoupangOrderApiPort를 MarketOrderPort로 래핑하여 통합 인터페이스 제공
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoupangOrderAdapter implements MarketOrderPort {

	private final CoupangOrderApiPort coupangOrderApiPort;
	private final CoupangStatusMapper statusMapper;
	private final OrderRepository orderRepository;
	private final OrderLineItemRepository orderLineItemRepository;

	@Override
	public MarketType getMarketType() {
		return MarketType.COUPANG;
	}

	@Override
	public List<MarketOrderDto> fetchOrders(MarketCredential credential,
		LocalDate fromDate, LocalDate toDate) {
		List<MarketOrderDto> result = new ArrayList<>();

		String vendorId = credential.getClientId();
		String accessKey = credential.getAccessKey();
		String secretKey = credential.getSecretKey();

		String fromDateStr = fromDate.toString();
		String toDateStr = toDate.toString();

		String[] statuses = {
			"ACCEPT", "INSTRUCT", "DEPARTURE", "DELIVERING", "FINAL_DELIVERY", "NONE_TRACKING"
		};

		for (String status : statuses) {
			try {
				JsonNode orders = coupangOrderApiPort.fetchOrders(
					vendorId, accessKey, secretKey, fromDateStr, toDateStr, status);

				if (orders == null || !orders.isArray()) {
					continue;
				}

				for (JsonNode orderNode : orders) {
					String orderStatus = orderNode.path("status").asText();

					if ("PAYMENT_WAITING".equalsIgnoreCase(orderStatus)
						|| "DEPOSIT_WAITING".equalsIgnoreCase(orderStatus)) {
						continue;
					}

					MarketOrderDto dto = parseOrderNode(orderNode, status);
					if (dto != null) {
						result.add(dto);
					}
				}

				Thread.sleep(1000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			} catch (Exception e) {
				log.error("쿠팡 주문 조회 실패 (status={}): {}", status, e.getMessage());
			}
		}

		return result;
	}

	private MarketOrderDto parseOrderNode(JsonNode orderNode, String status) {
		try {
			String marketOrderNo = orderNode.path("orderId").asText();
			String shipmentBoxId = orderNode.path("shipmentBoxId").asText(null);

			String orderedAtStr = orderNode.path("orderedAt").asText();
			LocalDateTime orderDate = LocalDateTime.parse(orderedAtStr,
				DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));

			JsonNode receiver = orderNode.path("receiver");
			String recipientName = receiver.path("name").asText();
			String recipientPhone = receiver.path("safeNumber").asText();
			String zipcode = receiver.path("postCode").asText();
			String address = receiver.path("addr1").asText() + " " + receiver.path("addr2").asText();
			String message = orderNode.path("parcelPrintMessage").asText();

			JsonNode overseaInfo = orderNode.path("overseaShippingInfoDto");
			String customsClearanceNo = null;
			String ordererName = null;
			String ordererPhone = null;

			if (!overseaInfo.isMissingNode() && !overseaInfo.isNull()) {
				ordererPhone = overseaInfo.path("ordererPhoneNumber").asText(null);
				if (ordererPhone != null && !ordererPhone.isEmpty()) {
					recipientPhone = ordererPhone;
				}
				ordererName = overseaInfo.path("ordererName").asText(null);
				customsClearanceNo = overseaInfo.path("personalCustomsClearanceCode").asText(null);
			}

			if (ordererName == null || ordererName.isEmpty()) {
				ordererName = orderNode.path("orderer").path("name").asText(null);
			}
			if (ordererName == null || ordererName.isEmpty()) {
				ordererName = recipientName;
			}

			String invoiceNo = orderNode.path("invoiceNumber").asText(null);
			if (invoiceNo == null || invoiceNo.isEmpty() || "null".equals(invoiceNo)) {
				invoiceNo = null;
			}
			String deliveryCompanyName = orderNode.path("deliveryCompanyName").asText(null);
			ShippingCarrier carrier = ShippingCarrier.fromMarketCode(deliveryCompanyName);

			Map<String, String> statusMap = Map.of("status", status);
			ShippingStatus shippingStatus = statusMapper.mapStatus(statusMap);

			JsonNode orderItems = orderNode.path("orderItems");
			if (orderItems.isArray() && orderItems.size() > 0) {
				JsonNode firstItem = orderItems.get(0);
				String externalVendorSkuCode = firstItem.path("externalVendorSkuCode").asText(null);
				if (externalVendorSkuCode == null || externalVendorSkuCode.isEmpty()
					|| "null".equals(externalVendorSkuCode)) {
					externalVendorSkuCode = firstItem.path("sellerProductId").asText(null);
				}
				String vendorItemName = firstItem.path("vendorItemName").asText();
				int qty = firstItem.path("shippingCount").asInt();
				BigDecimal price = new BigDecimal(firstItem.path("orderPrice").asText());

				return MarketOrderDto.builder()
					.marketOrderNo(marketOrderNo)
					.marketProductCode(externalVendorSkuCode)
					.productName(vendorItemName)
					.quantity(qty)
					.orderPrice(price)
					.totalAmount(price.multiply(BigDecimal.valueOf(qty)))
					.recipientName(recipientName)
					.recipientPhone(recipientPhone)
					.zipcode(zipcode)
					.address(address)
					.message(message)
					.ordererName(ordererName)
					.ordererPhone(ordererPhone)
					.customsClearanceNo(customsClearanceNo)
					.trackingNo(invoiceNo)
					.carrier(carrier)
					.status(shippingStatus)
					.orderDate(orderDate)
					.shipmentBoxId(shipmentBoxId)
					.build();
			}

			return MarketOrderDto.builder()
				.marketOrderNo(marketOrderNo)
				.recipientName(recipientName)
				.recipientPhone(recipientPhone)
				.zipcode(zipcode)
				.address(address)
				.message(message)
				.ordererName(ordererName)
				.ordererPhone(ordererPhone)
				.customsClearanceNo(customsClearanceNo)
				.trackingNo(invoiceNo)
				.carrier(carrier)
				.status(shippingStatus)
				.orderDate(orderDate)
				.shipmentBoxId(shipmentBoxId)
				.build();

		} catch (Exception e) {
			log.error("쿠팡 주문 파싱 실패: {}", e.getMessage());
			return null;
		}
	}

	@Override
	public void shipOrder(MarketCredential credential,
		Order order, OrderLineItem lineItem,
		String trackingNo, ShippingCarrier carrier) {
		String vendorItemId = lineItem.getMarketProductCode();
		if (vendorItemId == null || vendorItemId.isEmpty()) {
			throw new IllegalArgumentException("쿠팡 배송 처리 실패: vendorItemId가 없습니다.");
		}

		String deliveryCompanyCode = mapCarrierCode(carrier);
		coupangOrderApiPort.shipOrder(
			credential.getClientId(),
			credential.getAccessKey(),
			credential.getSecretKey(),
			order.getMarketOrderNo(),
			vendorItemId,
			trackingNo,
			deliveryCompanyCode);
	}

	@Override
	public void acceptOrders(MarketCredential credential, Order order) {
		if (order.getShipmentBoxId() == null || order.getShipmentBoxId().isEmpty()) {
			throw new IllegalStateException(
				"쿠팡 발주확인 실패: shipmentBoxId가 없습니다. order=" + order.getMarketOrderNo());
		}

		coupangOrderApiPort.acceptOrders(
			credential.getClientId(),
			credential.getAccessKey(),
			credential.getSecretKey(),
			List.of(order.getShipmentBoxId()));
	}

	@Override
	public Map<String, BigDecimal> querySettlement(MarketCredential credential,
		LocalDate from, LocalDate to) {
		Map<String, BigDecimal> settlementMap = new HashMap<>();

		try {
			JsonNode salesItems = coupangOrderApiPort.querySalesDetails(
				credential.getClientId(),
				credential.getAccessKey(),
				credential.getSecretKey(),
				from.toString(),
				to.toString());

			if (salesItems == null || !salesItems.isArray()) {
				return settlementMap;
			}

			for (JsonNode orderItem : salesItems) {
				JsonNode itemsArray = orderItem.path("items");
				if (itemsArray.isArray()) {
					for (JsonNode item : itemsArray) {
						String sbCode = item.path("externalSellerSkuCode").asText(null);
						String settlementAmountStr = item.path("settlementAmount").asText(null);
						if (sbCode != null && !sbCode.isEmpty()
							&& settlementAmountStr != null && !settlementAmountStr.isEmpty()) {
							try {
								settlementMap.put(sbCode, new BigDecimal(settlementAmountStr));
							} catch (NumberFormatException e) {
								log.warn("정산금액 변환 실패: sbCode={}, amount={}", sbCode, settlementAmountStr);
							}
						}
					}
				}
			}
		} catch (Exception e) {
			log.error("쿠팡 정산 조회 실패: {}", e.getMessage());
		}

		return settlementMap;
	}

	public void detectCancellations(List<MarketOrderDto> apiOrders, LocalDate fromDate, LocalDate toDate) {
		java.util.Set<String> apiOrderIds = new java.util.HashSet<>();
		for (MarketOrderDto dto : apiOrders) {
			apiOrderIds.add(dto.getMarketOrderNo());
		}

		List<Order> dbOrders = orderRepository.findByMarketType(MarketType.COUPANG);
		int canceledCount = 0;

		for (Order order : dbOrders) {
			if (order.getOrderDate() != null) {
				LocalDate orderDate = order.getOrderDate().toLocalDate();
				if (orderDate.isBefore(fromDate) || orderDate.isAfter(toDate)) {
					continue;
				}
			}

			if (!apiOrderIds.contains(order.getMarketOrderNo())) {
				List<OrderLineItem> items = orderLineItemRepository.findByOrderId(order.getId());
				boolean hasNonTerminal = items.stream().anyMatch(item -> item.getShippingData() == null
					|| (item.getShippingData().getShippingStatus() != ShippingStatus.CANCELED
						&& item.getShippingData().getShippingStatus() != ShippingStatus.DELIVERED));

				if (hasNonTerminal) {
					for (OrderLineItem item : items) {
						if (item.getShippingData() == null
							|| (item.getShippingData().getShippingStatus() != ShippingStatus.CANCELED
								&& item.getShippingData().getShippingStatus() != ShippingStatus.DELIVERED)) {
							item.updateShippingWithCarrier(null, ShippingStatus.CANCELED, null, null);
							orderLineItemRepository.save(item);
						}
					}
					canceledCount++;
				}
			}
		}

		if (canceledCount > 0) {
			log.info("쿠팡 취소 감지: {}건 CANCELED로 업데이트", canceledCount);
		}
	}

	public void fixCarriers(List<MarketOrderDto> apiOrders) {
		Map<String, MarketOrderDto> apiOrderMap = new HashMap<>();
		for (MarketOrderDto dto : apiOrders) {
			apiOrderMap.put(dto.getMarketOrderNo(), dto);
		}

		List<Order> dbOrders = orderRepository.findByMarketType(MarketType.COUPANG);
		int carrierFixedCount = 0;

		for (Order order : dbOrders) {
			MarketOrderDto apiOrder = apiOrderMap.get(order.getMarketOrderNo());
			if (apiOrder == null)
				continue;

			List<OrderLineItem> items = orderLineItemRepository.findByOrderId(order.getId());
			for (OrderLineItem item : items) {
				if (item.getShippingData() == null)
					continue;

				boolean needsCarrierFix = item.getShippingData()
					.getShippingCarrier() == ShippingCarrier.ETC
					&& apiOrder.getCarrier() != null
					&& apiOrder.getCarrier() != ShippingCarrier.ETC;

				boolean needsInvoiceFix = (item.getShippingData().getTrackingNo() == null
					|| item.getShippingData().getTrackingNo().isEmpty())
					&& apiOrder.getTrackingNo() != null
					&& !apiOrder.getTrackingNo().isEmpty();

				if (needsCarrierFix || needsInvoiceFix) {
					item.updateShippingWithCarrier(
						needsInvoiceFix ? apiOrder.getTrackingNo() : item.getShippingData().getTrackingNo(),
						item.getShippingData().getShippingStatus(),
						item.getShippingData().getIsUnipassDone(),
						needsCarrierFix ? apiOrder.getCarrier() : item.getShippingData().getShippingCarrier());
					orderLineItemRepository.save(item);
					carrierFixedCount++;
				}
			}
		}

		if (carrierFixedCount > 0) {
			log.info("쿠팡 택배사 보정: {}건 업데이트", carrierFixedCount);
		}
	}
}
