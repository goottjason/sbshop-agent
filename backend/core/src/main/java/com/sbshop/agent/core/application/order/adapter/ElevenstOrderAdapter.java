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

	/**
	 * D-126: 11번가 목록별 <b>상태 신뢰 등급</b>. 값이 클수록 상태 판정에서 우선한다.
	 *
	 * <p>네 목록은 상호배타적이지 않다(2026-08-05 라이브 확증). 배송중 목록은 <b>송장이 등록된
	 * 주문</b>을 돌려주며 진행상태가 결제완료여도 포함되므로, 그 목록에 있다는 사실만으로는
	 * "배송중"의 근거가 되지 못한다. 반면 결제완료·배송준비중 목록은 11번가의 주문 진행상태를
	 * 직접 뜻하므로 확정적이다. 따라서 진행상태 축이 배송 축을 이긴다.
	 */
	private static final int RANK_SHIPPING = 1;   // 배송중 — 송장 보유 사실만 뜻함
	private static final int RANK_DELIVERED = 2;  // 배송완료 — 배송 축 안에서는 더 진행됨
	private static final int RANK_PROGRESS = 3;   // 결제완료·배송준비중 — 진행상태 확정

	@Override
	public List<MarketOrderDto> fetchOrders(MarketCredential credential,
		LocalDate fromDate, LocalDate toDate) {
		// D-126: 주문번호를 키로 병합한다. 같은 주문이 여러 목록·여러 주간 chunk에 나타나도
		// 한 건으로 모으고, 상태는 등급이 높은 목록의 것을 채택한다. 과거엔 단순 concat이라
		// "목록 순서상 마지막"이 이겨 결제완료 주문이 배송중으로 뒤집혔다.
		Map<String, MarketOrderDto> merged = new LinkedHashMap<>();
		Map<String, Integer> ranks = new HashMap<>();

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
						mergeInto(merged, ranks, dto, RANK_PROGRESS);
					}
				}
				Thread.sleep(500);

				// 2. 배송준비중 주문 조회 (발주확인 - 전체 데이터 포함)
				List<Element> packagingOrders = elevenstOrderApiPort.fetchPackagingOrders(apiKey, startTime, endTime);
				for (Element orderElement : packagingOrders) {
					MarketOrderDto dto = parseOrderElement(orderElement, "packaging");
					if (dto != null) {
						mergeInto(merged, ranks, dto, RANK_PROGRESS);
					}
				}
				Thread.sleep(500);

				// 3. 배송중 주문 조회 (최소 정보 - 개별 조회로 폴백)
				List<Element> shippingOrders = elevenstOrderApiPort.fetchShippingOrders(apiKey, startTime, endTime);
				for (Element orderElement : shippingOrders) {
					MarketOrderDto dto = parseShippingElement(orderElement);
					if (dto != null) {
						// D-107: 배송중 목록은 수취인 이름을 주지 않는다(최소 정보). 이름이 비면 단건 상세조회
						// (claimservice/orderlistalladdr, rcvrNm 포함)로 수취인 정보를 복원한다. 원 설계 주석의
						// "개별 조회로 폴백"이 미구현이었던 부분 — 이름이 ""로 유실돼 그리드에 "-"로 남던 결함 해소.
						if (dto.getRecipientName() == null) {
							enrichRecipientFromDetail(apiKey, dto);
							Thread.sleep(300);
						}
						mergeInto(merged, ranks, dto, RANK_SHIPPING);
					}
				}
				Thread.sleep(500);

				// 4. 배송완료 주문 조회 (전체 데이터 포함 - 신규 주문 생성 가능)
				List<Element> dlvCompletedOrders = elevenstOrderApiPort.fetchCompletedDeliveryOrders(apiKey, startTime,
					endTime);
				for (Element orderElement : dlvCompletedOrders) {
					MarketOrderDto dto = parseOrderElement(orderElement, "dlvcompleted");
					if (dto != null) {
						mergeInto(merged, ranks, dto, RANK_DELIVERED);
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

		return new ArrayList<>(merged.values());
	}

	/**
	 * D-126: 주문번호 기준으로 DTO를 병합한다.
	 *
	 * <p>상태는 등급이 높은 목록의 것을 채택하고, 나머지 필드는 <b>비어 있는 쪽을 채우는</b>
	 * 방향으로만 합친다. 배송중 목록은 최소 정보(송장·수취인)만 주고 결제완료/배송완료 목록은
	 * 전체 정보를 주므로, 어느 쪽이 이기든 상대가 가진 값을 잃지 않는다. 특히 결제완료가
	 * 이길 때도 배송중 목록이 준 송장은 보존된다 — "결제완료인데 송장 있음"이 실제 상태다.
	 */
	private void mergeInto(Map<String, MarketOrderDto> merged, Map<String, Integer> ranks,
		MarketOrderDto dto, int rank) {
		String key = dto.getMarketOrderNo();
		MarketOrderDto existing = merged.get(key);
		if (existing == null) {
			merged.put(key, dto);
			ranks.put(key, rank);
			return;
		}

		int existingRank = ranks.getOrDefault(key, 0);
		if (rank > existingRank) {
			// 새 DTO가 상태 판정의 주인이 된다. 기존이 가진 값으로 빈 칸만 메운다.
			fillBlanks(dto, existing);
			merged.put(key, dto);
			ranks.put(key, rank);
			log.debug("11번가 주문 {} 상태 재판정: 등급 {} → {} (status={})",
				key, existingRank, rank, dto.getStatus());
		} else {
			// 기존이 상태의 주인. 새 DTO는 빈 칸을 메우는 데만 쓴다(상태는 덮지 않는다).
			fillBlanks(existing, dto);
		}
	}

	/** {@code target}의 비어 있는 필드만 {@code source}의 값으로 채운다. 상태(status)는 건드리지 않는다. */
	private void fillBlanks(MarketOrderDto target, MarketOrderDto source) {
		if (isBlank(target.getTrackingNo()) && !isBlank(source.getTrackingNo())) {
			target.setTrackingNo(source.getTrackingNo());
			target.setCarrier(source.getCarrier());
		}
		if (isBlank(target.getMarketProductCode())) {
			target.setMarketProductCode(source.getMarketProductCode());
		}
		if (isBlank(target.getProductName())) {
			target.setProductName(source.getProductName());
		}
		if (isBlank(target.getRecipientName())) {
			target.setRecipientName(source.getRecipientName());
		}
		if (isBlank(target.getRecipientPhone())) {
			target.setRecipientPhone(source.getRecipientPhone());
		}
		if (isBlank(target.getZipcode())) {
			target.setZipcode(source.getZipcode());
		}
		if (isBlank(target.getAddress())) {
			target.setAddress(source.getAddress());
		}
		if (isBlank(target.getMessage())) {
			target.setMessage(source.getMessage());
		}
		if (isBlank(target.getOrdererName())) {
			target.setOrdererName(source.getOrdererName());
		}
		if (isBlank(target.getOrdererPhone())) {
			target.setOrdererPhone(source.getOrdererPhone());
		}
		if (isBlank(target.getCustomsClearanceNo())) {
			target.setCustomsClearanceNo(source.getCustomsClearanceNo());
		}
		// 배송중 목록은 수량·금액을 주지 않아 0/ZERO로 남는다 — 실값이 있는 쪽을 취한다.
		if (isEmptyQuantity(target.getQuantity()) && !isEmptyQuantity(source.getQuantity())) {
			target.setQuantity(source.getQuantity());
		}
		if (isZero(target.getOrderPrice()) && !isZero(source.getOrderPrice())) {
			target.setOrderPrice(source.getOrderPrice());
		}
		if (isZero(target.getTotalAmount()) && !isZero(source.getTotalAmount())) {
			target.setTotalAmount(source.getTotalAmount());
		}
		// 발주확인·발송처리에 필요한 ordPrdSeq/dlvNo는 결제완료·배송완료 목록에만 있다.
		if ((target.getMarketSpecificData() == null || target.getMarketSpecificData().isEmpty())
			&& source.getMarketSpecificData() != null) {
			target.setMarketSpecificData(source.getMarketSpecificData());
		}
	}

	private boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	private boolean isEmptyQuantity(Integer quantity) {
		return quantity == null || quantity == 0;
	}

	private boolean isZero(BigDecimal value) {
		return value == null || value.signum() == 0;
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

	/**
	 * D-099: 주문 단건 상세조회(claimservice/orderlistalladdr)로 실제 클레임 상태를 판정한다.
	 *
	 * <p>11번가는 클레임 목록 조회 REST가 없어(라이브 확정) 진행상태 목록에서 사라진 주문을 detectCancellations가
	 * 무조건 CANCELED로 뭉뚱그렸다. 상세 응답의 {@code ordPrdStatNm}(취소완료/반품완료/교환완료 등)을 읽어
	 * 취소·반품·교환을 구분한다. 클레임이 아니면(구매확정·배송완료 등) {@code null} — 오취소를 막는다.
	 *
	 * @return 클레임 상태(CANCELED/RETURNED/EXCHANGED) 또는 클레임 아님/조회실패 시 {@code null}
	 */
	public ShippingStatus resolveClaimStatus(String apiKey, String ordNo) {
		try {
			List<Element> details = elevenstOrderApiPort.fetchOrderDetail(apiKey, ordNo);
			if (details == null || details.isEmpty()) {
				return null;
			}
			Element el = details.get(0);
			String ordPrdStat = ElevenstXmlUtils.getElementText(el, "ordPrdStat");
			String ordPrdStatNm = ElevenstXmlUtils.getElementText(el, "ordPrdStatNm");
			return statusMapper.mapClaimStatus(ordPrdStat, ordPrdStatNm);
		} catch (Exception e) {
			log.warn("11번가 클레임 상태 조회 실패: ordNo={}, error={}", ordNo, e.getMessage());
			return null;
		}
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
			// D-066: 배송중 경로에도 통관번호 파싱 추가.
			// getElementText는 태그 부재 시 ""를 반환하므로 null로 정규화 — SyncService null-guard가
			// 빈 값으로 기존 통관번호를 덮어쓰지 않게 한다(기존 주문 회귀 방지).
			String psnCscUniqNo = emptyToNull(ElevenstXmlUtils.getElementText(element, "psnCscUniqNo"));

			if (ordNo == null || ordNo.isEmpty()) {
				return null;
			}

			// D-107: 배송중 목록도 수취인/구매자 정보를 담고 있으므로 파싱해 복원한다. 과거엔 이 경로가
			// 이름·주소를 ""로 하드코딩해 내보냈고, Order.update가 recipientName을 !=null 로만 보호해
			// ""가 기존 실이름을 덮어써 그리드에 "-"로 표시됐다(사용자 신고). 태그 부재 시 ""를 null로
			// 정규화 — null-guard가 기존 값을 보존하도록 한다(통관번호와 동일 정책).
			String rcvrNm = emptyToNull(ElevenstXmlUtils.getElementText(element, "rcvrNm"));
			String rcvrPrtblNo = emptyToNull(ElevenstXmlUtils.getElementText(element, "rcvrPrtblNo"));
			String rcvrMailNo = emptyToNull(ElevenstXmlUtils.getElementText(element, "rcvrMailNo"));
			String rcvrBaseAddr = ElevenstXmlUtils.getElementText(element, "rcvrBaseAddr");
			String rcvrDtlsAddr = ElevenstXmlUtils.getElementText(element, "rcvrDtlsAddr");
			String address = emptyToNull((rcvrBaseAddr + " " + rcvrDtlsAddr).trim());
			String ordDlvReqCont = emptyToNull(ElevenstXmlUtils.getElementText(element, "ordDlvReqCont"));
			String ordNm = emptyToNull(ElevenstXmlUtils.getElementText(element, "ordNm"));
			String ordPrtblTel = emptyToNull(ElevenstXmlUtils.getElementText(element, "ordPrtblTel"));

			ShippingStatus status = ShippingStatus.SHIPPED;
			ShippingCarrier carrier = parseCarrierCode(dlvEtprsCd);

			return MarketOrderDto.builder()
				.marketType(getMarketType())
				.marketOrderNo(ordNo)
				.marketProductCode(null)
				.productName(null)
				.quantity(0)
				.orderPrice(BigDecimal.ZERO)
				.totalAmount(BigDecimal.ZERO)
				.recipientName(rcvrNm)
				.recipientPhone(rcvrPrtblNo)
				.zipcode(rcvrMailNo)
				.address(address)
				.message(ordDlvReqCont)
				.ordererName(ordNm)
				.ordererPhone(ordPrtblTel)
				.customsClearanceNo(psnCscUniqNo)
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

	/**
	 * D-107: 배송중 목록이 수취인 이름을 주지 않을 때 단건 상세조회로 수취인/구매자 정보를 복원한다.
	 * 배송 상태·송장은 배송중 값을 유지하고, 비어 있는 수취인 필드만 상세조회 값으로 채운다.
	 * 상세조회 실패·값 부재 시 조용히 지나가 기존 값(null)을 유지한다(SyncService null-guard가 DB 보존).
	 */
	private void enrichRecipientFromDetail(String apiKey, MarketOrderDto dto) {
		try {
			List<Element> details = elevenstOrderApiPort.fetchOrderDetail(apiKey, dto.getMarketOrderNo());
			if (details == null || details.isEmpty()) {
				return;
			}
			MarketOrderDto detail = parseOrderDetailElement(details.get(0));
			if (detail == null) {
				return;
			}
			if (dto.getRecipientName() == null) {
				dto.setRecipientName(blankToNull(detail.getRecipientName()));
			}
			if (dto.getRecipientPhone() == null) {
				dto.setRecipientPhone(blankToNull(detail.getRecipientPhone()));
			}
			if (dto.getZipcode() == null) {
				dto.setZipcode(blankToNull(detail.getZipcode()));
			}
			if (dto.getAddress() == null) {
				dto.setAddress(blankToNull(detail.getAddress()));
			}
			if (dto.getOrdererName() == null) {
				dto.setOrdererName(blankToNull(detail.getOrdererName()));
			}
			if (dto.getOrdererPhone() == null) {
				dto.setOrdererPhone(blankToNull(detail.getOrdererPhone()));
			}
		} catch (Exception e) {
			log.warn("11번가 배송중 수취인 상세 복원 실패: ordNo={}, error={}", dto.getMarketOrderNo(), e.getMessage());
		}
	}

	private String blankToNull(String value) {
		return (value == null || value.isBlank()) ? null : value;
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

	private String emptyToNull(String value) {
		return (value == null || value.isEmpty()) ? null : value;
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
