package com.sbshop.agent.core.application.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.order.port.EsmplusOrderApiPort;
import com.sbshop.agent.core.application.order.port.MarketOrderPort;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.dto.MarketOrderDto;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

		if (masterId == null || masterId.isEmpty()) {
			log.warn("[ESM+] 크레덴셜에 masterId(accessKey)가 없습니다");
			return result;
		}

		try {
			List<MarketOrderDto> orders = esmplusOrderApiPort.fetchOrders(masterId, password, fromDate, toDate);
			result.addAll(orders);
			log.info("[ESM+] {} 건의 주문 조회 완료 ({}~{})", orders.size(), fromDate, toDate);
		} catch (Exception e) {
			log.error("[ESM+] 주문 조회 실패: {}", e.getMessage(), e);
		}

		return result;
	}

	@Override
	public void shipOrder(MarketCredential credential,
		Order order, OrderLineItem lineItem,
		String trackingNo, ShippingCarrier carrier) {
		// ESM+는 배송 시작 API가 다를 수 있음 (추후 구현)
		log.warn("[ESM+] shipOrder 미구현: order={}", order.getMarketOrderNo());
	}

	@Override
	public void acceptOrders(MarketCredential credential, Order order) {
		// ESM+는 주문 접수 API가 다를 수 있음 (추후 구현)
		log.warn("[ESM+] acceptOrders 미구현: order={}", order.getMarketOrderNo());
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

	@Override
	public String mapCarrierCode(ShippingCarrier carrier) {
		if (carrier == null) {
			return "CJGLS";
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

			// 상태 매핑
			ShippingStatus status = statusMapper.mapStatus(deliveryStatusCode);
			if (status == ShippingStatus.NEW && deliveryStatusCode != 1010) {
				ShippingStatus fallback = statusMapper.mapStatus(deliveryStatus);
				if (fallback != ShippingStatus.NEW) {
					status = fallback;
				}
			}

			// 배송 상태가 취소/교환이면 스킵 (반품은 포함)
			if (status == ShippingStatus.CANCELED || status == ShippingStatus.EXCHANGED) {
				log.debug("[ESM+] 주문 {} 상태 {} 스킵", orderNo, deliveryStatus);
				return null;
			}

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
				.marketSpecificData(java.util.Map.of(
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
