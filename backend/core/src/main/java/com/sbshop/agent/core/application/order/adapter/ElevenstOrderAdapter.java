package com.sbshop.agent.core.application.order.adapter;

import com.sbshop.agent.core.application.order.port.ElevenstOrderApiPort;
import com.sbshop.agent.core.application.order.port.MarketOrderPort;
import com.sbshop.agent.core.application.order.util.ElevenstXmlUtils;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.application.order.dto.MarketLineItemDto;
import com.sbshop.agent.core.application.order.dto.MarketOrderDto;
import com.sbshop.agent.core.application.order.dto.MarketShipmentDto;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
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

	/** 11번가 문서상 orderlistall의 주문번호 상한. */
	private static final int STATUS_LOOKUP_CHUNK = 100;

	/**
	 * 2단계: 4개 목록에서 <b>상품주문 행</b>을 모으고, 상태는 {@code orderlistall}이 직접 알려주는
	 * 것을 쓴다. 결과는 (주문 / 배송 / 상품주문) 3계층이다.
	 *
	 * <p>종전에는 주문번호로만 키잉해 한 주문의 여러 상품주문이 서로 덮어썼다(D-130 — 정나영 건에서
	 * 순번2가 시스템에 아예 없었다). D-126이 도입한 "목록 신뢰 등급"은 그 증상을 덮은 것이었고,
	 * 전제(4개 목록이 서로 다른 축을 본다)가 거짓으로 확정돼 <b>제거한다</b>. 목록 행은 상품주문
	 * 단위이므로 같은 상품주문이 두 목록에 동시에 나오지 않는다 — 두 목록이 준 것은 서로 다른 상품주문이었다.
	 *
	 * <p>목록의 역할은 <b>주문 발견과 상세 정보 수집</b>으로 좁아진다. 상태 판정은 orderlistall이 한다.
	 */
	@Override
	public List<MarketOrderDto> fetchOrders(MarketCredential credential,
		LocalDate fromDate, LocalDate toDate) {
		Map<String, OrderAccumulator> orders = new LinkedHashMap<>();
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

		// 전량 실패(성공 0 · 오류≥1)면 대표 오류를 담아 전파. 부분 성공은 result 반환(경고).
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
		return result;
	}

	/** 전체 정보 목록(결제완료·배송준비중·배송완료)의 행을 상품주문 단위로 모은다. */
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

	/**
	 * 배송중 목록의 행을 모은다. 이 목록은 최소 정보(송장·배송번호·수취인)만 준다.
	 *
	 * <p>송장은 <b>배송번호(dlvNo)에</b> 붙인다 — 송장은 상품주문의 것이 아니라 배송의 것이다.
	 * 이렇게 하면 이 목록이 {@code ordPrdSeq}를 주지 않아도 정보를 잃지 않는다.
	 */
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

	/**
	 * {@code claimservice/orderlistall}로 상품주문별 상태·배송번호를 확정한다.
	 *
	 * <p>실패하거나 빈 응답이면 <b>목록 소속 상태로 폴백</b>한다. 새 API 하나가 11번가 동기화
	 * 전체를 무력화하게 두지 않는다 — 상태 판정이 덜 정확해질 뿐 주문은 사라지지 않는다.
	 */
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

	/**
	 * D-107: 수취인 이름을 어느 목록에서도 얻지 못한 주문만 단건 상세조회로 복원한다.
	 *
	 * <p>배송중 목록은 최소 정보만 주므로 그 목록에만 있는 주문은 이름이 빈다. 과거 이 경로가
	 * 미구현이라 그리드에 "-"로 남았다(사용자 신고 2026-07-25). 실패하면 조용히 지나간다 —
	 * null이면 동기화의 null-guard가 DB의 기존 값을 보존한다.
	 */
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

	@Override
	public void shipOrder(MarketCredential credential,
		Order order, OrderLineItem lineItem,
		String trackingNo, ShippingCarrier carrier) {
		String apiKey = credential.getAccessKey();
		// D-127: reqdelivery의 마지막 경로변수는 배송번호(dlvNo)다. 과거엔 주문번호(ordNo)를 넘겨
		// 라이브에서 항상 "존재하지 않는 배송번호 입니다."(code=-1)로 실패했다. 동기화가
		// marketSpecificData에 저장해 둔 dlvNo를 쓴다 — 발주확인(acceptOrders)과 같은 출처다.
		String dlvNo = resolveDeliveryNo(order);
		String dlvMthdCd = "01"; // 택배
		String dlvEtprsCd = mapCarrierCode(carrier);
		String sendDt = formatDateTime(LocalDateTime.now(), "yyyyMMddHHmm");

		// 2단계: 상품주문 식별자를 알면 부분발송으로 그 상품주문만 보낸다. 전체 발송처리는
		// 묶음배송번호가 같은 주문번호를 모두 발송 처리하므로(-3308 설명), 다품목·묶음배송
		// 주문에서 아직 준비되지 않은 상품까지 발송된 것으로 마켓에 기록될 수 있다.
		String ordPrdSeq = lineItem != null ? lineItem.getMarketLineItemNo() : null;
		if (ordPrdSeq != null && !ordPrdSeq.isBlank()) {
			elevenstOrderApiPort.shipOrderPartial(apiKey, sendDt, dlvMthdCd, dlvEtprsCd,
				trackingNo, dlvNo, order.getMarketOrderNo(), ordPrdSeq);
			return;
		}
		// 레거시 라인아이템(식별자 미채택)은 종전 경로를 쓴다 — 주문당 상품주문 1건이라 결과가 같다.
		elevenstOrderApiPort.shipOrder(apiKey, sendDt, dlvMthdCd, dlvEtprsCd, trackingNo, dlvNo);
	}

	/**
	 * D-127: 발송처리에 쓸 11번가 배송번호(dlvNo)를 marketSpecificData에서 꺼낸다.
	 *
	 * <p>주문번호로 폴백하지 않는다 — 폴백은 "존재하지 않는 배송번호" 실패를 낳을 뿐이고,
	 * 그 실패가 마켓 거부처럼 보여 원인 추적을 어렵게 만든다. 배송번호를 모르면 즉시 알린다.
	 */
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

	/**
	 * 발주확인은 <b>상품주문 단위</b>다 — 주문의 모든 {@code ordPrdSeq}를 확인해야 한다.
	 *
	 * <p>종전에는 {@code marketSpecificData}에 담긴 대표 순번 하나만 호출했다. 다품목 주문에서
	 * 순번 2가 남으면 11번가는 그 주문을 <b>결제완료 목록에 계속 둔다</b> — 이것이
	 * "API는 성공(result_code=0)인데 배송준비중 목록은 매 사이클 0건"이던 현상의 유력한 원인이다.
	 *
	 * <p>{@code ordPrdSeqs}(전체 순번 콤마)는 2단계부터 채워진다. 이미 저장된 주문에는 없으므로
	 * 대표 순번 하나로 폴백한다.
	 */
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

	/** 발주확인 대상 상품주문 순번 목록. {@code ordPrdSeqs} 우선, 없으면 대표 순번 하나. */
	private List<String> resolveProductOrderSeqs(Map<String, String> data) {
		if (data == null) {
			return List.of();
		}
		String all = data.get("ordPrdSeqs");
		if (all != null && !all.isBlank()) {
			List<String> seqs = new ArrayList<>();
			// '|'가 정본 구분자다. 콤마도 받아 준다 — 순번은 숫자라 오인될 여지가 없다.
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

	/**
	 * D-099: 주문 단건 상세조회(claimservice/orderlistalladdr)로 실제 클레임 상태를 판정한다.
	 *
	 * <p>11번가는 클레임 목록 조회 REST가 없어(라이브 확정) 4개 진행상태 목록만 조회하므로, 목록에서
	 * 사라진 주문의 실제 상태는 단건 상세조회로만 알 수 있다. 클레임이 아니면(구매확정·배송완료 등)
	 * 비어 있는 결과 — 오취소를 막는다.
	 *
	 * <p><b>2단계 정정</b>: 이 응답은 <b>상품주문마다 한 행</b>이다(2026-08-06 라이브 확인 —
	 * 정나영 건이 순번 1·2 두 행으로 왔다). 종전에는 {@code details.get(0)}만 읽어 첫 상품주문의
	 * 상태를 주문 전체에 적용했다 — D-130과 같은 키메라 오류다. 순번별로 돌려준다.
	 *
	 * @return {@code ordPrdSeq → 클레임 상태} 맵. 클레임인 행만 담긴다(정상 진행 행은 제외).
	 */
	public MissingOrderState resolveMissingOrderState(String apiKey, String ordNo) {
		try {
			List<Element> details = elevenstOrderApiPort.fetchOrderDetail(apiKey, ordNo);
			if (details == null || details.isEmpty()) {
				return MissingOrderState.empty();
			}
			Map<String, ShippingStatus> statuses = new LinkedHashMap<>();
			Map<String, String> trackingNos = new LinkedHashMap<>();
			for (Element el : details) {
				String seq = emptyToNull(ElevenstXmlUtils.getElementText(el, "ordPrdSeq"));
				String key = seq != null ? seq : CLAIM_ORDER_WIDE;
				String statNm = ElevenstXmlUtils.getElementText(el, "ordPrdStatNm");

				// D-157: 클레임뿐 아니라 정상 종결(구매확정·배송완료)도 반영한다. 종전에는 클레임만 보고
				// 나머지를 버려, 구매확정으로 목록을 벗어난 주문이 SHIPPED로 영구히 굳었다
				// (라이브 2026-08-08: 20260720086485068은 마켓에서 구매확정인데 시스템은 배송중).
				// 종결이 아닌 상태(결제완료·배송준비중 등)와 미매핑(UNKNOWN)은 담지 않는다 —
				// 목록을 벗어난 주문에 진행 상태를 되씌우면 상태가 거꾸로 갈 수 있다(D-028/D-099 규율).
				ShippingStatus status = statusMapper.mapProductOrderStatus(statNm);
				if (isTerminalStatus(status)) {
					statuses.put(key, status);
				}

				// D-158: 같은 응답이 마켓 보유 송장을 준다. "마켓이 아는 값"으로만 기록한다(우리 송장은 덮지 않음).
				String invcNo = emptyToNull(ElevenstXmlUtils.getElementText(el, "invcNo"));
				if (invcNo != null) {
					trackingNos.put(key, invcNo);
				}
			}
			return new MissingOrderState(statuses, trackingNos);
		} catch (Exception e) {
			log.warn("11번가 사라진 주문 상태 조회 실패: ordNo={}, error={}", ordNo, e.getMessage());
			return MissingOrderState.empty();
		}
	}

	/** 목록을 벗어난 주문에 반영해도 되는 종결 상태인가. 진행 상태는 되씌우지 않는다. */
	private static boolean isTerminalStatus(ShippingStatus status) {
		return status == ShippingStatus.DELIVERED
			|| status == ShippingStatus.CANCELED
			|| status == ShippingStatus.RETURNED
			|| status == ShippingStatus.EXCHANGED;
	}

	/**
	 * 진행상태 목록에서 사라진 주문을 단건 조회해 얻은 사실.
	 *
	 * @param statuses    상품주문 순번 → 종결 상태(클레임 또는 구매확정·배송완료). 종결이 아니면 담기지 않는다.
	 * @param trackingNos 상품주문 순번 → 마켓이 보유한 송장번호
	 */
	public record MissingOrderState(Map<String, ShippingStatus> statuses, Map<String, String> trackingNos) {
		public static MissingOrderState empty() {
			return new MissingOrderState(Map.of(), Map.of());
		}

		public boolean isEmpty() {
			return statuses.isEmpty() && trackingNos.isEmpty();
		}
	}

	/** 상품주문 순번을 얻지 못한 클레임 행의 키. 순번 미상 라인아이템에 적용한다. */
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

	/**
	 * 11번가 택배사 코드를 ShippingCarrier enum으로 변환
	 */
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

	/**
	 * 주문번호에서 주문일 추출 (YYYYMMDD + 9자리 시퀀스)
	 * 예: 20260612076034242 → 2026-06-12
	 */
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

			// D-066: 통관번호 파싱 (잠복경로 예방 — 향후 fetchOrderDetail 호출 시 정합).
			// 태그 부재 시 ""를 null로 정규화(SyncService null-guard로 기존 값 보호).
			String psnCscUniqNo = emptyToNull(ElevenstXmlUtils.getElementText(element, "psnCscUniqNo"));

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
				.customsClearanceNo(psnCscUniqNo)
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

	private static String formatDateTime(LocalDate date, String time) {
		return date.format(DateTimeFormatter.ofPattern("yyyyMMdd")) + time;
	}

	private static String formatDateTime(LocalDateTime dateTime, String pattern) {
		return dateTime.format(DateTimeFormatter.ofPattern(pattern));
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

	/**
	 * 한 주문번호에 대해 여러 목록·여러 API의 조각을 모아 3계층 DTO로 조립한다.
	 *
	 * <p>계층마다 <b>출처가 다르다</b>는 것이 이 클래스의 존재 이유다:
	 * <ul>
	 * <li><b>주문 공통</b>(수취인·주소·통관번호·주문자) — 아무 목록 행에서나. 빈 칸 채우기로만 병합한다.
	 * <li><b>상품주문 로스터·상태</b> — {@code orderlistall}이 권위. 없으면 목록 행으로 폴백한다.
	 * <li><b>상품·금액·판매자상품코드</b> — 전체 정보 목록에서 {@code ordPrdSeq}로 짝지어.
	 *     배송중 목록은 이 값들을 주지 않는다.
	 * <li><b>송장·택배사</b> — <b>배송번호(dlvNo)에</b> 붙인다. 송장은 상품주문의 것이 아니라 배송의 것이다.
	 * </ul>
	 */
	private static final class OrderAccumulator {

		private final String ordNo;

		// 주문 공통 — 빈 칸만 채운다.
		private String recipientName;
		private String recipientPhone;
		private String zipcode;
		private String address;
		private String message;
		private String ordererName;
		private String ordererPhone;
		private String customsClearanceNo;
		private LocalDateTime orderDate;

		/** ordPrdSeq → 전체 정보 목록이 준 상품주문 조각. */
		private final Map<String, DetailRow> detailRows = new LinkedHashMap<>();
		/** ordPrdSeq → orderlistall이 준 상태·배송번호. 권위 있는 로스터. */
		private final Map<String, StatusRow> statusRows = new LinkedHashMap<>();
		/** dlvNo → 송장·택배사. */
		private final Map<String, TrackingInfo> trackingByDlvNo = new LinkedHashMap<>();
		/** ordPrdSeq → 송장. 배송번호를 주지 않는 배송중 목록용. */
		private final Map<String, TrackingInfo> trackingBySeq = new LinkedHashMap<>();
		/** 배송번호를 알 수 없는 행이 준 송장 — 배송이 하나뿐일 때만 쓴다. */
		private TrackingInfo trackingWithoutDlvNo;
		/**
		 * 상품주문 식별자를 하나도 못 얻었을 때 쓸 상태. 배송중 목록처럼 {@code ordPrdSeq}를 주지
		 * 않는 목록에만 나타나는 주문이 있고, 그런 주문을 드롭하면 데이터가 조용히 사라진다.
		 */
		private ShippingStatus fallbackListStatus;

		private OrderAccumulator(String ordNo) {
			this.ordNo = ordNo;
		}

		private record DetailRow(String sellerPrdCd, String productName, Integer quantity,
			BigDecimal orderPrice, BigDecimal totalAmount, ShippingStatus listStatus,
			String dlvNo, String addPrdYn, String addPrdNo) {}

		/**
		 * orderlistall이 주는 상품주문별 사실. 2026-08-06 라이브 응답으로 확인한 필드다:
		 * {@code stlPlnAmt}(정산예정금액) · {@code selFee}(판매수수료) · {@code tmallApplyDscAmt}(11번가 할인분담).
		 * <b>{@code sellerPrdCd}는 주지 않는다</b> — 상품 매핑은 전체 정보 목록에서만 얻을 수 있다.
		 */
		private record StatusRow(String statusName, String dlvNo, Integer quantity,
			BigDecimal settlementAmount, BigDecimal sellerFee, BigDecimal marketDiscount) {}

		private record TrackingInfo(String trackingNo, ShippingCarrier carrier) {}

		/** 전체 정보 목록(결제완료·배송준비중·배송완료) 행. */
		private void addDetailRow(Element row, String source, ElevenstStatusMapper mapper) {
			fillOrderCommon(row);

			String seq = emptyToNull(ElevenstXmlUtils.getElementText(row, "ordPrdSeq"));
			String dlvNo = emptyToNull(ElevenstXmlUtils.getElementText(row, "dlvNo"));
			captureTracking(row, dlvNo);

			ShippingStatus listStatus = mapper.mapStatus(Map.of("source", source));
			if (seq == null) {
				// 상품주문 식별자가 없으면 로스터에 담을 수 없다. 주문 공통 정보·송장은 이미 챘고,
				// 상태는 폴백으로 남겨 이 주문이 드롭되지 않게 한다.
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

		/**
		 * 배송중 목록 행 — 최소 정보. 송장을 배송번호에 붙이고 수취인 빈 칸을 채운다.
		 * 상태는 여기서 정하지 않는다(목록 소속으로 상태를 추론하는 것이 D-126의 원인이었다).
		 */
		private void addShippingRow(Element row, ElevenstStatusMapper mapper) {
			fillOrderCommon(row);
			String dlvNo = emptyToNull(ElevenstXmlUtils.getElementText(row, "dlvNo"));
			captureTracking(row, dlvNo);
			// 2026-08-06 라이브 확인: 이 목록은 최소 정보(ordNo·ordPrdSeq·invcNo·dlvEtprsCd·sndEndDt)지만
			// ordPrdSeq는 준다. 종전 구현은 "안 준다"고 전제해 송장을 상품주문에 붙일 길을 스스로 막았다.
			// 배송번호는 주지 않으므로, 상품주문 단위로 기록해 두고 조립 때 그 순번의 배송에 붙인다.
			if (dlvNo == null) {
				String seq = emptyToNull(ElevenstXmlUtils.getElementText(row, "ordPrdSeq"));
				String invcNo = emptyToNull(ElevenstXmlUtils.getElementText(row, "invcNo"));
				if (seq != null && invcNo != null) {
					trackingBySeq.putIfAbsent(seq, new TrackingInfo(invcNo,
						parseCarrierCode(ElevenstXmlUtils.getElementText(row, "dlvEtprsCd"))));
				}
			}
			// 상품주문 식별자를 끝까지 못 얻는 경우에만 쓰이는 폴백이다. 주 경로는 orderlistall이다.
			if (fallbackListStatus == null) {
				fallbackListStatus = mapper.mapStatus(Map.of("source", "shipping"));
			}
		}

		/** orderlistall 행 — 상품주문별 상태·배송번호. */
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
				parseBigDecimal(ElevenstXmlUtils.getElementText(row, "tmallApplyDscAmt"))));
			// 이 행도 송장을 준다. 배송번호가 함께 오므로 배송에 정확히 붙는다.
			captureTracking(row, emptyToNull(ElevenstXmlUtils.getElementText(row, "dlvNo")));
		}

		/** 두 목록이 같은 상품주문을 줬을 때 — 빈 칸만 채운다. 상태는 나중 값을 채택한다. */
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

		/**
		 * D-107/D-119: 태그 부재 시 {@code ""}가 실값을 덮지 않도록 null로 정규화하고, 빈 칸만 채운다.
		 * 과거 배송중 경로가 이름·주소를 {@code ""}로 내보내 그리드에 "-"로 남은 이력이 있다.
		 */
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

		/** 송장은 배송의 것이다 — 배송번호를 알면 그 배송에, 모르면 따로 둔다. */
		private void captureTracking(Element row, String dlvNo) {
			String invcNo = emptyToNull(ElevenstXmlUtils.getElementText(row, "invcNo"));
			if (invcNo == null) {
				return;
			}
			// 택배사 코드가 없으면 위조하지 않는다. parseCarrierCode는 null을 CJ로 기본값 처리하는데,
			// orderlistall은 dlvEtprsCd를 주지 않으므로 그대로 두면 전 주문이 CJ로 찍힌다(D-131과 같은 부류).
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

		/**
		 * 3계층 DTO로 조립한다.
		 *
		 * <p><b>라인아이템 레벨 평면 필드는 채우지 않는다.</b> 거기에 "첫 상품주문"을 담으면 종전의
		 * 키메라 행(순번1의 상품 + 순번2의 송장)이 그대로 되살아난다. 주문 공통 필드는 계속 채운다.
		 */
		private MarketOrderDto toNestedDto(MarketType marketType, ElevenstStatusMapper mapper) {
			// 로스터는 orderlistall이 권위. 비어 있으면(조회 실패·빈 응답) 목록 행으로 폴백한다.
			List<String> roster = new ArrayList<>(statusRows.isEmpty() ? detailRows.keySet() : statusRows.keySet());
			if (roster.isEmpty()) {
				// 상품주문 식별자를 끝까지 못 얻었다(배송중 목록에만 있고 orderlistall도 답이 없는 주문).
				// 드롭하면 주문이 조용히 사라진다 — 식별자 없는 라인아이템 1건으로 낸다. 키를 위조하지
				// 않으므로(D-131) 매칭은 카디널리티로 이뤄지고(D-132), 기존 동작과 같아진다.
				log.warn("11번가 주문 {} 의 상품주문 식별자를 얻지 못했다 — 식별자 없는 라인아이템 1건으로 처리", ordNo);
				return unidentifiedSingleLineItemDto(marketType);
			}

			// 배송번호로 묶는다 — 같은 dlvNo는 물리적으로 같은 택배 한 상자다(-3308 문서).
			Map<String, List<MarketLineItemDto>> byDlvNo = new LinkedHashMap<>();
			String representativeDlvNo = null;

			for (String seq : roster) {
				StatusRow status = statusRows.get(seq);
				DetailRow detail = detailRows.get(seq);

				String dlvNo = firstNonNull(status != null ? status.dlvNo() : null,
					detail != null ? detail.dlvNo() : null);
				if (dlvNo == null) {
					// 설계 3.3: 배송 식별자를 못 얻으면 주문번호로 대체한다. 배송 없는 주문은 만들지 않는다.
					dlvNo = ordNo;
				}
				if (representativeDlvNo == null) {
					representativeDlvNo = dlvNo;
				}

				Map<String, Object> lineData = new java.util.HashMap<>();
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
					.productName(detail != null ? detail.productName() : null)
					.quantity(resolveQuantity(status, detail))
					.orderPrice(resolveAmount(status, detail != null ? detail.orderPrice() : null))
					.totalAmount(resolveAmount(status, detail != null ? detail.totalAmount() : null))
					.settlementAmount(status != null ? status.settlementAmount() : null)
					.status(resolveStatus(status, detail, mapper))
					.marketSpecificData(lineData)
					.build());
			}

			List<MarketShipmentDto> shipments = new ArrayList<>();
			for (Map.Entry<String, List<MarketLineItemDto>> entry : byDlvNo.entrySet()) {
				TrackingInfo tracking = trackingByDlvNo.get(entry.getKey());
				if (tracking == null) {
					// 배송중 목록은 배송번호를 주지 않고 ordPrdSeq만 준다. 이 배송에 속한 상품주문 중
					// 하나라도 송장을 알려줬으면 그것이 이 배송의 송장이다(한 배송 = 한 송장).
					tracking = entry.getValue().stream()
						.map(li -> trackingBySeq.get(li.getMarketLineItemNo()))
						.filter(java.util.Objects::nonNull)
						.findFirst().orElse(null);
				}
				if (tracking == null && byDlvNo.size() == 1) {
					// 배송이 하나뿐이면 배송번호를 못 밝힌 행의 송장도 이 배송의 것이 확실하다.
					tracking = trackingWithoutDlvNo;
				}
				shipments.add(MarketShipmentDto.builder()
					.marketShipmentNo(entry.getKey())
					.trackingNo(tracking != null ? tracking.trackingNo() : null)
					.carrier(tracking != null ? tracking.carrier() : null)
					.lineItems(entry.getValue())
					.build());
			}

			// 주문 계층 마켓 데이터 — 발주확인·발송처리가 읽는다. ordPrdSeqs는 다품목 발주확인용(전체 순번).
			Map<String, Object> orderData = new java.util.HashMap<>();
			if (representativeDlvNo != null) {
				orderData.put("dlvNo", representativeDlvNo);
			}
			orderData.put("ordPrdSeq", roster.get(0));
			// 구분자가 콤마가 아닌 이유: Order.marketSpecificData는 자체 구현 유사 JSON이고
			// 읽을 때 ','로 split한다 — 값에 콤마가 들어가면 그 값이 조용히 잘린다(백로그 등재).
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

		/**
		 * 상품주문 식별자를 못 얻은 주문을 배송 1 : 상품주문 1로 낸다.
		 * 종전(주문번호로만 키잉)과 같은 형태이므로 기존 주문에 그대로 반영된다.
		 */
		private MarketOrderDto unidentifiedSingleLineItemDto(MarketType marketType) {
			String dlvNo = trackingByDlvNo.keySet().stream().findFirst().orElse(ordNo);
			TrackingInfo tracking = trackingByDlvNo.getOrDefault(dlvNo, trackingWithoutDlvNo);

			MarketLineItemDto lineItem = MarketLineItemDto.builder()
				.marketLineItemNo(null)
				.quantity(0)
				.orderPrice(BigDecimal.ZERO)
				.totalAmount(BigDecimal.ZERO)
				.status(fallbackListStatus != null ? fallbackListStatus : ShippingStatus.UNKNOWN)
				.marketSpecificData(new java.util.HashMap<>(Map.of("dlvNo", dlvNo)))
				.build();

			Map<String, Object> orderData = new java.util.HashMap<>();
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

		/**
		 * 주문금액을 정한다. 전체 정보 목록이 준 값이 있으면 그것을 쓰고, 없으면 orderlistall의
		 * 실측값으로 <b>계산</b>한다: {@code 정산예정금액 + 판매수수료 + 11번가 할인분담}.
		 *
		 * <p>추측이 아니라 산술이다. 2026-08-06 라이브 응답으로 두 상품주문 모두 검증했다 —
		 * 49887+6253+1560 = 57700, 45648+5712+1440 = 52800 (판매자센터 표시 금액과 일치).
		 * 배송중 목록에만 있는 주문(전체 정보 목록의 날짜 창을 지난 주문)은 이 경로가 유일하다.
		 */
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

		/**
		 * 상태는 {@code ordPrdStatNm}이 권위다. 없거나 매핑 불가면 목록 소속으로 폴백한다 —
		 * 새 API 하나가 동기화 전체를 무력화하게 두지 않는다.
		 */
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
