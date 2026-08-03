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
		List<MarketOrderDto> result = new ArrayList<>();

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
