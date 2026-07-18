package com.sbshop.agent.worker.service;

import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.order.service.MarketShippingResult;
import com.sbshop.agent.core.application.order.service.MarketplaceShippingService;
import com.sbshop.agent.core.domain.actionlog.ActionLogConstants;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.PurchaseStatus;
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
	@Transactional
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

			// 1. DB에서 이메일 처리가 필요한 iHerb 주문번호 조회
			List<OrderLineItem> items = orderLineItemRepository.findIherbItemsNeedingEmailProcessing();
			if (items.isEmpty()) {
				log.debug("이메일 처리가 필요한 iHerb 주문이 없습니다.");
				return true;
			}

			// 2. 라우팅: 주문의 소싱 계정(sourcing_account)으로 검색할 메일함을 좁힌다.
			//    - Gmail 소싱분은 중앙 계정으로 자동전달되므로 중앙(Gmail) 메일함만
			//    - 비-Gmail 소싱분은 해당 제공자 메일함만 (미매칭·미상은 전 계정 폴백)
			Map<EmailAccountProperties.Account, Set<String>> plan = buildSearchPlan(items);
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
	 */
	private Map<EmailAccountProperties.Account, Set<String>> buildSearchPlan(List<OrderLineItem> items) {
		Map<EmailAccountProperties.Account, Set<String>> plan = new LinkedHashMap<>();
		List<EmailAccountProperties.Account> accounts = properties.getAccounts();
		for (OrderLineItem item : items) {
			var sourcingData = item.getSourcingData();
			String orderNo = sourcingData != null ? sourcingData.getSourcingOrderNo() : null;
			if (orderNo == null || orderNo.isBlank()) {
				continue;
			}
			String sourcingAccount = sourcingData != null ? sourcingData.getSourcingAccount() : null;
			for (EmailAccountProperties.Account target : resolveTargetAccounts(sourcingAccount, accounts)) {
				plan.computeIfAbsent(target, k -> new LinkedHashSet<>()).add(orderNo);
			}
		}
		return plan;
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

			// 이미 SHIPPED 상태이고 동일 송장번호인 경우
			String existingTracking = item.getShippingData() != null
				? item.getShippingData().getTrackingNo() : null;
			boolean alreadyShipped = currentStatus == ShippingStatus.SHIPPED
				&& shipmentData.getTrackingNo().equals(existingTracking);

			if (alreadyShipped) {
				// 마켓 동기화가 안 된 경우에만 재시도
				boolean synced = item.getShippingData() != null
					&& Boolean.TRUE.equals(item.getShippingData().getTrackingSentToMarket());
				if (synced) {
					log.info("iHerb 주문 {} 이미 배송 처리 및 마켓 동기화 완료 (tracking={}) - 스킵",
						shipmentData.getOrderNo(), shipmentData.getTrackingNo());
					continue;
				}
				// 마켓 미동기화 건은 재시도
				log.info("iHerb 주문 {} 배송 처리됨但 마켓 미동기화 (tracking={}) - 재시도",
					shipmentData.getOrderNo(), shipmentData.getTrackingNo());
				// 마켓 미동기화(재시도) — 마켓에 아직 송장이 없으므로 초기등록(shipOrder) 경로.
				MarketShippingResult retryResult = marketplaceShippingService.sendTrackingToMarketplace(item, false);
				handleMarketResult(item, retryResult, shipmentData.getOrderNo(), "재시도");
				continue;
			}

			// 이미 SHIPPED이지만 송장번호가 다른 경우: 취소 방지용 가짜 송장을 진짜 송장으로 교정.
			// (예: 운영자가 가짜 송장 선입력 → 이메일로 진짜 송장 도착 → 반드시 수정 반영)
			if (currentStatus == ShippingStatus.SHIPPED) {
				ShippingCarrier carrier = mapCarrier(shipmentData.getCarrier());
				ShippingData currentShipping = item.getShippingData() != null
					? item.getShippingData() : ShippingData.builder().build();
				// 이메일 택배사가 없거나 미지원(ETC)로 매핑되면 기존 택배사 유지
				ShippingCarrier finalCarrier = (carrier != null && carrier != ShippingCarrier.ETC)
					? carrier : currentShipping.getShippingCarrier();
				item.applyShippingData(currentShipping.toBuilder()
					.trackingNo(shipmentData.getTrackingNo())
					.shippingCarrier(finalCarrier)
					.build()); // 상태는 SHIPPED 유지
				orderLineItemRepository.save(item);
				log.info("iHerb 주문 {} 송장 변경 감지(기존={} → 신규={}) - 마켓 수정 반영",
					shipmentData.getOrderNo(), existingTracking, shipmentData.getTrackingNo());
				// 마켓엔 이미 (가짜)송장이 존재 → 수정(updateTracking) 경로: 두번째 인자 true.
				MarketShippingResult updResult = marketplaceShippingService.sendTrackingToMarketplace(item, true);
				handleMarketResult(item, updResult, shipmentData.getOrderNo(), "송장교정");
				continue;
			}

			// PREPARING 상태이고 구매완료(PurchaseStatus.PURCHASED)인 경우에만 배송 처리
			if (currentStatus == ShippingStatus.PREPARING
				&& item.getPurchaseStatus() == PurchaseStatus.PURCHASED) {
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

				// 마켓플러스에 송장 전송 — 실패해도 배송 저장은 보존, 성공 시에만 전송완료 마킹
				// 최초 발송 — 마켓에 아직 송장이 없으므로 초기등록(shipOrder) 경로.
				MarketShippingResult sendResult = marketplaceShippingService.sendTrackingToMarketplace(item, false);
				handleMarketResult(item, sendResult, shipmentData.getOrderNo(), "최초발송");
			} else {
				log.info("iHerb 주문 {} 상태({}/구매={})가 배송 처리 조건 미충족 — 스킵",
					shipmentData.getOrderNo(), currentStatus, item.getPurchaseStatus());
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

	// iHerb 주문 확인 처리 (실구매가 자동 기록)
	private void processIherbConfirmation(OrderEmailParser.IherbConfirmationData confirmData) {
		List<OrderLineItem> items = orderLineItemRepository.findBySourcingData_SourcingOrderNo(
			confirmData.getOrderNo());

		if (items.isEmpty()) {
			log.info("iHerb 주문번호 {}에 해당하는 라인아이템 없음 (이미 처리되었거나 미동기화)", confirmData.getOrderNo());
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
