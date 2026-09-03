package com.sbshop.agent.core.application.order.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.sbshop.agent.core.application.order.port.MarketOrderPort;
import com.sbshop.agent.core.application.order.port.SmartStoreOrderApiPort;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.application.order.dto.MarketLineItemDto;
import com.sbshop.agent.core.application.order.dto.MarketOrderDto;
import com.sbshop.agent.core.application.order.dto.MarketShipmentDto;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.vo.ClaimData;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SmartStoreOrderAdapter implements MarketOrderPort {
	private final SmartStoreOrderApiPort smartStoreOrderApiPort;
	private final SmartStoreStatusMapper statusMapper;

	long chunkDelayMillis = 2_000L;
	long retryBackoffMillis = 5_000L;
	static final int MAX_RETRIES = 3;

	@Override
	public MarketType getMarketType() {
		return MarketType.SMART_STORE;
	}

	@Override
	public List<MarketOrderDto> fetchOrders(MarketCredential credential,
		LocalDate fromDate, LocalDate toDate) {
		LinkedHashMap<String, LinkedHashMap<String, ProductOrderRow>> grouped = new LinkedHashMap<>();

		String clientId = credential.getClientId();
		String secretKey = credential.getSecretKey();

		ZonedDateTime endDate = ZonedDateTime.now(ZoneId.of("UTC")).truncatedTo(ChronoUnit.SECONDS);
		ZonedDateTime startDate = fromDate.atStartOfDay(ZoneId.of("UTC"));
		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

		int successChunks = 0;
		int failedChunks = 0;
		Exception lastFailure = null;

		ZonedDateTime currentFrom = startDate;
		while (currentFrom.isBefore(endDate)) {
			ZonedDateTime currentTo = currentFrom.plusDays(1);
			if (currentTo.isAfter(endDate)) {
				currentTo = endDate;
			}

			String fromStr = currentFrom.format(formatter);
			String toStr = currentTo.format(formatter);

			try {
				JsonNode orders = fetchChunkWithRetry(clientId, secretKey, fromStr, toStr);

				if (orders != null && orders.isArray() && !orders.isEmpty()) {
					for (JsonNode orderNode : orders) {
						JsonNode orderInfo = orderNode.path("order");
						JsonNode productOrderInfo = orderNode.path("productOrder");
						JsonNode deliveryInfo = orderNode.path("delivery");

						String status = productOrderInfo.path("productOrderStatus").asText();

						if ("PAYMENT_WAITING".equalsIgnoreCase(status)) {
							continue;
						}

						ProductOrderRow row = parseProductOrder(orderInfo, productOrderInfo, deliveryInfo, status);
						if (row != null) {
							grouped.computeIfAbsent(row.orderKey(), k -> new LinkedHashMap<>())
								.put(row.productOrderId(), row);
						}
					}
				}

				successChunks++;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			} catch (Exception e) {
				failedChunks++;
				lastFailure = e;
				log.error("스마트스토어 주문 조회 실패 (기간: {} ~ {}): {}", fromStr, toStr, e.getMessage());
			}

			if (!sleepQuietly(chunkDelayMillis)) {
				break;
			}

			currentFrom = currentTo;
		}

		if (successChunks == 0 && failedChunks > 0) {
			String detail = lastFailure != null ? lastFailure.getMessage() : "알 수 없는 오류";
			throw new RuntimeException("스마트스토어 주문 조회 실패: " + detail, lastFailure);
		}
		if (failedChunks > 0) {
			log.warn("스마트스토어 주문 부분 조회: {} chunk 성공, {} chunk 실패 (마지막 오류: {})",
				successChunks, failedChunks, lastFailure != null ? lastFailure.getMessage() : "-");
		}

		List<MarketOrderDto> result = new ArrayList<>();
		for (LinkedHashMap<String, ProductOrderRow> productOrders : grouped.values()) {
			result.add(toOrderDto(productOrders));
		}
		return result;
	}

	@Override
	public void shipOrder(MarketCredential credential,
		Order order, OrderLineItem lineItem,
		String trackingNo, ShippingCarrier carrier) {
		String deliveryCompanyCode = mapCarrierCode(carrier);
		smartStoreOrderApiPort.shipOrder(
			credential.getClientId(),
			credential.getSecretKey(),
			resolveDispatchTarget(order, lineItem),
			trackingNo,
			deliveryCompanyCode);
	}

	@Override
	public void acceptOrders(MarketCredential credential, Order order) {
		smartStoreOrderApiPort.confirmOrders(
			credential.getClientId(),
			credential.getSecretKey(),
			resolveProductOrderIds(order));
	}

	@Override
	public void cancelOrder(MarketCredential credential, Order order) {
		smartStoreOrderApiPort.cancelOrders(
			credential.getClientId(),
			credential.getSecretKey(),
			resolveProductOrderIds(order));
	}

	private record ProductOrderRow(
		String orderKey, String productOrderId, String packageNumber,
		LocalDateTime orderDate, String recipientName, String recipientPhone,
		String zipcode, String address, String message,
		String ordererName, String ordererPhone, String customsClearanceNo,
		String productName, String sellerProductCode, int quantity,
		BigDecimal unitPrice, BigDecimal totalAmount, BigDecimal settlementAmount,
		ShippingStatus status, ClaimData claim, String trackingNo, ShippingCarrier carrier, String deliveryStatus) {
		String shipmentKey() {
			return (packageNumber != null && !packageNumber.isBlank()) ? packageNumber : productOrderId;
		}
	}

	private JsonNode fetchChunkWithRetry(String clientId, String secretKey, String fromStr, String toStr)
		throws InterruptedException {
		RuntimeException lastError = null;
		long backoff = retryBackoffMillis;

		for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
			try {
				return smartStoreOrderApiPort.fetchOrders(clientId, secretKey, fromStr, toStr);
			} catch (RuntimeException e) {
				if (!isRateLimited(e)) {
					throw e;
				}
				lastError = e;
				if (attempt == MAX_RETRIES) {
					break;
				}
				log.warn("스마트스토어 429 — {}ms 후 재시도 ({}/{}, 기간: {} ~ {})",
					backoff, attempt + 1, MAX_RETRIES, fromStr, toStr);
				Thread.sleep(backoff);
				backoff *= 2;
			}
		}
		throw lastError;
	}

	private boolean isRateLimited(Exception e) {
		String message = e.getMessage();
		return message != null
			&& (message.contains("429") || message.contains("TOO_MANY_REQUESTS"));
	}

	private boolean sleepQuietly(long millis) {
		if (millis <= 0) {
			return true;
		}
		try {
			Thread.sleep(millis);
			return true;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	private MarketOrderDto toOrderDto(LinkedHashMap<String, ProductOrderRow> productOrders) {
		ProductOrderRow representative = null;
		LinkedHashMap<String, List<ProductOrderRow>> byShipment = new LinkedHashMap<>();
		for (ProductOrderRow row : productOrders.values()) {
			representative = row;
			byShipment.computeIfAbsent(row.shipmentKey(), k -> new ArrayList<>()).add(row);
		}

		List<MarketShipmentDto> shipments = new ArrayList<>();
		for (Map.Entry<String, List<ProductOrderRow>> entry : byShipment.entrySet()) {
			ProductOrderRow withTracking = entry.getValue().stream()
				.filter(r -> r.trackingNo() != null && !r.trackingNo().isBlank())
				.findFirst().orElse(null);
			List<MarketLineItemDto> lineItems = new ArrayList<>();
			for (ProductOrderRow row : entry.getValue()) {
				Map<String, Object> lineData = new HashMap<>();
				lineData.put("productOrderId", row.productOrderId());
				if (row.packageNumber() != null) {
					lineData.put("packageNumber", row.packageNumber());
				}
				lineItems.add(MarketLineItemDto.builder()
					.marketLineItemNo(row.productOrderId())
					.marketProductCode(row.sellerProductCode())
					.productName(row.productName())
					.quantity(row.quantity())
					.orderPrice(row.unitPrice())
					.totalAmount(row.totalAmount())
					.settlementAmount(row.settlementAmount())
					.status(row.status())
					.claim(row.claim())
					.marketSpecificData(lineData)
					.build());
			}
			shipments.add(MarketShipmentDto.builder()
				.marketShipmentNo(entry.getKey())
				.trackingNo(withTracking != null ? withTracking.trackingNo() : null)
				.carrier(withTracking != null ? withTracking.carrier() : null)
				.deliveryStatus(withTracking != null ? withTracking.deliveryStatus() : null)
				.lineItems(lineItems)
				.build());
		}

		Map<String, Object> orderData = new HashMap<>();
		orderData.put("productOrderIds", String.join("|", productOrders.keySet()));

		return MarketOrderDto.builder()
			.marketType(getMarketType())
			.marketOrderNo(representative.orderKey())
			.marketProductCode(representative.sellerProductCode())
			.productName(representative.productName())
			.quantity(representative.quantity())
			.orderPrice(representative.unitPrice())
			.totalAmount(representative.totalAmount())
			.recipientName(representative.recipientName())
			.recipientPhone(representative.recipientPhone())
			.zipcode(representative.zipcode())
			.address(representative.address())
			.message(representative.message())
			.ordererName(representative.ordererName())
			.ordererPhone(representative.ordererPhone())
			.customsClearanceNo(representative.customsClearanceNo())
			.status(representative.status())
			.orderDate(representative.orderDate())
			.marketSpecificData(orderData)
			.shipments(shipments)
			.build();
	}

	private ProductOrderRow parseProductOrder(JsonNode orderInfo, JsonNode productOrderInfo,
		JsonNode deliveryInfo, String status) {
		try {
			String productOrderId = productOrderInfo.path("productOrderId").asText();
			String orderId = orderInfo.path("orderId").asText(null);
			String orderKey = (orderId != null && !orderId.isBlank()) ? orderId : productOrderId;
			String packageNumber = productOrderInfo.path("packageNumber").asText(null);

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

			String placeOrderStatus = productOrderInfo.path("placeOrderStatus").asText("");
			Map<String, String> statusMap = Map.of("status", status, "placeOrderStatus", placeOrderStatus);
			ShippingStatus shippingStatus = statusMapper.mapStatus(statusMap);

			String claimType = productOrderInfo.path("claimType").asText(null);
			String claimStatus = productOrderInfo.path("claimStatus").asText(null);
			ClaimData claim = statusMapper.mapClaim(status, claimType, claimStatus);

			String productName = productOrderInfo.path("productName").asText();
			String sellerProductCode = productOrderInfo.path("sellerProductCode").asText();
			int quantity = productOrderInfo.path("quantity").asInt(1);
			BigDecimal totalPaymentAmount = new BigDecimal(productOrderInfo.path("totalPaymentAmount").asText("0"));
			BigDecimal unitPrice = quantity > 0
				? totalPaymentAmount.divide(BigDecimal.valueOf(quantity), 2, RoundingMode.HALF_UP)
				: BigDecimal.ZERO;

			String trackingNo = deliveryInfo.path("trackingNumber").asText(null);
			String deliveryCompanyCode = deliveryInfo.path("deliveryCompany").asText(null);
			ShippingCarrier carrier = ShippingCarrier.fromMarketCode(deliveryCompanyCode,
				"SMART_STORE productOrderId=" + productOrderInfo.path("productOrderId").asText(""));
			String deliveryStatus = deliveryInfo.path("deliveryStatus").asText(null);

			BigDecimal settlementAmount = null;
			String settlementRaw = productOrderInfo.path("expectedSettlementAmount").asText(null);
			if (settlementRaw != null && !settlementRaw.isBlank()) {
				try {
					settlementAmount = new BigDecimal(settlementRaw);
				} catch (NumberFormatException ignore) {
					log.warn("스마트스토어 정산예정금액 파싱 실패: productOrderId={}, value={}",
						productOrderId, settlementRaw);
				}
			}

			return new ProductOrderRow(
				orderKey, productOrderId, packageNumber, orderDate,
				receiverName.isEmpty() ? recipientName : receiverName,
				receiverPhone.isEmpty() ? recipientPhone : receiverPhone,
				zipCode, address, message, ordererName, ordererPhone, customsClearanceNo,
				productName, sellerProductCode, quantity, unitPrice, totalPaymentAmount,
				settlementAmount, shippingStatus, claim, trackingNo, carrier, deliveryStatus);
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
			case LOTTE_LOGISTICS, HYUNDAI_LOGISTICS -> "HYUNDAI";
			case ROCKET -> "COUPANG";
			default -> throw new IllegalArgumentException(
				"스마트스토어에 보낼 택배사 코드를 알 수 없습니다: " + carrier);
		};
	}

	private String resolveDispatchTarget(Order order, OrderLineItem lineItem) {
		String key = lineItem != null ? lineItem.getMarketLineItemNo() : null;
		if (key != null && !key.isBlank()) {
			return key;
		}
		List<String> ids = resolveProductOrderIds(order);
		if (ids.size() == 1) {
			return ids.get(0);
		}
		throw new IllegalArgumentException(
			"스마트스토어 발송처리 불가 — 상품주문번호를 특정할 수 없습니다: order=" + order.getMarketOrderNo()
				+ ", 후보=" + ids + " (주문 동기화로 라인아이템 상품주문번호를 먼저 확보해야 합니다)");
	}

	private List<String> resolveProductOrderIds(Order order) {
		Map<String, String> data = order.getMarketSpecificDataMap();
		String joined = data != null ? data.get("productOrderIds") : null;
		if (joined != null && !joined.isBlank()) {
			List<String> ids = new ArrayList<>();
			for (String part : joined.split("[|,]")) {
				String id = part.trim();
				if (!id.isEmpty()) {
					ids.add(id);
				}
			}
			if (!ids.isEmpty()) {
				return ids;
			}
		}
		String legacy = order.getMarketOrderNo();
		return (legacy != null && !legacy.isBlank()) ? List.of(legacy) : List.of();
	}
}
