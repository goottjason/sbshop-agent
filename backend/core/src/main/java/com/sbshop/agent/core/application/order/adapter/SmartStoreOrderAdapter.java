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

	// D-118: 네이버 API 호출 간격·재시도 파라미터.
	// 30일 창을 1일 단위로 훑으므로 런당 31청크 — 간격 2초면 한 런이 약 60~90초다. 동기화는 30분 주기라
	// 여유가 충분하고, 수집 누락 0이 속도보다 훨씬 중요하므로 넉넉하게 잡는다.
	// (테스트에서 대기 없이 돌리려고 final이 아닌 package-private으로 둔다.)
	long chunkDelayMillis = 2_000L;
	long retryBackoffMillis = 5_000L;
	/** 429 1건당 최대 재시도 횟수(백오프는 시도마다 2배). */
	static final int MAX_RETRIES = 3;

	@Override
	public MarketType getMarketType() {
		return MarketType.SMART_STORE;
	}

	@Override
	public List<MarketOrderDto> fetchOrders(MarketCredential credential,
		LocalDate fromDate, LocalDate toDate) {
		// 5단계: 응답 원소는 <b>상품주문</b> 하나다. 주문(orderId)으로 묶어 3계층으로 낸다.
		// 청크 경계를 넘어 누적해야 한다 — 같은 주문의 상품주문이 서로 다른 날 바뀌면 다른 청크에 온다.
		// 안쪽 맵의 키가 상품주문번호이므로, 창 안에서 상태가 두 번 바뀌어 두 청크에 나온 상품주문은
		// 나중 것이 앞의 것을 덮는다(최신이 진실). 그대로 두면 라인아이템 키가 중복돼
		// uk_line_item_order_market_no 위반으로 동기화가 통째로 실패한다.
		LinkedHashMap<String, LinkedHashMap<String, ProductOrderRow>> grouped = new LinkedHashMap<>();

		String clientId = credential.getClientId();
		String secretKey = credential.getSecretKey();

		ZonedDateTime endDate = ZonedDateTime.now(ZoneId.of("UTC")).truncatedTo(ChronoUnit.SECONDS);
		ZonedDateTime startDate = fromDate.atStartOfDay(ZoneId.of("UTC"));
		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

		// D-043: chunk별 성공/실패 집계. 전량 실패 시 예외 전파 → 서비스 catch → SYNC_FAILED → 액션로그 FAILED.
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

			// D-118: 지연을 성공 경로에만 두면 실패 시 즉시 다음 호출로 넘어가 429가 연쇄 폭주한다
			// (31청크가 4초 만에 완주하던 원인). 성공·실패 무관하게 청크 사이를 항상 벌린다.
			if (!sleepQuietly(chunkDelayMillis)) {
				break;
			}

			currentFrom = currentTo;
		}

		// 전량 실패(성공 0 · 오류≥1)면 대표 오류를 담아 전파. 부분 성공은 result 반환(경고).
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

	/**
	 * D-118: 429는 "잠시 후 다시"라는 뜻이므로 백오프 후 재시도해 청크를 회수한다.
	 * 429가 아닌 오류(401 인증 실패 등)는 재시도해도 결과가 같고 호출만 늘리므로 즉시 전파한다.
	 */
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

	/** 호출량 초과(429) 여부. 클라이언트가 상태코드를 메시지에 담아 전파한다. */
	private boolean isRateLimited(Exception e) {
		String message = e.getMessage();
		return message != null
			&& (message.contains("429") || message.contains("TOO_MANY_REQUESTS"));
	}

	/** 인터럽트되면 플래그를 복구하고 false를 반환해 호출부가 루프를 접게 한다. */
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

	/**
	 * 응답 원소 하나(= 상품주문 1건)를 중간 표현으로 파싱한다.
	 *
	 * <p>주문 계층 필드({@code recipient*}·주소·구매자)가 상품주문마다 실려 오지만 같은 주문이면
	 * 같은 값이다. 묶을 때 대표값 하나만 쓴다.
	 */
	private record ProductOrderRow(
		String orderKey, String productOrderId, String packageNumber,
		LocalDateTime orderDate, String recipientName, String recipientPhone,
		String zipcode, String address, String message,
		String ordererName, String ordererPhone, String customsClearanceNo,
		String productName, String sellerProductCode, int quantity,
		BigDecimal unitPrice, BigDecimal totalAmount, BigDecimal settlementAmount,
		ShippingStatus status, String trackingNo, ShippingCarrier carrier, String deliveryStatus) {

		/** 이 상품주문이 속한 배송의 식별자. 묶음배송번호가 없으면 상품주문번호로 대체한다(설계 §3.3). */
		String shipmentKey() {
			return (packageNumber != null && !packageNumber.isBlank()) ? packageNumber : productOrderId;
		}
	}

	/**
	 * 한 주문의 상품주문들을 3계층 DTO로 조립한다.
	 *
	 * <p>배송은 {@code packageNumber}로 가른다 — 묶음배송이면 상품주문 여러 건이 한 배송에 들어가고,
	 * 분리배송이면 배송이 갈린다. 송장은 <b>배송에 붙인다</b>(한 배송 = 한 송장).
	 */
	private MarketOrderDto toOrderDto(LinkedHashMap<String, ProductOrderRow> productOrders) {
		// 대표값은 마지막(가장 최근에 바뀐) 상품주문에서 취한다 — 주문 계층 값은 어차피 같다.
		ProductOrderRow representative = null;
		LinkedHashMap<String, List<ProductOrderRow>> byShipment = new LinkedHashMap<>();
		for (ProductOrderRow row : productOrders.values()) {
			representative = row;
			byShipment.computeIfAbsent(row.shipmentKey(), k -> new ArrayList<>()).add(row);
		}

		List<MarketShipmentDto> shipments = new ArrayList<>();
		for (Map.Entry<String, List<ProductOrderRow>> entry : byShipment.entrySet()) {
			// 한 배송 = 한 송장. 이 배송의 상품주문 중 송장을 알려준 것이 있으면 그것이 배송의 송장이다.
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

		// 주문 계층 마켓 데이터 — 발주확인·주문취소가 읽는다(둘 다 상품주문 단위 API다).
		// 구분자가 콤마가 아닌 이유: Order.marketSpecificData는 자체 구현 유사 JSON이고 읽을 때
		// ','로 split한다 — 콤마를 쓰면 값이 조용히 잘린다(D-135).
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
			// 5단계: 주문 키는 orderId다. 실측상 22/22 존재하지만, 없으면 상품주문번호로 폴백한다 —
			// 식별자를 못 얻었다고 주문을 드롭하면 그 주문이 통째로 사라진다(D-131/D-136에서 배운 것).
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

			// D-117: placeOrderStatus는 응답에 없을 수 있다. asText(null)의 null이 Map.of에 들어가면
			// NPE가 나고 아래 catch가 그걸 삼켜 주문이 통째로 드롭되므로 빈 문자열로 정규화한다.
			String placeOrderStatus = productOrderInfo.path("placeOrderStatus").asText("");
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
			String deliveryStatus = deliveryInfo.path("deliveryStatus").asText(null);

			// 마켓이 알려준 정산예정금액. 실측상 22/22 존재한다 — 요율 추정보다 항상 정확하다(설계 §9.1).
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
				settlementAmount, shippingStatus, trackingNo, carrier, deliveryStatus);

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

	/**
	 * 발송처리·발주확인·취소는 전부 <b>상품주문 단위</b>다 — 주문번호(orderId)를 넘기면 안 된다.
	 *
	 * <p>5단계 전에는 주문번호가 곧 상품주문번호였다. 전환 후 주문은 {@code productOrderIds}를
	 * 반드시 갖고, 갖지 않은 행은 전환 전에 저장된 것이므로 그때의 의미대로 주문번호를 쓴다.
	 */
	private List<String> resolveProductOrderIds(Order order) {
		Map<String, String> data = order.getMarketSpecificDataMap();
		String joined = data != null ? data.get("productOrderIds") : null;
		if (joined != null && !joined.isBlank()) {
			List<String> ids = new ArrayList<>();
			// '|'가 정본 구분자다(D-135). 콤마도 받아 준다 — 상품주문번호는 숫자라 오인될 여지가 없다.
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
		// 전환 전 저장분: marketOrderNo가 곧 productOrderId였다.
		String legacy = order.getMarketOrderNo();
		return (legacy != null && !legacy.isBlank()) ? List.of(legacy) : List.of();
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

	/**
	 * 이 발송이 대상으로 하는 상품주문 하나를 정한다.
	 *
	 * <p>라인아이템의 키가 정답이다. 없을 때 주문의 상품주문이 하나뿐이면 그것으로 확정할 수 있지만,
	 * 여럿이면 <b>추측하지 않고 즉시 알린다</b> — 엉뚱한 상품이 발송 처리되면 되돌릴 수 없고,
	 * 잘못된 식별자로 인한 마켓 거부는 마켓의 상태 잠금처럼 보여 원인 추적을 어렵게 만든다(D-127).
	 */
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

	/**
	 * 발주확인은 주문의 <b>모든</b> 상품주문에 해야 한다. 하나라도 남으면 네이버는 그 주문을
	 * 신규주문 목록에 계속 둔다(11번가에서 같은 현상을 겪었다).
	 */
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
}
