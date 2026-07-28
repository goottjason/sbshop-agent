package com.sbshop.agent.worker.service;

import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.order.service.MarketShippingResult;
import com.sbshop.agent.core.application.order.service.MarketplaceShippingService;
import com.sbshop.agent.core.domain.actionlog.ActionLogConstants;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.order.vo.SourcingData;
import com.sbshop.agent.core.domain.order.vo.ShippingData;
import com.sbshop.agent.core.config.EmailAccountProperties;
import jakarta.mail.*;
import jakarta.mail.search.SubjectTerm;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailFetcherService {

	private final EmailAccountProperties properties;
	private final OrderEmailParser parser;
	private final OrderLineItemRepository orderLineItemRepository;
	private final OrderRepository orderRepository;
	private final MarketplaceShippingService marketplaceShippingService;
	private final ActionLogService actionLogService;

	// F-MISC-18: 재진입/동시실행 가드.
	// EmailFetchController(수동 /internal/email/fetch)와 OrderSyncScheduler(cron 0/30)가
	// 같은 worker JVM에서 fetchAndProcessEmails()를 동시에 호출하면 같은 발송메일을 이중 처리 →
	// 마켓에 중복 송장 전송되는 창이 있다(PURCHASED 아이템을 두 실행이 동시에 읽어 각각 shipOrder).
	// 인-JVM 플래그로 이미 실행 중이면 두 번째 호출은 본처리를 스킵하고 즉시 반환한다.
	// (cross-JVM 아님 — 컨트롤러·스케줄러 모두 worker JVM이라 인-JVM 가드로 충분.)
	// AtomicBoolean은 서로 다른 스레드 간 겹침은 물론 같은 스레드의 재진입도 막는다
	// (ReentrantLock은 동일 스레드 재진입을 허용하므로 부적합).
	private final AtomicBoolean fetching = new AtomicBoolean(false);

	/**
	 * 이메일 수집·처리를 1회 실행한다.
	 *
	 * @return 본처리를 실제로 수행했으면 true, 재진입 가드(F-MISC-18)로 이미 실행 중이라
	 *         이번 호출이 스킵됐으면 false. 내부 트리거(/internal/email/fetch)가 실제 실행
	 *         여부를 응답에 반영할 수 있도록 표면화한다(F-MISC-20). 계정 미설정·처리대상 없음은
	 *         "정상 실행(처리할 것이 없었을 뿐)"이므로 true 로 간주한다.
	 */
	// 트랜잭션 없음(2026-07-24): 이 메서드는 계정별 IMAP 접속(각 최대 30s)과 마켓 API 호출 등
	// 느린 네트워크 I/O를 다수 수행한다. 전체를 @Transactional로 감싸면 그 긴 시간 동안 DB 커넥션을
	// 붙잡아 풀이 커넥션을 닫고 "Unable to rollback against JDBC Connection"으로 실패한다.
	// DB 읽기는 각 리포지토리 호출이, 쓰기는 각 orderLineItemRepository.save()가 자체 트랜잭션으로
	// 원자적이므로 바깥 트랜잭션은 불필요하다.
	public boolean fetchAndProcessEmails() {
		// 재진입/동시실행 가드: 이미 실행 중이면 본처리를 스킵하고 즉시 반환(실행 안 함 → false).
		if (!fetching.compareAndSet(false, true)) {
			log.info("이메일 수집·처리가 이미 실행 중입니다 - 이번 호출은 스킵(중복 실행 방지, F-MISC-18)");
			return false;
		}
		try {
			if (properties.getAccounts() == null || properties.getAccounts().isEmpty()) {
				log.warn("IMAP 이메일 계정이 설정되지 않았습니다.");
				return true;
			}

			// 1. DB에서 이메일 처리가 필요한 iHerb 주문번호 조회.
			//    두 큐는 생애주기가 다르다:
			//    - 배송 큐: 발송메일에서 송장을 받아야 하는 건(PREPARING·미동기 DISPATCHED/SHIPPED)
			//    - 실구매가 큐: 확인메일에서 금액을 받아야 하는 건(배송상태 무관)
			//    과거에는 배송 큐만 스캔해, 배송이 끝난 주문은 실구매가를 영영 못 받았다(실측 164/194 미주입).
			List<OrderLineItem> shipmentItems = orderLineItemRepository.findIherbItemsNeedingEmailProcessing();
			List<OrderLineItem> amountItems = orderLineItemRepository.findIherbItemsNeedingPurchaseAmount();
			if (shipmentItems.isEmpty() && amountItems.isEmpty()) {
				log.debug("이메일 처리가 필요한 iHerb 주문이 없습니다.");
				return true;
			}

			// 2. 라우팅: 주문의 소싱 계정(sourcing_account)으로 검색할 메일함을 좁힌다.
			//    - Gmail 소싱분은 중앙 계정으로 자동전달되므로 중앙(Gmail) 메일함만
			//    - 비-Gmail 소싱분은 해당 제공자 메일함만 (미매칭·미상은 전 계정 폴백)
			Map<EmailAccountProperties.Account, Set<String>> plan = buildSearchPlan(
				shipmentItems, amountItems, properties.getAccounts());
			log.info("이메일 검색 계획: 계정 {}곳, 주문 {}건", plan.size(),
				plan.values().stream().mapToInt(Set::size).sum());

			// 3. 계정별 1회 접속하여 각 주문번호를 서버측 SEARCH로 조회·처리
			for (Map.Entry<EmailAccountProperties.Account, Set<String>> entry : plan.entrySet()) {
				searchAccountForOrderNos(entry.getKey(), entry.getValue());
			}
			return true;
		} finally {
			fetching.set(false);
		}
	}

	/**
	 * 처리 대상 라인아이템을 "검색할 메일함(계정) → 주문번호 집합"으로 라우팅한다.
	 * 주문의 소싱 계정({@code sourcing_account})으로 대상 메일함을 좁혀, 전 계정 스캔을 피한다.
	 *
	 * <p>배송 큐와 실구매가 큐를 합쳐 계정별 주문번호 집합으로 정리한다. 한 주문이 두 큐에 모두 있어도
	 * IMAP SEARCH는 1회다(Set 중복 제거) — 매칭된 메일의 제목으로 발송/확인 처리를 분기하므로,
	 * 어느 큐에서 왔든 같은 검색 1회로 양쪽이 처리된다.
	 */
	// 테스트 접근을 위해 package-private
	Map<EmailAccountProperties.Account, Set<String>> buildSearchPlan(
		List<OrderLineItem> shipmentItems, List<OrderLineItem> amountItems,
		List<EmailAccountProperties.Account> accounts) {
		Map<EmailAccountProperties.Account, Set<String>> plan = new LinkedHashMap<>();
		for (List<OrderLineItem> items : List.of(shipmentItems, amountItems)) {
			for (OrderLineItem item : items) {
				addToSearchPlan(plan, item, accounts);
			}
		}
		return plan;
	}

	private void addToSearchPlan(Map<EmailAccountProperties.Account, Set<String>> plan,
		OrderLineItem item, List<EmailAccountProperties.Account> accounts) {
		var sourcingData = item.getSourcingData();
		String orderNo = sourcingData != null ? sourcingData.getSourcingOrderNo() : null;
		if (orderNo == null || orderNo.isBlank()) {
			return;
		}
		// 사용자 편집 필드라 비ASCII 값(예: 한글 "재고")이 들어올 수 있다. 그대로 IMAP SEARCH에
		// 넣으면 charset 미지정으로 서버가 파싱 실패(BAD)를 던져 검색 루프가 끊긴다 → 제외.
		if (!isImapSearchable(orderNo)) {
			log.warn("이메일 검색 제외 — 비ASCII 구매주문번호(itemId={}, orderNo='{}')", item.getId(), orderNo);
			return;
		}
		String sourcingAccount = sourcingData.getSourcingAccount();
		for (EmailAccountProperties.Account target : resolveTargetAccounts(sourcingAccount, accounts)) {
			plan.computeIfAbsent(target, k -> new LinkedHashSet<>()).add(orderNo);
		}
	}

	/**
	 * 소싱 계정 이메일로 검색 대상 메일함(들)을 결정한다.
	 * <ul>
	 *   <li>Gmail 소싱분 → 중앙 계정으로 자동전달되므로 설정된 Gmail(중앙) 계정</li>
	 *   <li>비-Gmail 소싱분 → username이 정확히 일치하는 계정(그 제공자 메일함)</li>
	 *   <li>소싱 계정 미상·미설정 → 설정된 전 계정(폴백, 기존 동작 보존)</li>
	 * </ul>
	 * 빈 username 계정(D-E4)은 제외한다.
	 */
	static List<EmailAccountProperties.Account> resolveTargetAccounts(
		String sourcingAccount, List<EmailAccountProperties.Account> accounts) {
		List<EmailAccountProperties.Account> usable = new ArrayList<>();
		if (accounts != null) {
			for (EmailAccountProperties.Account a : accounts) {
				if (a.getUsername() != null && !a.getUsername().isBlank()) {
					usable.add(a);
				}
			}
		}
		if (sourcingAccount == null || sourcingAccount.isBlank()) {
			return usable;
		}
		String host = EmailAccountProperties.imapHostForEmail(sourcingAccount);
		if ("imap.gmail.com".equals(host)) {
			List<EmailAccountProperties.Account> gmail = new ArrayList<>();
			for (EmailAccountProperties.Account a : usable) {
				if ("imap.gmail.com".equals(a.getHost())) {
					gmail.add(a);
				}
			}
			return gmail.isEmpty() ? usable : gmail;
		}
		List<EmailAccountProperties.Account> exact = new ArrayList<>();
		for (EmailAccountProperties.Account a : usable) {
			if (sourcingAccount.equalsIgnoreCase(a.getUsername())) {
				exact.add(a);
			}
		}
		return exact.isEmpty() ? usable : exact;
	}

	/**
	 * IMAP SEARCH(SubjectTerm)에 안전하게 쓸 수 있는 검색어인지 판정한다.
	 * 비ASCII 문자가 포함되면 charset 미지정 SEARCH에서 서버가 파싱 실패(BAD)를 던지므로 제외한다.
	 * iHerb 주문번호는 숫자(ASCII)라 정상 값은 모두 통과한다.
	 */
	static boolean isImapSearchable(String value) {
		if (value == null || value.isBlank()) {
			return false;
		}
		return value.chars().allMatch(c -> c < 128);
	}

	/**
	 * 한 계정에 1회 접속하여, 주어진 주문번호들을 서버측 IMAP SEARCH(SUBJECT)로 조회·처리한다.
	 * 주문번호는 숫자(ASCII)라 charset 이슈 없이 서버가 매칭 메일만 반환한다(1000통 스캔 대체).
	 */
	private void searchAccountForOrderNos(EmailAccountProperties.Account account, Set<String> orderNos) {
		if (account.getUsername() == null || account.getUsername().isBlank()) {
			log.debug("이메일 계정 username 미설정 - 스킵");
			return;
		}
		Properties props = new Properties();
		props.put("mail.store.protocol", account.getProtocol());
		props.put("mail.imaps.host", account.getHost());
		props.put("mail.imaps.port", String.valueOf(account.getPort()));
		props.put("mail.imaps.connectiontimeout", "10000");
		props.put("mail.imaps.timeout", "30000");

		try {
			Session session = Session.getDefaultInstance(props, null);
			Store store = session.getStore(account.getProtocol());
			store.connect(account.getHost(), account.getUsername(), account.getPassword());
			log.debug("IMAP 연결 성공: account={}, 대상주문 {}건", account.getUsername(), orderNos.size());

			Folder inbox = store.getFolder("INBOX");
			inbox.open(Folder.READ_ONLY);

			for (String orderNo : orderNos) {
				try {
					// 서버측 검색: 제목에 주문번호를 포함하는 메일만 반환
					Message[] matches = inbox.search(new SubjectTerm(orderNo));
					for (Message message : matches) {
						processMatchedMessage(account, orderNo, message);
					}
				} catch (Exception e) {
					log.error("이메일 검색 실패: orderNo={}, account={}", orderNo, account.getUsername(), e);
				}
			}

			inbox.close(false);
			store.close();
		} catch (Exception e) {
			log.error("IMAP 연결 실패: account={}, error={}", account.getUsername(), e.getMessage(), e);
		}
	}

	/** SEARCH로 매칭된 메일을 발송/확인 제목으로 분기해 처리한다. */
	private void processMatchedMessage(EmailAccountProperties.Account account, String orderNo, Message message)
		throws Exception {
		String subject = message.getSubject();
		if (subject == null) {
			return;
		}
		if (subject.contains("주문이 발송되었습니다 #" + orderNo)) {
			String body = getTextFromMessage(message);
			String from = message.getFrom() != null && message.getFrom().length > 0
				? message.getFrom()[0].toString() : account.getUsername();
			parser.parseIherbShipment(from, subject, body).ifPresent(shipmentData -> {
				log.info("iHerb 발송 이메일 발견: orderNo={}, tracking={}, account={}",
					shipmentData.getOrderNo(), shipmentData.getTrackingNo(), account.getUsername());
				processIherbShipment(shipmentData);
			});
		} else if (subject.contains("주문이 확인되었습니다 #" + orderNo)) {
			String body = getTextFromMessage(message);
			parser.parseIherbConfirmation(subject, body).ifPresent(confirmData -> {
				log.info("iHerb 확인 이메일 발견: orderNo={}, amount={}, account={}",
					confirmData.getOrderNo(), confirmData.getTotalAmount(), account.getUsername());
				processIherbConfirmation(confirmData);
			});
		}
	}

	// iHerb 발송 처리 (테스트 접근을 위해 package-private)
	void processIherbShipment(OrderEmailParser.IherbShipmentData shipmentData) {
		// iHerb 주문번호로 소싱 데이터 조회
		List<OrderLineItem> items = orderLineItemRepository.findBySourcingData_SourcingOrderNo(
			shipmentData.getOrderNo());

		if (items.isEmpty()) {
			log.warn("iHerb 주문번호 {}에 해당하는 라인아이템을 찾을 수 없습니다.", shipmentData.getOrderNo());
			return;
		}

		for (OrderLineItem item : items) {
			ShippingStatus currentStatus = item.getShippingData() != null
				? item.getShippingData().getShippingStatus() : null;
			String existingTracking = item.getShippingData() != null
				? item.getShippingData().getTrackingNo() : null;

			// 마켓에 이미 송장이 등록된 상태: DISPATCHED(쿠팡 DEPARTURE 등 송장 등록·추적 전) 또는 SHIPPED(배송중).
			// 두 상태 모두 마켓이 송장을 이미 보유 → 마켓 전송은 수정(updateTracking) 경로(invoiceAlreadyExists=true).
			boolean marketHasInvoice = currentStatus == ShippingStatus.DISPATCHED
				|| currentStatus == ShippingStatus.SHIPPED;

			if (marketHasInvoice) {
				boolean sameTracking = shipmentData.getTrackingNo().equals(existingTracking);
				if (sameTracking) {
					// 동일 송장: 우리 시스템이 마켓 전송을 확정(trackingSentToMarket)했으면 스킵, 아니면 재시도(수정 경로).
					boolean synced = item.getShippingData() != null
						&& Boolean.TRUE.equals(item.getShippingData().getTrackingSentToMarket());
					if (synced) {
						log.info("iHerb 주문 {} 이미 배송 처리 및 마켓 동기화 완료 (tracking={}) - 스킵",
							shipmentData.getOrderNo(), shipmentData.getTrackingNo());
						continue;
					}
					log.info("iHerb 주문 {} 송장 존재({})但 마켓 미동기화 - 재시도(수정 경로)",
						shipmentData.getOrderNo(), shipmentData.getTrackingNo());
					MarketShippingResult retryResult = marketplaceShippingService.sendTrackingToMarketplace(item, true);
					handleMarketResult(item, retryResult, shipmentData.getOrderNo(), "재시도");
					continue;
				}

				// 송장이 다른 경우: 취소 방지용 가짜 송장을 진짜 송장으로 교정하고 마켓 수정 반영.
				// (예: 쿠팡 동기화로 유입된 가짜/이전 송장 → 이메일로 진짜 송장 도착 → 반드시 수정 반영)
				ShippingCarrier carrier = mapCarrier(shipmentData.getCarrier());
				ShippingData currentShipping = item.getShippingData() != null
					? item.getShippingData() : ShippingData.builder().build();
				// 이메일 택배사가 없거나 미지원(ETC)로 매핑되면 기존 택배사 유지
				ShippingCarrier finalCarrier = (carrier != null && carrier != ShippingCarrier.ETC)
					? carrier : currentShipping.getShippingCarrier();
				item.applyShippingData(currentShipping.toBuilder()
					.trackingNo(shipmentData.getTrackingNo())
					.shippingCarrier(finalCarrier)
					.build()); // 배송상태(DISPATCHED/SHIPPED)는 유지 — 마켓 동기화로 반영
				orderLineItemRepository.save(item);
				log.info("iHerb 주문 {} 송장 변경 감지(기존={} → 신규={}, 상태={}) - 마켓 수정 반영",
					shipmentData.getOrderNo(), existingTracking, shipmentData.getTrackingNo(), currentStatus);
				MarketShippingResult updResult = marketplaceShippingService.sendTrackingToMarketplace(item, true);
				handleMarketResult(item, updResult, shipmentData.getOrderNo(), "송장교정");
				continue;
			}

			// PREPARING 상태이면 최초 배송 처리. iHerb 주문번호 존재(발송메일 매칭의 전제)가 곧 "구매함"의 신호이므로
			// PurchaseStatus는 게이팅에 쓰지 않는다(구매상태는 유저 수동 관리 필드).
			if (currentStatus == ShippingStatus.PREPARING) {
				ShippingCarrier carrier = mapCarrier(shipmentData.getCarrier());
				ShippingData currentShipping = item.getShippingData();
				if (currentShipping == null) {
					currentShipping = ShippingData.builder().build();
				}
				// 송장번호·택배사만 기록 — 배송상태는 마켓 API 동기화로 반영
				item.applyShippingData(currentShipping.toBuilder()
					.trackingNo(shipmentData.getTrackingNo())
					.shippingCarrier(carrier)
					.build());
				orderLineItemRepository.save(item);

				log.info("iHerb 발송 처리 완료: itemId={}, tracking={}, carrier={}",
					item.getId(), shipmentData.getTrackingNo(), carrier);

				// 마켓에 아직 송장이 없음 → 초기등록(shipOrder) 경로. 실패해도 배송 저장은 보존, 성공 시에만 전송완료 마킹.
				MarketShippingResult sendResult = marketplaceShippingService.sendTrackingToMarketplace(item, false);
				handleMarketResult(item, sendResult, shipmentData.getOrderNo(), "최초발송");
			} else {
				log.info("iHerb 주문 {} 상태({})가 배송 처리 조건 미충족 — 스킵",
					shipmentData.getOrderNo(), currentStatus);
			}
		}
	}

	/**
	 * 마켓 송장 전송 결과 후처리 (D-E6).
	 * - sent: 전송 성공 → trackingSentToMarket 마킹.
	 * - terminal: 마켓 배송상태 잠금(쿠팡 배송중/배송완료)으로 재시도 불가 → 재시도 루프를 끊기 위해
	 *   전송종결로 마킹하고(실송장은 이미 DB 기록됨), 실제 성공과 구분되도록 감사 로그(ActionLog)를 남긴다.
	 * - failed(일시): 미마킹 → 다음 사이클 재시도.
	 */
	private void handleMarketResult(OrderLineItem item, MarketShippingResult result,
		String orderNo, String phase) {
		if (result.sent()) {
			item.markTrackingAsSent();
			orderLineItemRepository.save(item);
			return;
		}
		if (result.isTerminal()) {
			// 재시도해도 성공 불가 → 종결 마킹으로 30분 재시도 루프 중단. 실송장은 DB에 보존됨.
			item.markTrackingAsSent();
			orderLineItemRepository.save(item);
			String tracking = item.getShippingData() != null ? item.getShippingData().getTrackingNo() : null;
			actionLogService.record(ActionLogConstants.SHIPPING_UPDATE, "COUPANG", ActionStatus.FAILED,
				"쿠팡 배송상태 잠금으로 마켓 송장 전송 종결(재시도 중단, " + phase + "): iHerb주문 " + orderNo
					+ " — 실송장(" + tracking + ")은 DB 기록됨, 마켓 반영 불가. 사유: " + result.failureReason());
			log.warn("iHerb 주문 {} 마켓 배송상태 잠금 - 전송 종결({}): {}",
				orderNo, phase, result.failureReason());
			return;
		}
		// 일시 실패: 미마킹(다음 사이클 재시도)
		log.warn("iHerb 주문 {} 마켓 송장 전송 실패 - 미마킹(다음 사이클 재시도, {}): {}",
			orderNo, phase, result.failureReason());
	}

	// iHerb 주문 확인 처리 (실구매가 자동 기록). 테스트 접근을 위해 package-private
	void processIherbConfirmation(OrderEmailParser.IherbConfirmationData confirmData) {
		List<OrderLineItem> items = orderLineItemRepository.findBySourcingData_SourcingOrderNo(
			confirmData.getOrderNo());

		if (items.isEmpty()) {
			log.info("iHerb 주문번호 {}에 해당하는 라인아이템 없음 (이미 처리되었거나 미동기화)", confirmData.getOrderNo());
			return;
		}

		// 확인메일의 총 결제 금액은 iHerb 주문 1건 전체의 금액이다. 한 주문번호가 여러 라인아이템에
		// 걸리면 총액을 라인별로 배분할 근거가 메일에 없고, 총액을 양쪽에 넣으면 sourcing_amount가
		// 라인아이템별로 합산되는 순수익 계산에서 원가가 중복 계상된다 → 자동 주입하지 않고 수동 입력에 맡긴다.
		if (items.size() > 1) {
			log.warn("iHerb 주문 {}이 라인아이템 {}건에 걸쳐 총액({}) 배분 불가 - 실구매가 자동 주입 스킵(수동 입력 필요)",
				confirmData.getOrderNo(), items.size(), confirmData.getTotalAmount());
			return;
		}

		for (OrderLineItem item : items) {
			if (confirmData.getTotalAmount() != null) {
				// 이미 실구매가가 있으면 스킵 (멱등성)
				BigDecimal existingAmount = item.getSourcingData() != null
					? item.getSourcingData().getSourcingAmount() : null;
				if (existingAmount != null && existingAmount.compareTo(BigDecimal.ZERO) > 0) {
					log.info("iHerb 주문 {} 이미 실구매가 기록됨 ({}) - 스킵",
						confirmData.getOrderNo(), existingAmount);
					continue;
				}

				// 소싱 데이터에 총 결제 금액을 실구매가로 저장
				SourcingData current = item.getSourcingData();
				SourcingData updated = (current != null ? current
					: SourcingData.builder().build())
					.toBuilder()
					.sourcingAmount(confirmData.getTotalAmount())
					.build();
				item.applySourcingData(updated);
				orderLineItemRepository.save(item);

				log.info("iHerb 주문 확인 실구매가 기록: itemId={}, orderNo={}, amount={}",
					item.getId(), confirmData.getOrderNo(), confirmData.getTotalAmount());
			}
		}
	}

	// 택배사 매핑
	private ShippingCarrier mapCarrier(String carrierName) {
		if (carrierName == null)
			return ShippingCarrier.ETC;
		String lower = carrierName.toLowerCase();
		if (lower.contains("dhl"))
			return ShippingCarrier.ETC;
		if (lower.contains("fedex"))
			return ShippingCarrier.ETC;
		if (lower.contains("ups"))
			return ShippingCarrier.ETC;
		if (lower.contains("usps"))
			return ShippingCarrier.ETC;
		if (lower.contains("ems"))
			return ShippingCarrier.ETC;
		if (lower.contains("cj") || lower.contains("대한통운"))
			return ShippingCarrier.CJ_LOGISTICS;
		if (lower.contains("lotte") || lower.contains("롯데"))
			return ShippingCarrier.LOTTE_LOGISTICS;
		if (lower.contains("post") || lower.contains("우체국"))
			return ShippingCarrier.KOREA_POST;
		return ShippingCarrier.ETC;
	}

	// iHerb 주문 확인 이메일 즉시 검색 (구매처리 시 호출)
	public Optional<BigDecimal> findIherbConfirmationAmount(String orderNo) {
		if (properties.getAccounts() == null || properties.getAccounts().isEmpty()) {
			return Optional.empty();
		}
		// 비ASCII 주문번호는 IMAP SEARCH 파싱 실패를 유발하므로 검색하지 않는다(buildSearchPlan과 동일 가드).
		if (!isImapSearchable(orderNo)) {
			log.debug("iHerb 확인금액 검색 제외 — 비ASCII 주문번호 '{}'", orderNo);
			return Optional.empty();
		}

		for (EmailAccountProperties.Account account : properties.getAccounts()) {
			Optional<BigDecimal> result = searchConfirmationInAccount(account, orderNo);
			if (result.isPresent()) {
				return result;
			}
		}
		return Optional.empty();
	}

	/**
	 * Gmail 호환: 특정 주문번호의 확인 이메일을 검색하여 실구매가 반환
	 */
	private Optional<BigDecimal> searchConfirmationInAccount(
		EmailAccountProperties.Account account, String orderNo) {

		Properties props = new Properties();
		props.put("mail.store.protocol", account.getProtocol());
		props.put("mail.imaps.host", account.getHost());
		props.put("mail.imaps.port", String.valueOf(account.getPort()));
		props.put("mail.imaps.connectiontimeout", "10000");
		props.put("mail.imaps.timeout", "30000");

		try {
			Session session = Session.getDefaultInstance(props, null);
			Store store = session.getStore(account.getProtocol());
			store.connect(account.getHost(), account.getUsername(), account.getPassword());

			Folder inbox = store.getFolder("INBOX");
			inbox.open(Folder.READ_ONLY);

			String searchSubject = "주문이 확인되었습니다 #" + orderNo;

			// 서버측 SEARCH(SUBJECT 주문번호)로 매칭 메일만 조회 (숫자=ASCII, charset 무관)
			Message[] messages = inbox.search(new SubjectTerm(orderNo));

			for (Message message : messages) {
				String subject = message.getSubject();
				if (subject != null && subject.contains(searchSubject)) {
					String body = getTextFromMessage(message);

					Optional<OrderEmailParser.IherbConfirmationData> parsed = parser
						.parseIherbConfirmation(subject, body);

					inbox.close(false);
					store.close();

					return parsed.flatMap(data -> {
						if (data.getTotalAmount() != null) {
							log.info("iHerb 주문 확인 메일 발견: orderNo={}, amount={}, account={}",
								orderNo, data.getTotalAmount(), account.getUsername());
						}
						return Optional.ofNullable(data.getTotalAmount());
					});
				}
			}

			inbox.close(false);
			store.close();
		} catch (Exception e) {
			log.debug("이메일 검색 실패 (account: {}): {}", account.getUsername(), e.getMessage());
		}
		return Optional.empty();
	}

	private String getTextFromMessage(Message message) throws Exception {
		if (message.isMimeType("text/plain")) {
			return message.getContent().toString();
		} else if (message.isMimeType("multipart/*")) {
			Multipart multipart = (Multipart)message.getContent();
			return getTextFromMultipart(multipart);
		}
		return "";
	}

	private String getTextFromMultipart(Multipart multipart) throws Exception {
		StringBuilder result = new StringBuilder();
		for (int i = 0; i < multipart.getCount(); i++) {
			BodyPart bodyPart = multipart.getBodyPart(i);
			if (bodyPart.isMimeType("text/plain")) {
				result.append(bodyPart.getContent());
			} else if (bodyPart.getContent() instanceof Multipart) {
				result.append(getTextFromMultipart((Multipart)bodyPart.getContent()));
			}
		}
		return result.toString();
	}
}
