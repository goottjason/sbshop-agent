package com.sbshop.agent.core.application.order.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.order.port.EsmplusOrderApiPort;
import com.sbshop.agent.core.application.order.port.MarketOrderPort;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.application.order.dto.MarketOrderDto;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.application.order.mapper.EsmplusStatusMapper;
import java.math.BigDecimal;
import java.util.Map;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * ESM+(G마켓/옥션) 주문 어댑터
 * EsmplusOrderApiPort를 MarketOrderPort로 래핑하여 통합 인터페이스 제공
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EsmplusOrderAdapter implements MarketOrderPort {

	private final EsmplusOrderApiPort esmplusOrderApiPort;
	private final EsmplusStatusMapper statusMapper;
	// G마켓/옥션 주문은 Cafe24 연동으로 들어오므로 송장 역전송도 Cafe24 주문 API로 처리한다.
	private final com.sbshop.agent.core.application.order.service.Cafe24ShipmentService cafe24ShipmentService;
	private final ObjectMapper objectMapper = new ObjectMapper();

	@Override
	public MarketType getMarketType() {
		return MarketType.GMARKET;
	}

	@Override
	public List<MarketOrderDto> fetchOrders(MarketCredential credential,
		LocalDate fromDate, LocalDate toDate) {
		List<MarketOrderDto> result = new ArrayList<>();

		String masterId = credential.getAccessKey();
		String password = credential.getSecretKey();

		// D-043: masterId 부재는 조회 불가한 실패이므로 빈 반환("성공 0건")이 아니라 예외로 표면화.
		if (masterId == null || masterId.isBlank()) {
			throw new IllegalArgumentException("ESM+ 주문 조회 실패: masterId(accessKey) 없음");
		}

		try {
			List<MarketOrderDto> orders = esmplusOrderApiPort.fetchOrders(masterId, password, fromDate, toDate);
			result.addAll(orders);
			log.info("[ESM+] {} 건의 주문 조회 완료 ({}~{})", orders.size(), fromDate, toDate);
		} catch (Exception e) {
			// D-043: 스크래핑/로그인 실패를 삼켜 위장하지 말고 전파 → 서비스 catch → SYNC_FAILED → 액션로그 FAILED.
			log.error("[ESM+] 주문 조회 실패: {}", e.getMessage(), e);
			throw new RuntimeException("ESM+ 주문 조회 실패: " + e.getMessage(), e);
		}

		return result;
	}

	@Override
	public void shipOrder(MarketCredential credential,
		Order order, OrderLineItem lineItem,
		String trackingNo, ShippingCarrier carrier) {
		// G마켓 주문은 Cafe24 order_id(marketOrderNo)로 Cafe24 shipments API에 송장 등록.
		cafe24ShipmentService.ship(order, trackingNo, carrier);
	}

	@Override
	public void acceptOrders(MarketCredential credential, Order order) {
		esmplusOrderApiPort.confirmOrders(
			credential.getAccessKey(), credential.getSecretKey(),
			List.of(order.getMarketOrderNo()));
	}

	@Override
	public void cancelOrder(MarketCredential credential, Order order) {
		esmplusOrderApiPort.cancelOrders(
			credential.getAccessKey(), credential.getSecretKey(),
			List.of(order.getMarketOrderNo()), "판매자 취소");
	}

	@Override
	public MarketOrderDto fetchOrderDetail(MarketCredential credential, MarketOrderDto dto) {
		String masterId = credential.getAccessKey();
		String password = credential.getSecretKey();
		if (dto.getMarketOrderNo() == null || dto.getMarketOrderNo().isEmpty()) {
			log.warn("[ESM+] fetchOrderDetail: siteOrderNo 없음");
			return null;
		}
		log.info("[ESM+] fetchOrderDetail: siteOrderNo={}", dto.getMarketOrderNo());
		return esmplusOrderApiPort.fetchOrderDetail(masterId, password, dto);
	}

	/**
	 * ESM+ siteId로 마켓 타입 매핑
	 * siteId 1 = 옥션, siteId 2 = G마켓
	 */
	public MarketType mapSiteIdToMarketType(int siteId) {
		return switch (siteId) {
			case 1 -> MarketType.AUCTION;
			case 2 -> MarketType.GMARKET;
			default -> MarketType.GMARKET;
		};
	}

	/**
	 * JSON 응답을 MarketOrderDto 목록으로 파싱
	 *
	 * @param jsonBody API 응답 JSON 문자열
	 * @return 파싱된 주문 DTO 목록
	 */
	public List<MarketOrderDto> parseOrdersFromJson(String jsonBody) {
		List<MarketOrderDto> result = new ArrayList<>();

		try {
			JsonNode root = objectMapper.readTree(jsonBody);
			int resultCode = root.path("resultCode").asInt(-1);
			if (resultCode != 0) {
				log.warn("[ESM+] API 오류: resultCode={}", resultCode);
				return result;
			}

			JsonNode listNode = root.path("data").path("list");
			if (!listNode.isArray()) {
				log.warn("[ESM+] 응답에 list 배열이 없습니다");
				return result;
			}

			for (JsonNode orderNode : listNode) {
				MarketOrderDto dto = parseSingleOrder(orderNode);
				if (dto != null) {
					result.add(dto);
				}
			}

			log.info("[ESM+] {} 건의 주문 파싱 완료", result.size());
		} catch (Exception e) {
			log.error("[ESM+] JSON 파싱 실패: {}", e.getMessage(), e);
		}

		return result;
	}

	/**
	 * 단일 주문 JSON을 MarketOrderDto로 변환
	 */
	private MarketOrderDto parseSingleOrder(JsonNode orderNode) {
		try {
			String orderNo = orderNode.path("orderNo").asText("");
			String siteOrderNo = orderNode.path("siteOrderNo").asText("");
			int siteId = orderNode.path("siteId").asInt(0);
			String goodsNo = orderNode.path("goodsNo").asText("");
			String goodsName = orderNode.path("goodsName").asText("");
			String buyerId = orderNode.path("buyerId").asText("");
			String rcverName = orderNode.path("rcverName").asText("");
			String buyerName = orderNode.path("buyerName").asText("");
			double tradeAmnt = orderNode.path("tradeAmnt").asDouble(0);
			int orderQty = orderNode.path("orderQty").asInt(1);
			String deliveryStatus = orderNode.path("deliveryStatus").asText("");
			int deliveryStatusCode = orderNode.path("deliveryStatusCode").asInt(0);
			String depositDate = orderNode.path("depositConfirmDate").asText("");

			// 주문일 파싱
			LocalDateTime orderDate = parseDateTime(depositDate);

			// 상태 매핑 — D-032: 미인식 코드(UNKNOWN)일 때만 문자열 폴백 적용
			// (기존 `status==NEW && code!=1010`은 논리 모순 DEAD CODE였음)
			ShippingStatus status = statusMapper.mapStatus(deliveryStatusCode);
			if (status == ShippingStatus.UNKNOWN) {
				ShippingStatus fallback = statusMapper.mapStatus(deliveryStatus);
				if (fallback != ShippingStatus.UNKNOWN) {
					status = fallback;
				}
			}

			// D-029: 취소/교환 주문을 null로 필터링하지 않는다 — 정상 처리 경로로 흘려보내
			// 기존 DB 주문의 상태를 취소/교환으로 갱신하게 한다. ESM+ 통합주문목록은 상태 필터 없이
			// 취소/교환 주문을 in-band로 반환하므로 별도 취소 감지 없이 갱신이 성립한다.

			// siteId가 2(G마켓)인 주문만 처리 (옥션은 siteId=1)
			MarketType marketType = mapSiteIdToMarketType(siteId);

			return MarketOrderDto.builder()
				.marketType(marketType)
				.marketOrderNo(siteOrderNo)
				.marketProductCode(goodsNo)
				.productName(goodsName)
				.quantity(orderQty)
				.orderPrice(BigDecimal.valueOf(tradeAmnt / orderQty))
				.totalAmount(BigDecimal.valueOf(tradeAmnt))
				.recipientName(rcverName)
				.recipientPhone("")
				.zipcode("")
				.address("")
				.message("")
				.ordererName(buyerName)
				.ordererPhone("")
				.trackingNo("")
				.carrier(ShippingCarrier.CJ_LOGISTICS)
				.status(status)
				.orderDate(orderDate)
				.marketSpecificData(Map.of(
					"siteId", siteId,
					"siteOrderNo", siteOrderNo,
					"orderNo", orderNo,
					"buyerId", buyerId,
					"deliveryStatus", deliveryStatus))
				.build();
		} catch (Exception e) {
			log.error("[ESM+] 주문 파싱 실패: {}", e.getMessage());
			return null;
		}
	}

	private LocalDateTime parseDateTime(String dateTimeStr) {
		if (dateTimeStr == null || dateTimeStr.isEmpty()) {
			return LocalDateTime.now();
		}
		try {
			return LocalDateTime.parse(dateTimeStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
		} catch (Exception e) {
			try {
				return LocalDateTime.parse(dateTimeStr.substring(0, 19),
					DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
			} catch (Exception ex) {
				return LocalDateTime.now();
			}
		}
	}
}
