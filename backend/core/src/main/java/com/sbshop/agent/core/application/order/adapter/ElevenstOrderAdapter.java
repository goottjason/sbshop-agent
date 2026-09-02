package com.sbshop.agent.core.application.order.adapter;

import com.sbshop.agent.core.application.order.port.ElevenstOrderApiPort;
import com.sbshop.agent.core.application.order.port.MarketOrderPort;
import com.sbshop.agent.core.application.order.util.ElevenstXmlUtils;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.application.order.dto.MarketFetchOutcome;
import com.sbshop.agent.core.application.order.dto.MarketLineItemDto;
import com.sbshop.agent.core.application.order.dto.MarketOrderDto;
import com.sbshop.agent.core.application.order.dto.MarketShipmentDto;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.vo.ClaimData;
import com.sbshop.agent.core.application.order.mapper.ElevenstStatusMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;

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

	private static final int STATUS_LOOKUP_CHUNK = 100;

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
		String apiKey = credential.getAccessKey();
		String dlvNo = resolveDeliveryNo(order);
		String dlvMthdCd = "01";
		String dlvEtprsCd = mapCarrierCode(carrier);
		String sendDt = formatDateTime(LocalDateTime.now(), "yyyyMMddHHmm");

		String ordPrdSeq = lineItem != null ? lineItem.getMarketLineItemNo() : null;
		if (ordPrdSeq != null && !ordPrdSeq.isBlank()) {
			elevenstOrderApiPort.shipOrderPartial(apiKey, sendDt, dlvMthdCd, dlvEtprsCd,
				trackingNo, dlvNo, order.getMarketOrderNo(), ordPrdSeq);
			return;
		}
		elevenstOrderApiPort.shipOrder(apiKey, sendDt, dlvMthdCd, dlvEtprsCd, trackingNo, dlvNo);
	}

	@Override
	public void acceptOrders(MarketCredential credential, Order order) {
		Map<String, String> data = order.getMarketSpecificDataMap();
		List<String> seqs = resolveProductOrderSeqs(data);
		if (seqs.isEmpty()) {
			throw new IllegalArgumentException(
				"11번가 발주확인 정보 부족: order=" + order.getMarketOrderNo());
		}
		String addPrdYn = data.getOrDefault("addPrdYn", "N");
		String addPrdNo = data.getOrDefault("addPrdNo", "0");
		String dlvNo = data.getOrDefault("dlvNo", order.getMarketOrderNo());

		for (String seq : seqs) {
			elevenstOrderApiPort.confirmOrder(credential.getAccessKey(),
				order.getMarketOrderNo(), seq, addPrdYn, addPrdNo, dlvNo);
		}
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

	public MissingOrderState resolveMissingOrderState(String apiKey, String ordNo) {
		try {
			List<Element> details = elevenstOrderApiPort.fetchOrderDetail(apiKey, ordNo);
			if (details == null || details.isEmpty()) {
				return MissingOrderState.empty();
			}
			Map<String, ShippingStatus> statuses = new LinkedHashMap<>();
			Map<String, ClaimData> claims = new LinkedHashMap<>();
			Map<String, String> trackingNos = new LinkedHashMap<>();
			for (Element el : details) {
				String seq = emptyToNull(ElevenstXmlUtils.getElementText(el, "ordPrdSeq"));
				String key = seq != null ? seq : CLAIM_ORDER_WIDE;
				String statNm = ElevenstXmlUtils.getElementText(el, "ordPrdStatNm");

				ShippingStatus status = statusMapper.mapProductOrderStatus(statNm);
				if (isTerminalStatus(status)) {
					statuses.put(key, status);
				}
				ClaimData claim = statusMapper.mapClaimByStatusName(statNm);
				if (claim.getClaimType().isActive()) {
					claims.put(key, claim);
				}

				String invcNo = emptyToNull(ElevenstXmlUtils.getElementText(el, "invcNo"));
				if (invcNo != null) {
					trackingNos.put(key, invcNo);
				}
			}
			return new MissingOrderState(statuses, claims, trackingNos);
		} catch (Exception e) {
			log.warn("11번가 사라진 주문 상태 조회 실패: ordNo={}, error={}", ordNo, e.getMessage());
			return MissingOrderState.empty();
		}
	}

	private MarketFetchOutcome doFetchOrders(MarketCredential credential,
		LocalDate fromDate, LocalDate toDate) {
		Map<String, OrderAccumulator> orders = new LinkedHashMap<>();
		String apiKey = credential.getAccessKey();

		int successChunks = 0;
		int failedChunks = 0;
		Exception lastFailure = null;

		LocalDate current = fromDate;
		while (!current.isAfter(toDate)) {
			LocalDate chunkEnd = current.plusDays(6).isAfter(toDate) ? toDate : current.plusDays(6);
			String startTime = formatDateTime(current, "0000");
			String endTime = formatDateTime(chunkEnd, "2359");

			try {
				collectRows(orders, elevenstOrderApiPort.fetchCompletedOrders(apiKey, startTime, endTime), "complete");
				Thread.sleep(500);
				collectRows(orders, elevenstOrderApiPort.fetchPackagingOrders(apiKey, startTime, endTime), "packaging");
				Thread.sleep(500);
				collectShippingRows(orders, elevenstOrderApiPort.fetchShippingOrders(apiKey, startTime, endTime));
				Thread.sleep(500);
				collectRows(orders, elevenstOrderApiPort.fetchCompletedDeliveryOrders(apiKey, startTime, endTime),
					"dlvcompleted");
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

		if (successChunks == 0 && failedChunks > 0) {
			String detail = lastFailure != null ? lastFailure.getMessage() : "알 수 없는 오류";
			throw new RuntimeException("11번가 주문 조회 실패: " + detail, lastFailure);
		}
		if (failedChunks > 0) {
			log.warn("11번가 주문 부분 조회: {} chunk 성공, {} chunk 실패 (마지막 오류: {})",
				successChunks, failedChunks, lastFailure != null ? lastFailure.getMessage() : "-");
		}

		applyProductOrderStatuses(apiKey, orders);
		enrichMissingRecipients(apiKey, orders);

		List<MarketOrderDto> result = new ArrayList<>();
		for (OrderAccumulator accum : orders.values()) {
			MarketOrderDto dto = accum.toNestedDto(getMarketType(), statusMapper);
			if (dto != null) {
				result.add(dto);
			}
		}
		return failedChunks == 0
			? MarketFetchOutcome.complete(result)
			: MarketFetchOutcome.partial(result);
	}

	private void collectRows(Map<String, OrderAccumulator> orders, List<Element> rows, String source) {
		if (rows == null) {
			return;
		}
		for (Element row : rows) {
			String ordNo = ElevenstXmlUtils.getElementText(row, "ordNo");
			if (ordNo == null || ordNo.isEmpty()) {
				continue;
			}
			OrderAccumulator accum = orders.computeIfAbsent(ordNo, OrderAccumulator::new);
			try {
				accum.addDetailRow(row, source, statusMapper);
			} catch (Exception e) {
				log.error("11번가 주문 파싱 실패: ordNo={}, error={}", ordNo, e.getMessage());
			}
		}
	}

	private void collectShippingRows(Map<String, OrderAccumulator> orders, List<Element> rows) {
		if (rows == null) {
			return;
		}
		for (Element row : rows) {
			String ordNo = ElevenstXmlUtils.getElementText(row, "ordNo");
			if (ordNo == null || ordNo.isEmpty()) {
				continue;
			}
			OrderAccumulator accum = orders.computeIfAbsent(ordNo, OrderAccumulator::new);
			try {
				accum.addShippingRow(row, statusMapper);
			} catch (Exception e) {
				log.error("11번가 배송중 주문 파싱 실패: ordNo={}, error={}", ordNo, e.getMessage());
			}
		}
	}

	private void applyProductOrderStatuses(String apiKey, Map<String, OrderAccumulator> orders) {
		List<String> ordNos = new ArrayList<>(orders.keySet());
		for (int i = 0; i < ordNos.size(); i += STATUS_LOOKUP_CHUNK) {
			List<String> chunk = ordNos.subList(i, Math.min(i + STATUS_LOOKUP_CHUNK, ordNos.size()));
			String joined = String.join(",", chunk);
			try {
				List<Element> rows = elevenstOrderApiPort.fetchProductOrderStatuses(apiKey, joined);
				if (rows == null || rows.isEmpty()) {
					log.warn("11번가 상품주문상태 응답 없음: {}건 요청 — 목록 소속 상태로 폴백", chunk.size());
					continue;
				}
				for (Element row : rows) {
					String ordNo = ElevenstXmlUtils.getElementText(row, "ordNo");
					OrderAccumulator accum = orders.get(ordNo);
					if (accum != null) {
						accum.addStatusRow(row);
					}
				}
			} catch (Exception e) {
				log.warn("11번가 상품주문상태 조회 실패({}건) — 목록 소속 상태로 폴백: {}",
					chunk.size(), e.getMessage());
			}
		}
	}

	private void enrichMissingRecipients(String apiKey, Map<String, OrderAccumulator> orders) {
		for (OrderAccumulator accum : orders.values()) {
			if (accum.recipientName != null) {
				continue;
			}
			try {
				List<Element> details = elevenstOrderApiPort.fetchOrderDetail(apiKey, accum.ordNo);
				if (details == null || details.isEmpty()) {
					continue;
				}
				accum.fillOrderCommon(details.get(0));
				Thread.sleep(300);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			} catch (Exception e) {
				log.warn("11번가 수취인 상세 복원 실패: ordNo={}, error={}", accum.ordNo, e.getMessage());
			}
		}
	}

	private static String formatDateTime(LocalDate date, String time) {
		return date.format(DateTimeFormatter.ofPattern("yyyyMMdd")) + time;
	}

	private static String formatDateTime(LocalDateTime dateTime, String pattern) {
		return dateTime.format(DateTimeFormatter.ofPattern(pattern));
	}

	private String resolveDeliveryNo(Order order) {
		Map<String, String> data = order.getMarketSpecificDataMap();
		String dlvNo = data != null ? data.get("dlvNo") : null;
		if (dlvNo == null || dlvNo.isBlank()) {
			throw new IllegalArgumentException(
				"11번가 발송처리 불가 — 배송번호(dlvNo) 없음: order=" + order.getMarketOrderNo()
					+ " (주문 동기화로 배송번호를 먼저 확보해야 합니다)");
		}
		return dlvNo;
	}

	public record MissingOrderState(Map<String, ShippingStatus> statuses, Map<String, ClaimData> claims,
		Map<String, String> trackingNos) {
		public static MissingOrderState empty() {
			return new MissingOrderState(Map.of(), Map.of(), Map.of());
		}

		public boolean isEmpty() {
			return statuses.isEmpty() && claims.isEmpty() && trackingNos.isEmpty();
		}
	}

	public static final String CLAIM_ORDER_WIDE = "*";

	private static String mapCarrierCode(ShippingCarrier carrier) {
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

	private List<String> resolveProductOrderSeqs(Map<String, String> data) {
		if (data == null) {
			return List.of();
		}
		String all = data.get("ordPrdSeqs");
		if (all != null && !all.isBlank()) {
			List<String> seqs = new ArrayList<>();
			for (String part : all.split("[|,]")) {
				String seq = part.trim();
				if (!seq.isEmpty()) {
					seqs.add(seq);
				}
			}
			if (!seqs.isEmpty()) {
				return seqs;
			}
		}
		String single = data.get("ordPrdSeq");
		return (single != null && !single.isBlank()) ? List.of(single) : List.of();
	}

	private MarketOrderDto parseOrderDetailElement(Element element) {
		try {
			String ordNo = ElevenstXmlUtils.getElementText(element, "ordNo");
			String prdNm = ElevenstXmlUtils.getElementText(element, "prdNm");
			String sellerPrdCd = ElevenstXmlUtils.getElementText(element, "sellerPrdCd");
			String ordQty = ElevenstXmlUtils.getElementText(element, "ordQty");
			String invcNo = ElevenstXmlUtils.getElementText(element, "invcNo");
			String dlvEtprsCd = ElevenstXmlUtils.getElementText(element, "dlvEtprsCd");

			String rcvrNm = ElevenstXmlUtils.getElementText(element, "rcvrNm");
			String rcvrPrtblNo = ElevenstXmlUtils.getElementText(element, "rcvrPrtblNo");
			String rcvrMailNo = ElevenstXmlUtils.getElementText(element, "rcvrMailNo");
			String rcvrBaseAddr = ElevenstXmlUtils.getElementText(element, "rcvrBaseAddr");
			String rcvrDtlsAddr = ElevenstXmlUtils.getElementText(element, "rcvrDtlsAddr");

			String psnCscUniqNo = emptyToNull(ElevenstXmlUtils.getElementText(element, "psnCscUniqNo"));

			String ordPrdSeq = ElevenstXmlUtils.getElementText(element, "ordPrdSeq");
			String addPrdYn = ElevenstXmlUtils.getElementText(element, "addPrdYn");
			String addPrdNo = ElevenstXmlUtils.getElementText(element, "addPrdNo");
			String dlvNo = ElevenstXmlUtils.getElementText(element, "dlvNo");

			LocalDateTime orderDate = extractOrderDate(ordNo);
			ShippingCarrier carrier = parseCarrierCode(dlvEtprsCd);

			Map<String, Object> marketData = new HashMap<>();
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
				.customsClearanceNo(psnCscUniqNo)
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

	private static ShippingCarrier parseCarrierCode(String dlvEtprsCd) {
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

	private static LocalDateTime extractOrderDate(String ordNo) {
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

	private static String emptyToNull(String value) {
		return (value == null || value.isEmpty()) ? null : value;
	}

	private static int parseIntValue(String value) {
		if (value == null || value.isEmpty()) {
			return 0;
		}
		try {
			return Integer.parseInt(value);
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private static boolean isTerminalStatus(ShippingStatus status) {
		return status == ShippingStatus.DELIVERED
			|| status == ShippingStatus.CONFIRMED;
	}

	private static BigDecimal parseBigDecimal(String value) {
		if (value == null || value.isEmpty()) {
			return BigDecimal.ZERO;
		}
		try {
			return new BigDecimal(value);
		} catch (NumberFormatException e) {
			return BigDecimal.ZERO;
		}
	}

	private static final class OrderAccumulator {
		private final String ordNo;

		private String recipientName;
		private String recipientPhone;
		private String zipcode;
		private String address;
		private String message;
		private String ordererName;
		private String ordererPhone;
		private String customsClearanceNo;
		private LocalDateTime orderDate;

		private final Map<String, DetailRow> detailRows = new LinkedHashMap<>();
		private final Map<String, StatusRow> statusRows = new LinkedHashMap<>();
		private final Map<String, TrackingInfo> trackingByDlvNo = new LinkedHashMap<>();
		private final Map<String, TrackingInfo> trackingBySeq = new LinkedHashMap<>();
		private TrackingInfo trackingWithoutDlvNo;
		private ShippingStatus fallbackListStatus;

		private OrderAccumulator(String ordNo) {
			this.ordNo = ordNo;
		}

		private record DetailRow(String sellerPrdCd, String productName, Integer quantity,
			BigDecimal orderPrice, BigDecimal totalAmount, ShippingStatus listStatus,
			String dlvNo, String addPrdYn, String addPrdNo) {
		}

		private record StatusRow(String statusName, String dlvNo, Integer quantity,
			BigDecimal settlementAmount, BigDecimal sellerFee, BigDecimal marketDiscount,
			String prdNo, String productName) {
		}

		private record TrackingInfo(String trackingNo, ShippingCarrier carrier) {
		}

		private void addDetailRow(Element row, String source, ElevenstStatusMapper mapper) {
			fillOrderCommon(row);

			String seq = emptyToNull(ElevenstXmlUtils.getElementText(row, "ordPrdSeq"));
			String dlvNo = emptyToNull(ElevenstXmlUtils.getElementText(row, "dlvNo"));
			captureTracking(row, dlvNo);

			ShippingStatus listStatus = mapper.mapStatus(Map.of("source", source));
			if (seq == null) {
				log.warn("11번가 {} 목록 행에 ordPrdSeq가 없다: ordNo={} — 상품주문 로스터에서 제외", source, ordNo);
				if (fallbackListStatus == null) {
					fallbackListStatus = listStatus;
				}
				return;
			}

			DetailRow parsed = new DetailRow(
				emptyToNull(ElevenstXmlUtils.getElementText(row, "sellerPrdCd")),
				emptyToNull(ElevenstXmlUtils.getElementText(row, "prdNm")),
				parseIntValue(ElevenstXmlUtils.getElementText(row, "ordQty")),
				parseBigDecimal(ElevenstXmlUtils.getElementText(row, "selPrc")),
				parseBigDecimal(ElevenstXmlUtils.getElementText(row, "ordAmt")),
				listStatus,
				dlvNo,
				emptyToNull(ElevenstXmlUtils.getElementText(row, "addPrdYn")),
				emptyToNull(ElevenstXmlUtils.getElementText(row, "addPrdNo")));

			DetailRow existing = detailRows.get(seq);
			detailRows.put(seq, existing == null ? parsed : mergeDetail(existing, parsed));
		}

		private void addShippingRow(Element row, ElevenstStatusMapper mapper) {
			fillOrderCommon(row);
			String dlvNo = emptyToNull(ElevenstXmlUtils.getElementText(row, "dlvNo"));
			captureTracking(row, dlvNo);
			if (dlvNo == null) {
				String seq = emptyToNull(ElevenstXmlUtils.getElementText(row, "ordPrdSeq"));
				String invcNo = emptyToNull(ElevenstXmlUtils.getElementText(row, "invcNo"));
				if (seq != null && invcNo != null) {
					trackingBySeq.putIfAbsent(seq, new TrackingInfo(invcNo,
						parseCarrierCode(ElevenstXmlUtils.getElementText(row, "dlvEtprsCd"))));
				}
			}
			if (fallbackListStatus == null) {
				fallbackListStatus = mapper.mapStatus(Map.of("source", "shipping"));
			}
		}

		private void addStatusRow(Element row) {
			String seq = emptyToNull(ElevenstXmlUtils.getElementText(row, "ordPrdSeq"));
			if (seq == null) {
				return;
			}
			statusRows.put(seq, new StatusRow(
				emptyToNull(ElevenstXmlUtils.getElementText(row, "ordPrdStatNm")),
				emptyToNull(ElevenstXmlUtils.getElementText(row, "dlvNo")),
				parseIntValue(ElevenstXmlUtils.getElementText(row, "ordQty")),
				parseBigDecimal(ElevenstXmlUtils.getElementText(row, "stlPlnAmt")),
				parseBigDecimal(ElevenstXmlUtils.getElementText(row, "selFee")),
				parseBigDecimal(ElevenstXmlUtils.getElementText(row, "tmallApplyDscAmt")),
				emptyToNull(ElevenstXmlUtils.getElementText(row, "prdNo")),
				emptyToNull(ElevenstXmlUtils.getElementText(row, "prdNm"))));
			captureTracking(row, emptyToNull(ElevenstXmlUtils.getElementText(row, "dlvNo")));
		}

		private static DetailRow mergeDetail(DetailRow a, DetailRow b) {
			return new DetailRow(
				a.sellerPrdCd() != null ? a.sellerPrdCd() : b.sellerPrdCd(),
				a.productName() != null ? a.productName() : b.productName(),
				(a.quantity() != null && a.quantity() != 0) ? a.quantity() : b.quantity(),
				isPositive(a.orderPrice()) ? a.orderPrice() : b.orderPrice(),
				isPositive(a.totalAmount()) ? a.totalAmount() : b.totalAmount(),
				b.listStatus() != null ? b.listStatus() : a.listStatus(),
				a.dlvNo() != null ? a.dlvNo() : b.dlvNo(),
				a.addPrdYn() != null ? a.addPrdYn() : b.addPrdYn(),
				a.addPrdNo() != null ? a.addPrdNo() : b.addPrdNo());
		}

		private static boolean isPositive(BigDecimal value) {
			return value != null && value.signum() != 0;
		}

		private void fillOrderCommon(Element row) {
			recipientName = firstNonNull(recipientName, text(row, "rcvrNm"));
			recipientPhone = firstNonNull(recipientPhone, text(row, "rcvrPrtblNo"));
			zipcode = firstNonNull(zipcode, text(row, "rcvrMailNo"));
			if (address == null) {
				String base = ElevenstXmlUtils.getElementText(row, "rcvrBaseAddr");
				String detail = ElevenstXmlUtils.getElementText(row, "rcvrDtlsAddr");
				address = emptyToNull(((base == null ? "" : base) + " " + (detail == null ? "" : detail)).trim());
			}
			message = firstNonNull(message, text(row, "ordDlvReqCont"));
			ordererName = firstNonNull(ordererName, text(row, "ordNm"));
			ordererPhone = firstNonNull(ordererPhone, text(row, "ordPrtblTel"));
			customsClearanceNo = firstNonNull(customsClearanceNo, text(row, "psnCscUniqNo"));
			if (orderDate == null) {
				orderDate = extractOrderDate(ordNo);
			}
		}

		private void captureTracking(Element row, String dlvNo) {
			String invcNo = emptyToNull(ElevenstXmlUtils.getElementText(row, "invcNo"));
			if (invcNo == null) {
				return;
			}
			String carrierCode = emptyToNull(ElevenstXmlUtils.getElementText(row, "dlvEtprsCd"));
			TrackingInfo info = new TrackingInfo(invcNo,
				carrierCode != null ? parseCarrierCode(carrierCode) : null);
			if (dlvNo != null) {
				trackingByDlvNo.putIfAbsent(dlvNo, info);
			} else if (trackingWithoutDlvNo == null) {
				trackingWithoutDlvNo = info;
			}
		}

		private static String text(Element row, String tag) {
			return emptyToNull(ElevenstXmlUtils.getElementText(row, tag));
		}

		private static String firstNonNull(String current, String candidate) {
			return current != null ? current : candidate;
		}

		private MarketOrderDto toNestedDto(MarketType marketType, ElevenstStatusMapper mapper) {
			List<String> roster = new ArrayList<>(statusRows.isEmpty() ? detailRows.keySet() : statusRows.keySet());
			if (roster.isEmpty()) {
				log.warn("11번가 주문 {} 의 상품주문 식별자를 얻지 못했다 — 식별자 없는 라인아이템 1건으로 처리", ordNo);
				return unidentifiedSingleLineItemDto(marketType);
			}

			Map<String, List<MarketLineItemDto>> byDlvNo = new LinkedHashMap<>();
			String representativeDlvNo = null;

			for (String seq : roster) {
				StatusRow status = statusRows.get(seq);
				DetailRow detail = detailRows.get(seq);

				String dlvNo = firstNonNull(status != null ? status.dlvNo() : null,
					detail != null ? detail.dlvNo() : null);
				if (dlvNo == null) {
					dlvNo = ordNo;
				}
				if (representativeDlvNo == null) {
					representativeDlvNo = dlvNo;
				}

				Map<String, Object> lineData = new HashMap<>();
				lineData.put("ordPrdSeq", seq);
				if (detail != null && detail.addPrdYn() != null) {
					lineData.put("addPrdYn", detail.addPrdYn());
				}
				if (detail != null && detail.addPrdNo() != null) {
					lineData.put("addPrdNo", detail.addPrdNo());
				}
				lineData.put("dlvNo", dlvNo);

				byDlvNo.computeIfAbsent(dlvNo, k -> new ArrayList<>()).add(MarketLineItemDto.builder()
					.marketLineItemNo(seq)
					.marketProductCode(detail != null ? detail.sellerPrdCd() : null)
					.sellerProductId(status != null ? status.prdNo() : null)
					.productName(firstNonNull(detail != null ? detail.productName() : null,
						status != null ? status.productName() : null))
					.quantity(resolveQuantity(status, detail))
					.orderPrice(resolveAmount(status, detail != null ? detail.orderPrice() : null))
					.totalAmount(resolveAmount(status, detail != null ? detail.totalAmount() : null))
					.settlementAmount(status != null ? status.settlementAmount() : null)
					.status(resolveStatus(status, detail, mapper))
					.claim(mapper.mapClaimByStatusName(status != null ? status.statusName() : null))
					.marketSpecificData(lineData)
					.build());
			}

			List<MarketShipmentDto> shipments = new ArrayList<>();
			for (Map.Entry<String, List<MarketLineItemDto>> entry : byDlvNo.entrySet()) {
				TrackingInfo tracking = trackingByDlvNo.get(entry.getKey());
				if (tracking == null) {
					tracking = entry.getValue().stream()
						.map(li -> trackingBySeq.get(li.getMarketLineItemNo()))
						.filter(Objects::nonNull)
						.findFirst().orElse(null);
				}
				if (tracking == null && byDlvNo.size() == 1) {
					tracking = trackingWithoutDlvNo;
				}
				shipments.add(MarketShipmentDto.builder()
					.marketShipmentNo(entry.getKey())
					.trackingNo(tracking != null ? tracking.trackingNo() : null)
					.carrier(tracking != null ? tracking.carrier() : null)
					.lineItems(entry.getValue())
					.build());
			}

			Map<String, Object> orderData = new HashMap<>();
			if (representativeDlvNo != null) {
				orderData.put("dlvNo", representativeDlvNo);
			}
			orderData.put("ordPrdSeq", roster.get(0));
			orderData.put("ordPrdSeqs", String.join("|", roster));
			DetailRow first = detailRows.get(roster.get(0));
			if (first != null && first.addPrdYn() != null) {
				orderData.put("addPrdYn", first.addPrdYn());
			}
			if (first != null && first.addPrdNo() != null) {
				orderData.put("addPrdNo", first.addPrdNo());
			}

			return MarketOrderDto.builder()
				.marketType(marketType)
				.marketOrderNo(ordNo)
				.recipientName(recipientName)
				.recipientPhone(recipientPhone)
				.zipcode(zipcode)
				.address(address)
				.message(message)
				.ordererName(ordererName)
				.ordererPhone(ordererPhone)
				.customsClearanceNo(customsClearanceNo)
				.orderDate(orderDate)
				.marketSpecificData(orderData)
				.shipments(shipments)
				.build();
		}

		private MarketOrderDto unidentifiedSingleLineItemDto(MarketType marketType) {
			String dlvNo = trackingByDlvNo.keySet().stream().findFirst().orElse(ordNo);
			TrackingInfo tracking = trackingByDlvNo.getOrDefault(dlvNo, trackingWithoutDlvNo);

			MarketLineItemDto lineItem = MarketLineItemDto.builder()
				.marketLineItemNo(null)
				.quantity(0)
				.orderPrice(BigDecimal.ZERO)
				.totalAmount(BigDecimal.ZERO)
				.status(fallbackListStatus != null ? fallbackListStatus : ShippingStatus.UNKNOWN)
				.marketSpecificData(new HashMap<>(Map.of("dlvNo", dlvNo)))
				.build();

			Map<String, Object> orderData = new HashMap<>();
			orderData.put("dlvNo", dlvNo);

			return MarketOrderDto.builder()
				.marketType(marketType)
				.marketOrderNo(ordNo)
				.recipientName(recipientName)
				.recipientPhone(recipientPhone)
				.zipcode(zipcode)
				.address(address)
				.message(message)
				.ordererName(ordererName)
				.ordererPhone(ordererPhone)
				.customsClearanceNo(customsClearanceNo)
				.orderDate(orderDate)
				.marketSpecificData(orderData)
				.shipments(List.of(MarketShipmentDto.builder()
					.marketShipmentNo(dlvNo)
					.trackingNo(tracking != null ? tracking.trackingNo() : null)
					.carrier(tracking != null ? tracking.carrier() : null)
					.lineItems(List.of(lineItem))
					.build()))
				.build();
		}

		private static BigDecimal resolveAmount(StatusRow status, BigDecimal fromDetail) {
			if (fromDetail != null && fromDetail.signum() != 0) {
				return fromDetail;
			}
			if (status == null || status.settlementAmount() == null
				|| status.settlementAmount().signum() == 0) {
				return BigDecimal.ZERO;
			}
			return status.settlementAmount()
				.add(status.sellerFee() != null ? status.sellerFee() : BigDecimal.ZERO)
				.add(status.marketDiscount() != null ? status.marketDiscount() : BigDecimal.ZERO);
		}

		private static Integer resolveQuantity(StatusRow status, DetailRow detail) {
			if (detail != null && detail.quantity() != null && detail.quantity() != 0) {
				return detail.quantity();
			}
			if (status != null && status.quantity() != null && status.quantity() != 0) {
				return status.quantity();
			}
			return 0;
		}

		private static ShippingStatus resolveStatus(StatusRow status, DetailRow detail,
			ElevenstStatusMapper mapper) {
			if (status != null) {
				ShippingStatus mapped = mapper.mapProductOrderStatus(status.statusName());
				if (mapped != null && mapped != ShippingStatus.UNKNOWN) {
					return mapped;
				}
			}
			return detail != null ? detail.listStatus() : ShippingStatus.UNKNOWN;
		}
	}
}
