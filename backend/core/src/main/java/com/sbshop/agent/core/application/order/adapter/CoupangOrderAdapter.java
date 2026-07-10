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
import com.sbshop.agent.core.application.order.dto.MarketOrderDto;
import com.sbshop.agent.core.application.order.dto.ShippingUpdateCommand;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.application.order.mapper.CoupangStatusMapper;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
	private final MarketRegistrationRepository marketRegistrationRepository;

	@Override
	public MarketType getMarketType() {
		return MarketType.COUPANG;
	}

	@Override
	public List<MarketOrderDto> fetchOrders(MarketCredential credential,
		LocalDate fromDate, LocalDate toDate) {
		List<MarketOrderDto> result = new ArrayList<>();

		String fromDateStr = fromDate.toString();
		String toDateStr = toDate.toString();

		String[] statuses = {
			"ACCEPT", "INSTRUCT", "DEPARTURE", "DELIVERING", "FINAL_DELIVERY", "NONE_TRACKING"
		};

		// sellerProductId -> externalVendorSku 매핑 캐시
		Map<String, String> skuCache = new HashMap<>();

		// D-046: status별 성공/실패 집계. 전량 실패 시 예외 전파 → 서비스 catch → SYNC_FAILED → 에러 토스트.
		// "진짜 0건(예외 없는 빈 응답)"과 "오류로 0건(API 실패)"을 구분한다.
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

					MarketOrderDto dto = parseOrderNode(orderNode, status, credential, skuCache);
					if (dto != null) {
						result.add(dto);
					}
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

		// 전량 실패(성공 0 · 오류≥1)면 대표 오류를 담아 전파. 부분 성공은 기존대로 result 반환(경고).
		if (successCount == 0 && failureCount > 0) {
			String detail = lastFailure != null ? lastFailure.getMessage() : "알 수 없는 오류";
			throw new RuntimeException(
				"쿠팡 주문 조회 실패: " + detail + " — IP 허용목록/자격증명 확인", lastFailure);
		}
		if (failureCount > 0) {
			log.warn("쿠팡 주문 부분 조회: {} status 성공, {} status 실패 (마지막 오류: {})",
				successCount, failureCount, lastFailure != null ? lastFailure.getMessage() : "-");
		}

		return result;
	}

	/**
	 * sellerProductId로 쿠팡 상품상세조회 API를 호출하여 올바른 externalVendorSku(판매자상품코드)를 가져옴
	 * 쿠팡 주문 API의 externalVendorSkuCode는 부정확한 값을 반환할 수 있으므로 상품조회 API를 통해 검증
	 */
	private String resolveExternalVendorSku(MarketCredential credential,
		String sellerProductIdStr, String vendorItemId, Map<String, String> skuCache) {
		if (sellerProductIdStr == null || sellerProductIdStr.isEmpty()) {
			return null;
		}

		String cacheKey = sellerProductIdStr + ":" + (vendorItemId != null ? vendorItemId : "");

		// 캐시 히트 시 즉시 반환 (null도 캐시 - 재시도 방지)
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

						// vendorItemId가 일치하는 item에서 externalVendorSku를 가져옴
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
					// vendorItemId 매칭 실패 시 첫 번째 item의 externalVendorSku 사용
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

		// 조회 실패 시 null 캐싱 (재시도 방지)
		skuCache.put(cacheKey, null);
		return null;
	}

	private MarketOrderDto parseOrderNode(JsonNode orderNode, String status,
		MarketCredential credential, Map<String, String> skuCache) {
		try {
			String marketOrderNo = orderNode.path("orderId").asText();
			String shipmentBoxId = orderNode.path("shipmentBoxId").asText(null);

			String orderedAtStr = orderNode.path("orderedAt").asText();
			LocalDateTime orderDate = LocalDateTime.parse(orderedAtStr,
				DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));

			JsonNode receiver = orderNode.path("receiver");
			String recipientName = receiver.path("name").asText();
			String ordererPhone = receiver.path("safeNumber").asText();
			String zipcode = receiver.path("postCode").asText();
			String address = receiver.path("addr1").asText() + " " + receiver.path("addr2").asText();
			String message = orderNode.path("parcelPrintMessage").asText();

			JsonNode overseaInfo = orderNode.path("overseaShippingInfoDto");
			String customsClearanceNo = null;
			String ordererName = orderNode.path("orderer").path("name").asText(null);
			String recipientPhone = null;

			if (!overseaInfo.isMissingNode() && !overseaInfo.isNull()) {
				recipientPhone = overseaInfo.path("ordererPhoneNumber").asText(null);
				customsClearanceNo = overseaInfo.path("personalCustomsClearanceCode").asText(null);
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

				// 1차: 주문 API의 externalVendorSkuCode 사용
				String externalVendorSkuCode = firstItem.path("externalVendorSkuCode").asText(null);
				if (externalVendorSkuCode == null || externalVendorSkuCode.isEmpty()
					|| "null".equals(externalVendorSkuCode)) {
					externalVendorSkuCode = null;
				}

				// 2차: sellerProductId로 상품상세조회 API를 호출하여 올바른 externalVendorSku를 가져옴
				// 쿠팡 주문 API의 externalVendorSkuCode는 부정확한 값(P0000NPQ000A 등)을 반환할 수 있음
				String sellerProductId = firstItem.path("sellerProductId").asText(null);
				String vendorItemId = firstItem.path("vendorItemId").asText(null);
				String resolvedSku = resolveExternalVendorSku(
					credential, sellerProductId, vendorItemId, skuCache);

				if (resolvedSku != null && !resolvedSku.isEmpty()) {
					externalVendorSkuCode = resolvedSku;
				} else if (externalVendorSkuCode == null || externalVendorSkuCode.isEmpty()) {
					// 3차: sellerProductId 자체를 사용 (최후의 수단)
					externalVendorSkuCode = sellerProductId;
				}
				int qty = firstItem.path("shippingCount").asInt();
				BigDecimal price = new BigDecimal(firstItem.path("orderPrice").asText());

				return MarketOrderDto.builder()
					.marketType(getMarketType())
					.marketOrderNo(marketOrderNo)
					.marketProductCode(vendorItemId)
					.sellerProductId(sellerProductId) // (D-046) 발행 저장 식별자로 역조회·보강하기 위해 전달
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
				.marketType(getMarketType())
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
		if (lineItem.getProductId() == null) {
			throw new IllegalArgumentException("쿠팡 배송 처리 실패: productId가 없습니다.");
		}

		Optional<MarketRegistration> reg = marketRegistrationRepository
			.findByProductIdAndMarketType(lineItem.getProductId(), MarketType.COUPANG);
		String vendorItemIdStr = reg.map(MarketRegistration::extractVendorItemId).orElse(null);

		if (vendorItemIdStr == null || vendorItemIdStr.isEmpty()) {
			throw new IllegalArgumentException("쿠팡 배송 처리 실패: vendorItemId가 없습니다.");
		}

		if (order.getShipmentBoxId() == null || order.getShipmentBoxId().isEmpty()) {
			throw new IllegalArgumentException("쿠팡 배송 처리 실패: shipmentBoxId가 없습니다.");
		}

		String deliveryCompanyCode = mapCarrierCode(carrier);

		var request = new CoupangInvoiceUploadRequest(
			credential.getClientId(),
			List.of(new CoupangInvoiceUploadRequest.InvoiceApply(
				Long.parseLong(order.getShipmentBoxId()),
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

		if (order.getShipmentBoxId() == null || order.getShipmentBoxId().isEmpty()) {
			throw new IllegalArgumentException("쿠팡 송장 수정 실패: shipmentBoxId가 없습니다.");
		}

		String deliveryCompanyCode = mapCarrierCode(carrier);

		var request = new CoupangUpdateInvoiceRequest(
			credential.getClientId(),
			List.of(new CoupangUpdateInvoiceRequest.InvoiceApply(
				Long.parseLong(order.getShipmentBoxId()),
				Long.parseLong(order.getMarketOrderNo()),
				Long.parseLong(vendorItemIdStr),
				deliveryCompanyCode,
				trackingNo,
				false,
				false,
				"")));

		coupangOrderApiPort.updateTracking(credential, request);
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
	public void acceptOrders(MarketCredential credential, Order order) {
		if (order.getShipmentBoxId() == null || order.getShipmentBoxId().isEmpty()) {
			throw new IllegalStateException(
				"쿠팡 발주확인 실패: shipmentBoxId가 없습니다. order=" + order.getMarketOrderNo());
		}

		coupangOrderApiPort.acceptOrders(
			credential,
			List.of(order.getShipmentBoxId()));
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

	/**
	 * terminal(종결) 상태가 아닌지 판정한다. fetchOrders가 조회하지 않는 종결 상태
	 * (CANCELED·DELIVERED·RETURNED·EXCHANGED)는 API 응답에 없어도 취소로 오인해선 안 된다. (D-027)
	 */
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
					ShippingUpdateCommand cmd = ShippingUpdateCommand.builder()
						.trackingNo(needsInvoiceFix ? apiOrder.getTrackingNo() : item.getShippingData().getTrackingNo())
						.shippingCarrier(
							needsCarrierFix ? apiOrder.getCarrier() : item.getShippingData().getShippingCarrier())
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
}
