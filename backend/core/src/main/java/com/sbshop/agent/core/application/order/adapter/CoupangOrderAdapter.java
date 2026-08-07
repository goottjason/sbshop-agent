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
import com.sbshop.agent.core.application.order.dto.MarketOrderDto;
import com.sbshop.agent.core.application.order.dto.MarketShipmentDto;
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
import java.util.LinkedHashMap;
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
	/** 3단계: 쓰기 경로가 라인아이템이 속한 배송에서 shipmentBoxId를 얻는다(분할배송 대응). */
	private final com.sbshop.agent.core.domain.order.repository.ShipmentRepository shipmentRepository;

	@Override
	public MarketType getMarketType() {
		return MarketType.COUPANG;
	}

	@Override
	public List<MarketOrderDto> fetchOrders(MarketCredential credential,
		LocalDate fromDate, LocalDate toDate) {
		// 3단계: 주문번호로 모은다. 응답 행 하나가 배송박스 하나이므로, 한 주문이 여러 행으로
		// 올 수 있다(분할배송). DTO를 행마다 내보내면 MarketOrderUpsertDispatcher가 같은 주문을
		// 여러 번 찾아 onExisting을 반복 호출하고 서로 덮어쓴다 — 11번가에서 겪은 함정이다.
		Map<String, OrderAccumulator> accumulators = new LinkedHashMap<>();

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

		List<MarketOrderDto> result = new ArrayList<>();
		for (OrderAccumulator accum : accumulators.values()) {
			result.add(accum.toNestedDto(getMarketType()));
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

	/**
	 * 응답 행 하나(=배송박스 하나)를 해당 주문의 누적기에 담는다.
	 *
	 * <p>행 레벨과 상품 레벨의 경계가 이 메서드의 핵심이다(2026-08-06 라이브 확인):
	 * {@code shipmentBoxId}·{@code status}·{@code invoiceNumber}·{@code deliveryCompanyName}은
	 * <b>행(배송)</b>의 것이고, {@code vendorItemId}·{@code externalVendorSkuCode}·
	 * {@code shippingCount}·{@code orderPrice}·{@code canceled}는 <b>상품주문</b>의 것이다.
	 */
	private void collectRow(Map<String, OrderAccumulator> accumulators, JsonNode orderNode,
		String status, MarketCredential credential, Map<String, String> skuCache) {
		try {
			String marketOrderNo = orderNode.path("orderId").asText(null);
			if (marketOrderNo == null || marketOrderNo.isEmpty()) {
				return;
			}
			OrderAccumulator accum = accumulators.computeIfAbsent(marketOrderNo, OrderAccumulator::new);
			accum.fillOrderCommon(orderNode);

			// 설계 3.3: 배송 식별자를 못 얻으면 주문번호로 대체한다. 배송 없는 주문은 만들지 않는다.
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
				// 상품이 없는 행도 주문을 드롭하지 않는다(11번가 2단계의 교훈). 식별자는 위조하지 않는다.
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

	/**
	 * {@code orderItems} 원소 하나를 상품주문으로 변환한다.
	 *
	 * <p>상품주문 식별자를 <b>{@code 배송박스:vendorItemId}</b>로 만드는 이유: 분할배송은 한 상품의
	 * 수량을 여러 박스로 쪼갤 수 있고, 그러면 {@code vendorItemId}만으로는 한 주문 안에서 중복돼
	 * {@code uk_line_item_order_market_no}를 위반한다 — 동기화가 통째로 실패한다(D-131과 같은 부류).
	 * 쿠팡의 실제 상품주문 단위는 (배송박스, 상품)이므로 이 키가 정직하다.
	 */
	private MarketLineItemDto toLineItem(JsonNode item, String shipmentBoxId,
		ShippingStatus boxStatus, MarketCredential credential, Map<String, String> skuCache) {
		String vendorItemId = emptyToNull(item.path("vendorItemId").asText(null));
		String sellerProductId = emptyToNull(item.path("sellerProductId").asText(null));

		// 1차: 주문 API의 externalVendorSkuCode 사용
		String externalVendorSkuCode = emptyToNull(item.path("externalVendorSkuCode").asText(null));
		if ("null".equals(externalVendorSkuCode)) {
			externalVendorSkuCode = null;
		}
		// 2차: sellerProductId로 상품상세조회 API를 호출해 올바른 externalVendorSku를 가져온다.
		// 쿠팡 주문 API의 externalVendorSkuCode는 부정확한 값(P0000NPQ000A 등)을 반환할 수 있다.
		String resolvedSku = resolveExternalVendorSku(credential, sellerProductId, vendorItemId, skuCache);
		if (resolvedSku != null && !resolvedSku.isEmpty()) {
			externalVendorSkuCode = resolvedSku;
		} else if (externalVendorSkuCode == null) {
			// 3차: sellerProductId 자체를 사용 (최후의 수단)
			externalVendorSkuCode = sellerProductId;
		}

		int qty = item.path("shippingCount").asInt();
		BigDecimal price = new BigDecimal(item.path("orderPrice").asText("0"));

		// 부분취소는 상품 단위로 표현된다 — 취소된 상품만 종결 상태가 된다.
		ShippingStatus status = item.path("canceled").asBoolean(false)
			? ShippingStatus.CANCELED : boxStatus;

		return MarketLineItemDto.builder()
			.marketLineItemNo(vendorItemId != null ? shipmentBoxId + ":" + vendorItemId : null)
			// marketProductCode는 종전 의미(vendorItemId)를 유지한다 — resolveProductId가 이걸로 매칭한다.
			.marketProductCode(vendorItemId)
			.sellerProductId(sellerProductId)
			.productName(emptyToNull(item.path("vendorItemName").asText(null)))
			.quantity(qty)
			.orderPrice(price)
			.totalAmount(price.multiply(BigDecimal.valueOf(qty)))
			.status(status)
			.marketSpecificData(new java.util.HashMap<>(Map.of(
				"vendorItemId", vendorItemId != null ? vendorItemId : "",
				"externalVendorSkuCode", externalVendorSkuCode != null ? externalVendorSkuCode : "",
				"shipmentBoxId", shipmentBoxId)))
			.build();
	}

	private static String emptyToNull(String value) {
		return (value == null || value.isEmpty()) ? null : value;
	}

	/**
	 * 한 주문번호에 대해 여러 행(=배송박스)을 모아 3계층 DTO로 조립한다.
	 *
	 * <p>주문 공통 정보는 아무 행에서나 빈 칸 채우기로 병합한다 — 같은 주문의 행들은 수취인·주소가
	 * 같기 때문이다. 상태·송장은 행마다 다르므로 절대 병합하지 않고 각 배송에 그대로 둔다.
	 */
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

		/**
		 * <p>라인아이템 레벨 평면 필드는 채우지 않는다 — "첫 상품주문"을 담으면 종전의 키메라 행이
		 * 되살아난다. 6단계에서 {@code shipmentBoxId}도 뺐다: 배송박스번호는 <b>배송이 갖는다</b>
		 * ({@code MarketShipmentDto.marketShipmentNo}). 같은 값을 주문 계층으로도 나르면 원본이
		 * 둘이 되고, 분할배송에서 "대표 박스"가 나머지 박스를 가린다.
		 */
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

	/**
	 * 쓰기에 쓸 배송박스 식별자를 정한다 — <b>라인아이템이 속한 배송</b>이 우선이다.
	 *
	 * <p>{@code sb_order.shipment_box_id}는 주문당 하나뿐이라 분할배송(박스 여러 개)을 표현하지
	 * 못한다. 그 값으로 쓰면 엉뚱한 박스에 송장을 붙이거나 발주확인이 한 박스만 된다.
	 * 배송 계층이 붙은 뒤에는 그것이 정답이고, 아직 안 붙은 레거시 행만 주문 컬럼으로 폴백한다
	 * (미러 컬럼 제거는 6단계).
	 */
	private String resolveShipmentBoxId(Order order, OrderLineItem lineItem, String action) {
		if (lineItem != null && lineItem.getShipmentId() != null && shipmentRepository != null) {
			String fromShipment = shipmentRepository.findById(lineItem.getShipmentId())
				.map(com.sbshop.agent.core.domain.order.Shipment::getMarketShipmentNo)
				.orElse(null);
			if (fromShipment != null && !fromShipment.isEmpty()) {
				return fromShipment;
			}
		}
		throw new IllegalArgumentException(
			"쿠팡 " + action + " 실패: 이 상품주문이 속한 배송을 찾을 수 없습니다. order="
				+ order.getMarketOrderNo() + " (주문 동기화로 배송을 먼저 확보해야 합니다)");
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

	private String mapCarrierCode(ShippingCarrier carrier) {
		if (carrier == null) {
			throw new IllegalArgumentException("배송사 정보가 없습니다.");
		}
		return switch (carrier) {
			case CJ_LOGISTICS -> "CJGLS";
			case HANJIN -> "HANJIN";
			case KOREA_POST -> "EPOST";
			// 쿠팡은 롯데택배를 구(舊) 현대택배 코드 "HYUNDAI"로 식별한다.
			// "LOTTE"는 쿠팡 미지원 → 400 "Delivery company code not supported" (라이브 확인, D-E5).
			case LOTTE_LOGISTICS -> "HYUNDAI";
			case ROCKET -> "COUPANG";
			default -> "CJGLS";
		};
	}

	/**
	 * 발주확인은 <b>배송박스 단위</b>다 — 주문의 모든 박스를 확인해야 한다.
	 *
	 * <p>종전에는 {@code sb_order.shipment_box_id} 하나만 넘겼다. 분할배송 주문이면 나머지 박스가
	 * 미확인으로 남아 주문이 결제완료에 머문다 — 11번가에서 같은 형태의 결함을 겪었다(D-134).
	 */
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
	 * D-097: 쿠팡 반품완료 전방 감지. returnRequests API로 receiptStatus=RETURNS_COMPLETED를 확증하면
	 * 해당 주문의 lineItem을 RETURNED + 정산 0 + verified로 전환한다.
	 *
	 * <p>배송완료(DELIVERED) 후 고객 반품 시 쿠팡은 그 주문을 ordersheet에서 제거하므로 fetchOrders로는
	 * 반품을 학습할 수 없다(단건조회 400 "취소 또는 반품"). detectCancellations는 DELIVERED를 terminal로
	 * 보호하므로 absence로도 못 잡는다. 이 경로만이 반품완료를 권위 있게 확증한다 — absence 추론이 아니라
	 * 쿠팡 원본 대조라 오취소가 없다. 멱등이며(RETURNED+0은 재전환하지 않음), 정산동기화는 DELIVERED만
	 * 처리하므로 RETURNED건은 스킵되어 정산액이 다시 부풀지 않는다.
	 */
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
					continue; // 멱등: 재실행 시 이미 반영된 건은 스킵
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

	private boolean isAlreadyReturnedWithZeroSettlement(OrderLineItem item) {
		boolean returned = item.getShippingData() != null
			&& item.getShippingData().getShippingStatus() == ShippingStatus.RETURNED;
		boolean zeroSettlement = item.getSettlementData() != null
			&& item.getSettlementData().getSettlementAmount() != null
			&& item.getSettlementData().getSettlementAmount().compareTo(BigDecimal.ZERO) == 0;
		return returned && zeroSettlement;
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

	/**
	 * 택배사 ETC·빈 송장을 마켓 값으로 보정한다.
	 *
	 * <p>3단계에서 송장·택배사는 <b>배송</b>에 실려 오므로 거기서 읽는다. 평면 필드는 더는 채워지지
	 * 않는다(라인아이템 레벨 값을 주문에 담으면 키메라 행이 되살아난다). 배송 upsert와 미러가 대부분을
	 * 이미 처리하지만, 마켓이 택배사만 주고 송장을 주지 않는 경우처럼 미러가 건드리지 않는 틈이 남아
	 * 이 보정을 유지한다.
	 */
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

	/**
	 * 이 라인아이템에 대응하는 응답 배송을 찾는다. 배송이 붙어 있으면 <b>그 배송식별자로</b>
	 * 짝짓고, 아직 없으면(레거시 행) 배송이 하나뿐일 때만 그것을 쓴다 — 여러 박스 중 아무거나
	 * 골라 붙이면 엉뚱한 송장이 들어간다.
	 */
	private MarketShipmentDto resolveSourceShipment(MarketOrderDto apiOrder, OrderLineItem item) {
		List<MarketShipmentDto> shipments = apiOrder.getShipments();
		if (shipments == null || shipments.isEmpty()) {
			return null;
		}
		if (item.getShipmentId() != null && shipmentRepository != null) {
			String boxId = shipmentRepository.findById(item.getShipmentId())
				.map(com.sbshop.agent.core.domain.order.Shipment::getMarketShipmentNo)
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
