package com.sbshop.agent.core.application.order.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.sbshop.agent.core.application.order.port.CoupangCancelOrderRequest;
import com.sbshop.agent.core.application.order.port.CoupangInvoiceUploadRequest;
import com.sbshop.agent.core.application.order.port.CoupangOrderApiPort;
import com.sbshop.agent.core.application.order.port.CoupangUpdateInvoiceRequest;
import com.sbshop.agent.core.application.order.port.MarketOrderPort;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.application.order.dto.MarketLineItemDto;
import com.sbshop.agent.core.application.order.dto.MarketFetchOutcome;
import com.sbshop.agent.core.application.order.dto.MarketOrderDto;
import com.sbshop.agent.core.application.order.dto.MarketShipmentDto;
import com.sbshop.agent.core.application.order.dto.ShippingUpdateCommand;
import com.sbshop.agent.core.domain.order.Shipment;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.application.order.mapper.CoupangStatusMapper;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.order.repository.ShipmentRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CoupangOrderAdapter implements MarketOrderPort {
	private final CoupangOrderApiPort coupangOrderApiPort;
	private final CoupangStatusMapper statusMapper;
	private final OrderRepository orderRepository;
	private final OrderLineItemRepository orderLineItemRepository;
	private final MarketRegistrationRepository marketRegistrationRepository;
	private final ShipmentRepository shipmentRepository;

	@Override
	public MarketType getMarketType() {
		return MarketType.COUPANG;
	}

	@Override
	public List<MarketOrderDto> fetchOrders(MarketCredential credential,
		LocalDate fromDate, LocalDate toDate) {
		return doFetchOrders(credential, fromDate, toDate).orders();
	}

	public MarketFetchOutcome fetchOrdersWithOutcome(MarketCredential credential,
		LocalDate fromDate, LocalDate toDate) {
		return doFetchOrders(credential, fromDate, toDate);
	}

	@Override
	public void shipOrder(MarketCredential credential,
		Order order, OrderLineItem lineItem,
		String trackingNo, ShippingCarrier carrier) {
		if (lineItem.getProductId() == null) {
			throw new IllegalArgumentException("쿠팡 배송 처리 실패: productId가 없습니다.");
		}

		Optional<MarketRegistration> reg = marketRegistrationRepository
			.findByProductIdAndMarketType(lineItem.getProductId(), MarketType.COUPANG);
		String vendorItemIdStr = reg.map(MarketRegistration::extractVendorItemId).orElse(null);

		if (vendorItemIdStr == null || vendorItemIdStr.isEmpty()) {
			throw new IllegalArgumentException("쿠팡 배송 처리 실패: vendorItemId가 없습니다.");
		}

		String shipmentBoxId = resolveShipmentBoxId(order, lineItem, "배송 처리");
		String deliveryCompanyCode = mapCarrierCode(carrier);

		var request = new CoupangInvoiceUploadRequest(
			credential.getClientId(),
			List.of(new CoupangInvoiceUploadRequest.InvoiceApply(
				Long.parseLong(shipmentBoxId),
				Long.parseLong(order.getMarketOrderNo()),
				Long.parseLong(vendorItemIdStr),
				deliveryCompanyCode,
				trackingNo,
				false,
				false,
				"")));

		coupangOrderApiPort.shipOrder(credential, request);
	}

	@Override
	public void updateTracking(MarketCredential credential,
		Order order, OrderLineItem lineItem,
		String trackingNo, ShippingCarrier carrier) {
		if (lineItem.getProductId() == null) {
			throw new IllegalArgumentException("쿠팡 송장 수정 실패: productId가 없습니다.");
		}

		Optional<MarketRegistration> reg = marketRegistrationRepository
			.findByProductIdAndMarketType(lineItem.getProductId(), MarketType.COUPANG);
		String vendorItemIdStr = reg.map(MarketRegistration::extractVendorItemId).orElse(null);

		if (vendorItemIdStr == null || vendorItemIdStr.isEmpty()) {
			throw new IllegalArgumentException("쿠팡 송장 수정 실패: vendorItemId가 없습니다.");
		}

		String shipmentBoxId = resolveShipmentBoxId(order, lineItem, "송장 수정");
		String deliveryCompanyCode = mapCarrierCode(carrier);

		var request = new CoupangUpdateInvoiceRequest(
			credential.getClientId(),
			List.of(new CoupangUpdateInvoiceRequest.InvoiceApply(
				Long.parseLong(shipmentBoxId),
				Long.parseLong(order.getMarketOrderNo()),
				Long.parseLong(vendorItemIdStr),
				deliveryCompanyCode,
				trackingNo,
				false,
				false,
				"")));

		coupangOrderApiPort.updateTracking(credential, request);
	}

	@Override
	public void acceptOrders(MarketCredential credential, Order order) {
		List<String> boxIds = new ArrayList<>();
		if (shipmentRepository != null) {
			for (var shipment : shipmentRepository.findByOrderId(order.getId())) {
				String no = shipment.getMarketShipmentNo();
				if (no != null && !no.isEmpty() && !boxIds.contains(no)) {
					boxIds.add(no);
				}
			}
		}
		if (boxIds.isEmpty()) {
			throw new IllegalStateException(
				"쿠팡 발주확인 실패: 이 주문의 배송을 찾을 수 없습니다. order=" + order.getMarketOrderNo()
					+ " (주문 동기화로 배송을 먼저 확보해야 합니다)");
		}

		coupangOrderApiPort.acceptOrders(credential, boxIds);
	}

	@Override
	public void cancelOrder(MarketCredential credential, Order order) {
		List<OrderLineItem> items = orderLineItemRepository.findByOrderId(order.getId());
		if (items.isEmpty()) {
			throw new IllegalStateException("쿠팡 주문취소 실패: 라인아이템이 없습니다. order=" + order.getMarketOrderNo());
		}

		List<Long> vendorItemIds = new ArrayList<>();
		List<Integer> receiptCounts = new ArrayList<>();

		for (OrderLineItem item : items) {
			if (item.getProductId() == null) {
				continue;
			}

			Optional<MarketRegistration> reg = marketRegistrationRepository
				.findByProductIdAndMarketType(item.getProductId(), MarketType.COUPANG);
			String vendorItemIdStr = reg.map(MarketRegistration::extractVendorItemId).orElse(null);

			if (vendorItemIdStr != null && !vendorItemIdStr.isEmpty()) {
				vendorItemIds.add(Long.parseLong(vendorItemIdStr));
				receiptCounts.add(item.getQuantity() != null ? item.getQuantity() : 1);
			}
		}

		if (vendorItemIds.isEmpty()) {
			throw new IllegalStateException("쿠팡 주문취소 실패: vendorItemId가 없습니다. order=" + order.getMarketOrderNo());
		}

		var request = new CoupangCancelOrderRequest(
			Long.parseLong(order.getMarketOrderNo()),
			vendorItemIds,
			receiptCounts,
			"CANERR",
			"CCTTER",
			credential.getClientId(),
			credential.getClientId());

		coupangOrderApiPort.cancelOrder(credential, request);
	}

	@Override
	public Map<String, BigDecimal> querySettlement(MarketCredential credential,
		LocalDate from, LocalDate to) {
		Map<String, BigDecimal> settlementMap = new HashMap<>();

		try {
			JsonNode salesItems = coupangOrderApiPort.querySalesDetails(
				credential,
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

	private static final class OrderAccumulator {
		private final String marketOrderNo;
		private final List<MarketShipmentDto> shipments = new ArrayList<>();

		private String recipientName;
		private String recipientPhone;
		private String zipcode;
		private String address;
		private String message;
		private String ordererName;
		private String ordererPhone;
		private String customsClearanceNo;
		private LocalDateTime orderDate;

		private OrderAccumulator(String marketOrderNo) {
			this.marketOrderNo = marketOrderNo;
		}

		private void fillOrderCommon(JsonNode row) {
			JsonNode receiver = row.path("receiver");
			recipientName = firstNonNull(recipientName, emptyToNull(receiver.path("name").asText(null)));
			ordererPhone = firstNonNull(ordererPhone, emptyToNull(receiver.path("safeNumber").asText(null)));
			zipcode = firstNonNull(zipcode, emptyToNull(receiver.path("postCode").asText(null)));
			if (address == null) {
				String a1 = receiver.path("addr1").asText("");
				String a2 = receiver.path("addr2").asText("");
				address = emptyToNull((a1 + " " + a2).trim());
			}
			message = firstNonNull(message, emptyToNull(row.path("parcelPrintMessage").asText(null)));
			ordererName = firstNonNull(ordererName,
				emptyToNull(row.path("orderer").path("name").asText(null)));

			JsonNode oversea = row.path("overseaShippingInfoDto");
			if (!oversea.isMissingNode() && !oversea.isNull()) {
				recipientPhone = firstNonNull(recipientPhone,
					emptyToNull(oversea.path("ordererPhoneNumber").asText(null)));
				customsClearanceNo = firstNonNull(customsClearanceNo,
					emptyToNull(oversea.path("personalCustomsClearanceCode").asText(null)));
			}

			if (orderDate == null) {
				String orderedAt = emptyToNull(row.path("orderedAt").asText(null));
				if (orderedAt != null) {
					try {
						orderDate = LocalDateTime.parse(orderedAt,
							DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
					} catch (Exception e) {
						log.warn("[COUPANG] 주문일 파싱 실패: orderId={}, orderedAt={}", marketOrderNo, orderedAt);
					}
				}
			}
		}

		private void addShipment(String boxId, String invoiceNo, ShippingCarrier carrier,
			List<MarketLineItemDto> lineItems) {
			shipments.add(MarketShipmentDto.builder()
				.marketShipmentNo(boxId)
				.trackingNo(invoiceNo)
				.carrier(carrier)
				.lineItems(lineItems)
				.build());
		}

		private MarketOrderDto toNestedDto(MarketType marketType) {
			return MarketOrderDto.builder()
				.marketType(marketType)
				.marketOrderNo(marketOrderNo)
				.recipientName(recipientName)
				.recipientPhone(recipientPhone)
				.zipcode(zipcode)
				.address(address)
				.message(message)
				.ordererName(ordererName)
				.ordererPhone(ordererPhone)
				.customsClearanceNo(customsClearanceNo)
				.orderDate(orderDate)
				.shipments(shipments)
				.build();
		}

		private static String firstNonNull(String current, String candidate) {
			return current != null ? current : candidate;
		}
	}

	public void detectCancellations(List<MarketOrderDto> apiOrders, LocalDate fromDate, LocalDate toDate) {
		Set<String> apiOrderIds = new HashSet<>();
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
				boolean hasNonTerminal = items.stream().anyMatch(item -> isNonTerminal(item));

				if (hasNonTerminal) {
					for (OrderLineItem item : items) {
						if (isNonTerminal(item)) {
							ShippingUpdateCommand cmd = ShippingUpdateCommand.builder()
								.shippingStatus(ShippingStatus.CANCELED)
								.build();
							item.applyShippingData(cmd.toShippingData(item.getShippingData()));
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

	public void detectReturns(MarketCredential credential, LocalDate fromDate, LocalDate toDate) {
		JsonNode returns = coupangOrderApiPort.queryReturns(
			credential, fromDate.toString(), toDate.toString());
		if (returns == null || !returns.isArray()) {
			return;
		}

		Set<String> completedReturnOrderIds = new HashSet<>();
		for (JsonNode node : returns) {
			String receiptType = node.path("receiptType").asText("");
			String receiptStatus = node.path("receiptStatus").asText("");
			if ("RETURN".equalsIgnoreCase(receiptType)
				&& "RETURNS_COMPLETED".equalsIgnoreCase(receiptStatus)) {
				String orderId = node.path("orderId").asText(null);
				if (orderId != null && !orderId.isEmpty()) {
					completedReturnOrderIds.add(orderId);
				}
			}
		}
		if (completedReturnOrderIds.isEmpty()) {
			return;
		}

		List<Order> dbOrders = orderRepository.findByMarketType(MarketType.COUPANG);
		int returnedCount = 0;
		for (Order order : dbOrders) {
			if (!completedReturnOrderIds.contains(order.getMarketOrderNo())) {
				continue;
			}
			List<OrderLineItem> items = orderLineItemRepository.findByOrderId(order.getId());
			for (OrderLineItem item : items) {
				if (isAlreadyReturnedWithZeroSettlement(item)) {
					continue;
				}
				ShippingUpdateCommand cmd = ShippingUpdateCommand.builder()
					.shippingStatus(ShippingStatus.RETURNED)
					.build();
				item.applyShippingData(cmd.toShippingData(item.getShippingData()));
				item.applySettlement(BigDecimal.ZERO);
				item.markSettlementVerified();
				orderLineItemRepository.save(item);
				returnedCount++;
			}
		}

		if (returnedCount > 0) {
			log.info("쿠팡 반품완료 반영: {}건 RETURNED+정산0 전환", returnedCount);
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
			if (apiOrder == null) {
				continue;
			}

			for (OrderLineItem item : orderLineItemRepository.findByOrderId(order.getId())) {
				if (item.getShippingData() == null) {
					continue;
				}
				MarketShipmentDto source = resolveSourceShipment(apiOrder, item);
				if (source == null) {
					continue;
				}

				ShippingCarrier currentCarrier = item.getShippingData().getShippingCarrier();
				boolean needsCarrierFix = (currentCarrier == null || currentCarrier == ShippingCarrier.ETC)
					&& source.getCarrier() != null && source.getCarrier() != ShippingCarrier.ETC;

				boolean needsInvoiceFix = (item.getShippingData().getTrackingNo() == null
					|| item.getShippingData().getTrackingNo().isEmpty())
					&& source.getTrackingNo() != null && !source.getTrackingNo().isEmpty();

				if (needsCarrierFix || needsInvoiceFix) {
					ShippingUpdateCommand cmd = ShippingUpdateCommand.builder()
						.trackingNo(needsInvoiceFix ? source.getTrackingNo() : item.getShippingData().getTrackingNo())
						.shippingCarrier(
							needsCarrierFix ? source.getCarrier() : item.getShippingData().getShippingCarrier())
						.shippingStatus(item.getShippingData().getShippingStatus())
						.build();
					item.applyShippingData(cmd.toShippingData(item.getShippingData()));
					orderLineItemRepository.save(item);
					carrierFixedCount++;
				}
			}
		}

		if (carrierFixedCount > 0) {
			log.info("쿠팡 택배사 보정: {}건 업데이트", carrierFixedCount);
		}
	}

	private MarketFetchOutcome doFetchOrders(MarketCredential credential,
		LocalDate fromDate, LocalDate toDate) {
		Map<String, OrderAccumulator> accumulators = new LinkedHashMap<>();

		String fromDateStr = fromDate.toString();
		String toDateStr = toDate.toString();

		String[] statuses = {
			"ACCEPT", "INSTRUCT", "DEPARTURE", "DELIVERING", "FINAL_DELIVERY", "NONE_TRACKING"
		};

		Map<String, String> skuCache = new HashMap<>();

		int successCount = 0;
		int failureCount = 0;
		Exception lastFailure = null;

		for (String status : statuses) {
			try {
				JsonNode orders = coupangOrderApiPort.fetchOrders(
					credential, fromDateStr, toDateStr, status);

				if (orders == null || !orders.isArray()) {
					continue;
				}

				for (JsonNode orderNode : orders) {
					String orderStatus = orderNode.path("status").asText();

					if ("PAYMENT_WAITING".equalsIgnoreCase(orderStatus)
						|| "DEPOSIT_WAITING".equalsIgnoreCase(orderStatus)) {
						continue;
					}

					collectRow(accumulators, orderNode, status, credential, skuCache);
				}

				successCount++;
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			} catch (Exception e) {
				failureCount++;
				lastFailure = e;
				log.error("쿠팡 주문 조회 실패 (status={}): {}", status, e.getMessage());
			}
		}

		if (successCount == 0 && failureCount > 0) {
			String detail = lastFailure != null ? lastFailure.getMessage() : "알 수 없는 오류";
			throw new RuntimeException(
				"쿠팡 주문 조회 실패: " + detail + " — IP 허용목록/자격증명 확인", lastFailure);
		}
		if (failureCount > 0) {
			log.warn("쿠팡 주문 부분 조회: {} status 성공, {} status 실패 (마지막 오류: {})",
				successCount, failureCount, lastFailure != null ? lastFailure.getMessage() : "-");
		}

		List<MarketOrderDto> result = new ArrayList<>();
		for (OrderAccumulator accum : accumulators.values()) {
			result.add(accum.toNestedDto(getMarketType()));
		}
		return failureCount == 0
			? MarketFetchOutcome.complete(result)
			: MarketFetchOutcome.partial(result);
	}

	private void collectRow(Map<String, OrderAccumulator> accumulators, JsonNode orderNode,
		String status, MarketCredential credential, Map<String, String> skuCache) {
		try {
			String marketOrderNo = orderNode.path("orderId").asText(null);
			if (marketOrderNo == null || marketOrderNo.isEmpty()) {
				return;
			}
			OrderAccumulator accum = accumulators.computeIfAbsent(marketOrderNo, OrderAccumulator::new);
			accum.fillOrderCommon(orderNode);

			String shipmentBoxId = emptyToNull(orderNode.path("shipmentBoxId").asText(null));
			if (shipmentBoxId == null) {
				shipmentBoxId = marketOrderNo;
			}

			String invoiceNo = emptyToNull(orderNode.path("invoiceNumber").asText(null));
			if ("null".equals(invoiceNo)) {
				invoiceNo = null;
			}
			ShippingCarrier carrier = ShippingCarrier.fromMarketCode(
				orderNode.path("deliveryCompanyName").asText(null));
			ShippingStatus boxStatus = statusMapper.mapStatus(Map.of("status", status));

			List<MarketLineItemDto> lineItems = new ArrayList<>();
			JsonNode orderItems = orderNode.path("orderItems");
			if (orderItems.isArray() && orderItems.size() > 0) {
				for (JsonNode item : orderItems) {
					lineItems.add(toLineItem(item, shipmentBoxId, boxStatus, credential, skuCache));
				}
			} else {
				log.warn("[COUPANG] orderItems가 비어 있다: orderId={}, box={} — 식별자 없는 라인아이템 1건으로 처리",
					marketOrderNo, shipmentBoxId);
				lineItems.add(MarketLineItemDto.builder()
					.quantity(0)
					.orderPrice(BigDecimal.ZERO)
					.totalAmount(BigDecimal.ZERO)
					.status(boxStatus)
					.build());
			}

			accum.addShipment(shipmentBoxId, invoiceNo, carrier, lineItems);
		} catch (Exception e) {
			log.error("쿠팡 주문 파싱 실패: {}", e.getMessage());
		}
	}

	private MarketLineItemDto toLineItem(JsonNode item, String shipmentBoxId,
		ShippingStatus boxStatus, MarketCredential credential, Map<String, String> skuCache) {
		String vendorItemId = emptyToNull(item.path("vendorItemId").asText(null));
		String sellerProductId = emptyToNull(item.path("sellerProductId").asText(null));

		String externalVendorSkuCode = emptyToNull(item.path("externalVendorSkuCode").asText(null));
		if ("null".equals(externalVendorSkuCode)) {
			externalVendorSkuCode = null;
		}
		String resolvedSku = resolveExternalVendorSku(credential, sellerProductId, vendorItemId, skuCache);
		if (resolvedSku != null && !resolvedSku.isEmpty()) {
			externalVendorSkuCode = resolvedSku;
		} else if (externalVendorSkuCode == null) {
			externalVendorSkuCode = sellerProductId;
		}

		int qty = item.path("shippingCount").asInt();
		BigDecimal price = new BigDecimal(item.path("orderPrice").asText("0"));

		ShippingStatus status = item.path("canceled").asBoolean(false)
			? ShippingStatus.CANCELED : boxStatus;

		return MarketLineItemDto.builder()
			.marketLineItemNo(vendorItemId != null ? shipmentBoxId + ":" + vendorItemId : null)
			.marketProductCode(vendorItemId)
			.sellerProductId(sellerProductId)
			.productName(emptyToNull(item.path("vendorItemName").asText(null)))
			.quantity(qty)
			.orderPrice(price)
			.totalAmount(price.multiply(BigDecimal.valueOf(qty)))
			.status(status)
			.marketSpecificData(new HashMap<>(Map.of(
				"vendorItemId", vendorItemId != null ? vendorItemId : "",
				"externalVendorSkuCode", externalVendorSkuCode != null ? externalVendorSkuCode : "",
				"shipmentBoxId", shipmentBoxId)))
			.build();
	}

	private String resolveExternalVendorSku(MarketCredential credential,
		String sellerProductIdStr, String vendorItemId, Map<String, String> skuCache) {
		if (sellerProductIdStr == null || sellerProductIdStr.isEmpty()) {
			return null;
		}

		String cacheKey = sellerProductIdStr + ":" + (vendorItemId != null ? vendorItemId : "");

		if (skuCache.containsKey(cacheKey)) {
			return skuCache.get(cacheKey);
		}

		try {
			long sellerProductId = Long.parseLong(sellerProductIdStr);
			JsonNode productData = coupangOrderApiPort.queryProduct(credential, sellerProductId);

			if (productData != null) {
				JsonNode items = productData.path("items");
				if (items.isArray()) {
					for (JsonNode item : items) {
						String itemVendorItemId = item.path("vendorItemId").asText(null);
						String externalVendorSku = item.path("externalVendorSku").asText(null);

						if (vendorItemId != null && vendorItemId.equals(itemVendorItemId)) {
							if (externalVendorSku != null && !externalVendorSku.isEmpty()) {
								skuCache.put(cacheKey, externalVendorSku);
								log.info(
									"상품조회 성공(vendorItemId 매칭): sellerProductId={}, vendorItemId={}, externalVendorSku={}",
									sellerProductIdStr, vendorItemId, externalVendorSku);
								return externalVendorSku;
							}
						}
					}
					JsonNode firstItem = items.get(0);
					if (firstItem != null) {
						String externalVendorSku = firstItem.path("externalVendorSku").asText(null);
						if (externalVendorSku != null && !externalVendorSku.isEmpty()) {
							skuCache.put(cacheKey, externalVendorSku);
							log.info("상품조회 성공(첫 번째 item): sellerProductId={}, externalVendorSku={}",
								sellerProductIdStr, externalVendorSku);
							return externalVendorSku;
						}
					}
				}
			}
			log.warn("상품조회: externalVendorSku 없음 sellerProductId={}", sellerProductIdStr);
		} catch (NumberFormatException e) {
			log.warn("sellerProductId 파싱 실패: {}", sellerProductIdStr);
		} catch (Exception e) {
			log.warn("상품조회 실패: sellerProductId={}, error={}", sellerProductIdStr, e.getMessage());
		}

		skuCache.put(cacheKey, null);
		return null;
	}

	private static String emptyToNull(String value) {
		return (value == null || value.isEmpty()) ? null : value;
	}

	private String resolveShipmentBoxId(Order order, OrderLineItem lineItem, String action) {
		if (lineItem != null && lineItem.getShipmentId() != null && shipmentRepository != null) {
			String fromShipment = shipmentRepository.findById(lineItem.getShipmentId())
				.map(Shipment::getMarketShipmentNo)
				.orElse(null);
			if (fromShipment != null && !fromShipment.isEmpty()) {
				return fromShipment;
			}
		}
		throw new IllegalArgumentException(
			"쿠팡 " + action + " 실패: 이 상품주문이 속한 배송을 찾을 수 없습니다. order="
				+ order.getMarketOrderNo() + " (주문 동기화로 배송을 먼저 확보해야 합니다)");
	}

	private String mapCarrierCode(ShippingCarrier carrier) {
		if (carrier == null) {
			throw new IllegalArgumentException("배송사 정보가 없습니다.");
		}
		return switch (carrier) {
			case CJ_LOGISTICS -> "CJGLS";
			case HANJIN -> "HANJIN";
			case KOREA_POST -> "EPOST";
			case LOTTE_LOGISTICS -> "HYUNDAI";
			case ROCKET -> "COUPANG";
			default -> "CJGLS";
		};
	}

	private boolean isNonTerminal(OrderLineItem item) {
		if (item.getShippingData() == null) {
			return true;
		}
		ShippingStatus s = item.getShippingData().getShippingStatus();
		return s != ShippingStatus.CANCELED
			&& s != ShippingStatus.DELIVERED
			&& s != ShippingStatus.RETURNED
			&& s != ShippingStatus.EXCHANGED;
	}

	private boolean isAlreadyReturnedWithZeroSettlement(OrderLineItem item) {
		boolean returned = item.getShippingData() != null
			&& item.getShippingData().getShippingStatus() == ShippingStatus.RETURNED;
		boolean zeroSettlement = item.getSettlementData() != null
			&& item.getSettlementData().getSettlementAmount() != null
			&& item.getSettlementData().getSettlementAmount().compareTo(BigDecimal.ZERO) == 0;
		return returned && zeroSettlement;
	}

	private MarketShipmentDto resolveSourceShipment(MarketOrderDto apiOrder, OrderLineItem item) {
		List<MarketShipmentDto> shipments = apiOrder.getShipments();
		if (shipments == null || shipments.isEmpty()) {
			return null;
		}
		if (item.getShipmentId() != null && shipmentRepository != null) {
			String boxId = shipmentRepository.findById(item.getShipmentId())
				.map(Shipment::getMarketShipmentNo)
				.orElse(null);
			if (boxId != null) {
				return shipments.stream()
					.filter(sh -> boxId.equals(sh.getMarketShipmentNo()))
					.findFirst().orElse(null);
			}
		}
		return shipments.size() == 1 ? shipments.get(0) : null;
	}
}
