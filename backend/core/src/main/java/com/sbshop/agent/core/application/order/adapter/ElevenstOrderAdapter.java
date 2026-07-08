package com.sbshop.agent.core.application.order.adapter;

import com.sbshop.agent.core.application.order.port.ElevenstOrderApiPort;
import com.sbshop.agent.core.application.order.port.MarketOrderPort;
import com.sbshop.agent.core.application.order.util.ElevenstXmlUtils;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.application.order.dto.MarketOrderDto;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.application.order.mapper.ElevenstStatusMapper;
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
import org.w3c.dom.Element;

/**
 * 11번가 주문 API 어댑터
 * ElevenstOrderApiPort를 MarketOrderPort로 래핑하여 통합 인터페이스 제공
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ElevenstOrderAdapter implements MarketOrderPort {

	private final ElevenstOrderApiPort elevenstOrderApiPort;
	private final ElevenstStatusMapper statusMapper;

	@Override
	public MarketType getMarketType() {
		return MarketType.ELEVEN_STREET;
	}

	@Override
	public List<MarketOrderDto> fetchOrders(MarketCredential credential,
		LocalDate fromDate, LocalDate toDate) {
		List<MarketOrderDto> result = new ArrayList<>();

		String apiKey = credential.getAccessKey();

		// D-043: chunk별 성공/실패 집계. 전량 실패 시 예외 전파 → 서비스 catch → SYNC_FAILED → 액션로그 FAILED.
		int successChunks = 0;
		int failedChunks = 0;
		Exception lastFailure = null;

		// 11번가는 최대 7일 조회 가능 -> 7일 단위로 분할
		LocalDate current = fromDate;
		while (!current.isAfter(toDate)) {
			LocalDate chunkEnd = current.plusDays(6).isAfter(toDate) ? toDate : current.plusDays(6);
			String startTime = formatDateTime(current, "0000");
			String endTime = formatDateTime(chunkEnd, "2359");

			try {
				// 1. 결제완료 주문 조회 (신규 주문 - 전체 데이터 포함)
				List<Element> completedOrders = elevenstOrderApiPort.fetchCompletedOrders(apiKey, startTime, endTime);
				for (Element orderElement : completedOrders) {
					MarketOrderDto dto = parseOrderElement(orderElement, "complete");
					if (dto != null) {
						result.add(dto);
					}
				}
				Thread.sleep(500);

				// 2. 배송준비중 주문 조회 (발주확인 - 전체 데이터 포함)
				List<Element> packagingOrders = elevenstOrderApiPort.fetchPackagingOrders(apiKey, startTime, endTime);
				for (Element orderElement : packagingOrders) {
					MarketOrderDto dto = parseOrderElement(orderElement, "packaging");
					if (dto != null) {
						result.add(dto);
					}
				}
				Thread.sleep(500);

				// 3. 배송중 주문 조회 (최소 정보 - 개별 조회로 폴백)
				List<Element> shippingOrders = elevenstOrderApiPort.fetchShippingOrders(apiKey, startTime, endTime);
				for (Element orderElement : shippingOrders) {
					MarketOrderDto dto = parseShippingElement(orderElement);
					if (dto != null) {
						result.add(dto);
					}
				}
				Thread.sleep(500);

				// 4. 배송완료 주문 조회 (전체 데이터 포함 - 신규 주문 생성 가능)
				List<Element> dlvCompletedOrders = elevenstOrderApiPort.fetchCompletedDeliveryOrders(apiKey, startTime,
					endTime);
				for (Element orderElement : dlvCompletedOrders) {
					MarketOrderDto dto = parseOrderElement(orderElement, "dlvcompleted");
					if (dto != null) {
						result.add(dto);
					}
				}
				Thread.sleep(500);
				successChunks++;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			} catch (Exception e) {
				failedChunks++;
				lastFailure = e;
				log.error("11번가 주문 조회 실패 ({}~{}): {}", current, chunkEnd, e.getMessage());
			}

			current = chunkEnd.plusDays(1);
		}

		// 전량 실패(성공 0 · 오류≥1)면 대표 오류를 담아 전파. 부분 성공은 result 반환(경고).
		if (successChunks == 0 && failedChunks > 0) {
			String detail = lastFailure != null ? lastFailure.getMessage() : "알 수 없는 오류";
			throw new RuntimeException("11번가 주문 조회 실패: " + detail, lastFailure);
		}
		if (failedChunks > 0) {
			log.warn("11번가 주문 부분 조회: {} chunk 성공, {} chunk 실패 (마지막 오류: {})",
				successChunks, failedChunks, lastFailure != null ? lastFailure.getMessage() : "-");
		}

		return result;
	}

	@Override
	public void shipOrder(MarketCredential credential,
		Order order, OrderLineItem lineItem,
		String trackingNo, ShippingCarrier carrier) {
		String apiKey = credential.getAccessKey();
		String dlvNo = order.getMarketOrderNo();
		String dlvMthdCd = "01"; // 택배
		String dlvEtprsCd = mapCarrierCode(carrier);
		String sendDt = formatDateTime(LocalDateTime.now(), "yyyyMMddHHmm");

		elevenstOrderApiPort.shipOrder(apiKey, sendDt, dlvMthdCd, dlvEtprsCd, trackingNo, dlvNo);
	}

	@Override
	public void acceptOrders(MarketCredential credential, Order order) {
		Map<String, String> data = order.getMarketSpecificDataMap();
		if (data == null || !data.containsKey("ordPrdSeq")) {
			throw new IllegalArgumentException(
				"11번가 발주확인 정보 부족: order=" + order.getMarketOrderNo());
		}
		elevenstOrderApiPort.confirmOrder(
			credential.getAccessKey(),
			order.getMarketOrderNo(),
			data.get("ordPrdSeq"),
			data.getOrDefault("addPrdYn", "N"),
			data.getOrDefault("addPrdNo", "0"),
			data.getOrDefault("dlvNo", order.getMarketOrderNo()));
	}

	@Override
	public void cancelOrder(MarketCredential credential, Order order) {
		throw new UnsupportedOperationException(
			"11번가 주문취소는 판매자 센터에서 수동 처리 필요: order=" + order.getMarketOrderNo());
	}

	@Override
	public MarketOrderDto fetchOrderDetail(MarketCredential credential, MarketOrderDto dto) {
		String apiKey = credential.getAccessKey();
		String ordNo = dto.getMarketOrderNo();
		try {
			List<Element> details = elevenstOrderApiPort.fetchOrderDetail(apiKey, ordNo);
			if (!details.isEmpty()) {
				return parseOrderDetailElement(details.get(0));
			}
		} catch (Exception e) {
			log.error("11번가 주문 상세 조회 실패: ordNo={}, error={}", ordNo, e.getMessage());
		}
		return null;
	}

	private String mapCarrierCode(ShippingCarrier carrier) {
		if (carrier == null) {
			throw new IllegalArgumentException("배송사 정보가 없습니다.");
		}
		return switch (carrier) {
			case CJ_LOGISTICS -> "00034";
			case HANJIN -> "00011";
			case KOREA_POST -> "00007";
			case LOTTE_LOGISTICS -> "00012";
			case ROCKET -> "00002";
			default -> "00034";
		};
	}

	/**
	 * 11번가 택배사 코드를 ShippingCarrier enum으로 변환
	 */
	private ShippingCarrier parseCarrierCode(String dlvEtprsCd) {
		if (dlvEtprsCd == null || dlvEtprsCd.isEmpty()) {
			return ShippingCarrier.CJ_LOGISTICS;
		}
		return switch (dlvEtprsCd) {
			case "00034" -> ShippingCarrier.CJ_LOGISTICS;
			case "00011" -> ShippingCarrier.HANJIN;
			case "00007" -> ShippingCarrier.KOREA_POST;
			case "00012" -> ShippingCarrier.LOTTE_LOGISTICS;
			case "00002" -> ShippingCarrier.ROCKET;
			default -> ShippingCarrier.CJ_LOGISTICS;
		};
	}

	/**
	 * 주문번호에서 주문일 추출 (YYYYMMDD + 9자리 시퀀스)
	 * 예: 20260612076034242 → 2026-06-12
	 */
	private LocalDateTime extractOrderDate(String ordNo) {
		if (ordNo != null && ordNo.length() >= 8) {
			try {
				String dateStr = ordNo.substring(0, 8);
				return LocalDate.parse(dateStr, DateTimeFormatter.ofPattern("yyyyMMdd"))
					.atStartOfDay();
			} catch (Exception e) {
				log.warn("주문번호에서 주문일 추출 실패: ordNo={}", ordNo);
			}
		}
		return LocalDateTime.now();
	}

	/**
	 * 개별 주문 조회 API 응답 파싱 (전체 데이터)
	 */
	private MarketOrderDto parseOrderDetailElement(Element element) {
		try {
			String ordNo = ElevenstXmlUtils.getElementText(element, "ordNo");
			String prdNm = ElevenstXmlUtils.getElementText(element, "prdNm");
			String sellerPrdCd = ElevenstXmlUtils.getElementText(element, "sellerPrdCd");
			String ordQty = ElevenstXmlUtils.getElementText(element, "ordQty");
			String invcNo = ElevenstXmlUtils.getElementText(element, "invcNo");
			String dlvEtprsCd = ElevenstXmlUtils.getElementText(element, "dlvEtprsCd");

			// 수령자 정보
			String rcvrNm = ElevenstXmlUtils.getElementText(element, "rcvrNm");
			String rcvrPrtblNo = ElevenstXmlUtils.getElementText(element, "rcvrPrtblNo");
			String rcvrMailNo = ElevenstXmlUtils.getElementText(element, "rcvrMailNo");
			String rcvrBaseAddr = ElevenstXmlUtils.getElementText(element, "rcvrBaseAddr");
			String rcvrDtlsAddr = ElevenstXmlUtils.getElementText(element, "rcvrDtlsAddr");

			// 발주확인용 필드
			String ordPrdSeq = ElevenstXmlUtils.getElementText(element, "ordPrdSeq");
			String addPrdYn = ElevenstXmlUtils.getElementText(element, "addPrdYn");
			String addPrdNo = ElevenstXmlUtils.getElementText(element, "addPrdNo");
			String dlvNo = ElevenstXmlUtils.getElementText(element, "dlvNo");

			// 주문번호에서 주문일 추출
			LocalDateTime orderDate = extractOrderDate(ordNo);
			ShippingCarrier carrier = parseCarrierCode(dlvEtprsCd);

			// 마켓별 상세 데이터
			Map<String, Object> marketData = new java.util.HashMap<>();
			if (ordPrdSeq != null && !ordPrdSeq.isEmpty())
				marketData.put("ordPrdSeq", ordPrdSeq);
			if (addPrdYn != null && !addPrdYn.isEmpty())
				marketData.put("addPrdYn", addPrdYn);
			if (addPrdNo != null && !addPrdNo.isEmpty())
				marketData.put("addPrdNo", addPrdNo);
			if (dlvNo != null && !dlvNo.isEmpty())
				marketData.put("dlvNo", dlvNo);

			return MarketOrderDto.builder()
				.marketType(getMarketType())
				.marketOrderNo(ordNo)
				.marketProductCode(sellerPrdCd)
				.productName(prdNm)
				.quantity(parseIntValue(ordQty))
				.orderPrice(BigDecimal.ZERO)
				.totalAmount(BigDecimal.ZERO)
				.recipientName(rcvrNm)
				.recipientPhone(rcvrPrtblNo)
				.zipcode(rcvrMailNo)
				.address(rcvrBaseAddr + " " + rcvrDtlsAddr)
				.message("")
				.ordererName("")
				.ordererPhone("")
				// D-031: 주문상세(claimservice/orderlistalladdr)는 배송 상태 필드를 제공하지 않으므로
				// 상태를 조작하지 않고 미설정(null)으로 둔다 — enrichment 시 기존 상태를 덮어쓰지 않음.
				.trackingNo(invcNo)
				.carrier(carrier)
				.orderDate(orderDate)
				.marketSpecificData(marketData)
				.build();
		} catch (Exception e) {
			log.error("11번가 주문 상세 파싱 실패: {}", e.getMessage());
			return null;
		}
	}

	/**
	 * XML 엘리먼트를 MarketOrderDto로 변환
	 */
	private MarketOrderDto parseOrderElement(Element element, String source) {
		try {
			String ordNo = ElevenstXmlUtils.getElementText(element, "ordNo");
			String prdNm = ElevenstXmlUtils.getElementText(element, "prdNm");
			String sellerPrdCd = ElevenstXmlUtils.getElementText(element, "sellerPrdCd");
			String selPrc = ElevenstXmlUtils.getElementText(element, "selPrc");
			String ordQty = ElevenstXmlUtils.getElementText(element, "ordQty");
			String ordAmt = ElevenstXmlUtils.getElementText(element, "ordAmt");

			// 수령자 정보
			String rcvrNm = ElevenstXmlUtils.getElementText(element, "rcvrNm");
			String rcvrPrtblNo = ElevenstXmlUtils.getElementText(element, "rcvrPrtblNo");
			String rcvrMailNo = ElevenstXmlUtils.getElementText(element, "rcvrMailNo");
			String rcvrBaseAddr = ElevenstXmlUtils.getElementText(element, "rcvrBaseAddr");
			String rcvrDtlsAddr = ElevenstXmlUtils.getElementText(element, "rcvrDtlsAddr");
			String ordDlvReqCont = ElevenstXmlUtils.getElementText(element, "ordDlvReqCont");

			// 구매자 정보
			String ordNm = ElevenstXmlUtils.getElementText(element, "ordNm");
			String ordPrtblTel = ElevenstXmlUtils.getElementText(element, "ordPrtblTel");

			// 통관번호
			String psnCscUniqNo = ElevenstXmlUtils.getElementText(element, "psnCscUniqNo");

			// 배송 정보
			String invcNo = ElevenstXmlUtils.getElementText(element, "invcNo");
			String dlvEtprsCd = ElevenstXmlUtils.getElementText(element, "dlvEtprsCd");
			ShippingCarrier carrier = parseCarrierCode(dlvEtprsCd);

			// 발주확인용 필드
			String ordPrdSeq = ElevenstXmlUtils.getElementText(element, "ordPrdSeq");
			String addPrdYn = ElevenstXmlUtils.getElementText(element, "addPrdYn");
			String addPrdNo = ElevenstXmlUtils.getElementText(element, "addPrdNo");
			String dlvNo = ElevenstXmlUtils.getElementText(element, "dlvNo");

			// 주문번호에서 주문일 추출
			LocalDateTime orderDate = extractOrderDate(ordNo);

			// 배송 상태 매핑
			Map<String, String> statusMap = Map.of("source", source);
			ShippingStatus status = statusMapper.mapStatus(statusMap);

			// 가격 파싱
			BigDecimal price = parseBigDecimal(selPrc);
			int qty = parseIntValue(ordQty);
			BigDecimal totalAmount = parseBigDecimal(ordAmt);

			// 마켓별 상세 데이터
			Map<String, Object> marketData = new java.util.HashMap<>();
			if (ordPrdSeq != null && !ordPrdSeq.isEmpty())
				marketData.put("ordPrdSeq", ordPrdSeq);
			if (addPrdYn != null && !addPrdYn.isEmpty())
				marketData.put("addPrdYn", addPrdYn);
			if (addPrdNo != null && !addPrdNo.isEmpty())
				marketData.put("addPrdNo", addPrdNo);
			if (dlvNo != null && !dlvNo.isEmpty())
				marketData.put("dlvNo", dlvNo);

			return MarketOrderDto.builder()
				.marketType(getMarketType())
				.marketOrderNo(ordNo)
				.marketProductCode(sellerPrdCd)
				.productName(prdNm)
				.quantity(qty)
				.orderPrice(price)
				.totalAmount(totalAmount)
				.recipientName(rcvrNm)
				.recipientPhone(rcvrPrtblNo)
				.zipcode(rcvrMailNo)
				.address(rcvrBaseAddr + " " + rcvrDtlsAddr)
				.message(ordDlvReqCont)
				.ordererName(ordNm)
				.ordererPhone(ordPrtblTel)
				.customsClearanceNo(psnCscUniqNo)
				.trackingNo(invcNo)
				.carrier(carrier)
				.status(status)
				.orderDate(orderDate)
				.marketSpecificData(marketData)
				.build();
		} catch (Exception e) {
			log.error("11번가 주문 파싱 실패: {}", e.getMessage());
			return null;
		}
	}

	/**
	 * 배송중 API 응답 파싱 (최소 정보 - 상태/운송장 업데이트용)
	 */
	private MarketOrderDto parseShippingElement(Element element) {
		try {
			String ordNo = ElevenstXmlUtils.getElementText(element, "ordNo");
			String invcNo = ElevenstXmlUtils.getElementText(element, "invcNo");
			String dlvEtprsCd = ElevenstXmlUtils.getElementText(element, "dlvEtprsCd");

			if (ordNo == null || ordNo.isEmpty()) {
				return null;
			}

			ShippingStatus status = ShippingStatus.SHIPPED;
			ShippingCarrier carrier = parseCarrierCode(dlvEtprsCd);

			return MarketOrderDto.builder()
				.marketType(getMarketType())
				.marketOrderNo(ordNo)
				.marketProductCode("")
				.productName("")
				.quantity(0)
				.orderPrice(BigDecimal.ZERO)
				.totalAmount(BigDecimal.ZERO)
				.recipientName("")
				.recipientPhone("")
				.zipcode("")
				.address("")
				.message("")
				.ordererName("")
				.ordererPhone("")
				.status(status)
				.trackingNo(invcNo)
				.carrier(carrier)
				.orderDate(extractOrderDate(ordNo))
				.build();
		} catch (Exception e) {
			log.error("11번가 배송중 주문 파싱 실패: {}", e.getMessage());
			return null;
		}
	}

	private String formatDateTime(LocalDate date, String time) {
		return date.format(DateTimeFormatter.ofPattern("yyyyMMdd")) + time;
	}

	private String formatDateTime(LocalDateTime dateTime, String pattern) {
		return dateTime.format(DateTimeFormatter.ofPattern(pattern));
	}

	private LocalDateTime parseDateTime(String dateTimeStr) {
		if (dateTimeStr == null || dateTimeStr.isEmpty()) {
			return LocalDateTime.now();
		}
		try {
			return LocalDateTime.parse(dateTimeStr, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
		} catch (Exception e) {
			return LocalDateTime.now();
		}
	}

	private BigDecimal parseBigDecimal(String value) {
		if (value == null || value.isEmpty()) {
			return BigDecimal.ZERO;
		}
		try {
			return new BigDecimal(value);
		} catch (NumberFormatException e) {
			return BigDecimal.ZERO;
		}
	}

	private int parseIntValue(String value) {
		if (value == null || value.isEmpty()) {
			return 0;
		}
		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException e) {
			return 0;
		}
	}
}
