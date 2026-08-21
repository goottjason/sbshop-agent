package com.sbshop.agent.worker.service;

import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.order.service.MarketShippingResult;
import com.sbshop.agent.core.application.order.service.MarketplaceShippingService;
import com.sbshop.agent.core.domain.actionlog.ActionLogConstants;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.enums.TrackingSource;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.order.vo.SourcingData;
import com.sbshop.agent.core.domain.order.vo.ShippingData;
import com.sbshop.agent.core.config.EmailAccountProperties;
import jakarta.mail.*;
import jakarta.mail.search.SubjectTerm;
import org.eclipse.angus.mail.imap.IMAPFolder;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
	/** D-133: 송장 쓰기는 이 통로만 쓴다 — 배송이 붙어 있으면 배송이 단일 원본이다. */
	private final com.sbshop.agent.core.application.order.service.LineItemShippingWriter shippingWriter;

	// F-MISC-18: 재진입/동시실행 가드.
	// EmailFetchController(수동 /internal/email/fetch)와 OrderSyncScheduler(cron 0/30)가
	// 같은 worker JVM에서 fetchAndProcessEmails()를 동시에 호출하면 같은 발송메일을 이중 처리 →
	// 마켓에 중복 송장 전송되는 창이 있다(PURCHASED 아이템을 두 실행이 동시에 읽어 각각 shipOrder).
	// 인-JVM 플래그로 이미 실행 중이면 두 번째 호출은 본처리를 스킵하고 즉시 반환한다.
	// (cross-JVM 아님 — 컨트롤러·스케줄러 모두 worker JVM이라 인-JVM 가드로 충분.)
	// AtomicBoolean은 서로 다른 스레드 간 겹침은 물론 같은 스레드의 재진입도 막는다
	// (ReentrantLock은 동일 스레드 재진입을 허용하므로 부적합).
	private final AtomicBoolean fetching = new AtomicBoolean(false);

	/** 실구매가 인식 실패를 활동 로그에 이미 남긴 주문번호(D-115). 30분마다 같은 건이 쌓이는 것을 막는다. */
	private final Set<String> amountParseFailuresReported = ConcurrentHashMap.newKeySet();

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
		List<EmailAccountProperties.Account> matched = new ArrayList<>();
		for (EmailAccountProperties.Account a : usable) {
			if (isSameMailbox(sourcingAccount, a)) {
				matched.add(a);
			}
		}
		return matched.isEmpty() ? usable : matched;
	}

	/**
	 * 소싱 계정과 설정 계정이 같은 메일함인지 판정한다.
	 *
	 * <p>주소 문자열 완전일치로는 **같은 제공자의 별칭 도메인**을 못 잡는다 —
	 * 운영에서 소싱 계정 {@code tonyworld@hanmail.net}이 설정 계정 {@code tonyworld@daum.net}과
	 * 매칭되지 않아 9개 계정 전부로 퍼졌고, 검색 계획의 약 67%가 이 팬아웃이었다.
	 * local-part가 같고 해석된 IMAP host가 같으면 같은 메일함으로 본다
	 * (hanmail.net·daum.net은 {@code DOMAIN_IMAP_HOST}에서 이미 같은 imap.daum.net으로 간다).
	 * local-part만 같고 제공자가 다르면 남남일 수 있으므로 매칭하지 않는다.
	 */
	private static boolean isSameMailbox(String sourcingAccount, EmailAccountProperties.Account account) {
		String sourcingLocal = localPart(sourcingAccount);
		String accountLocal = localPart(account.getUsername());
		if (sourcingLocal == null || accountLocal == null || !sourcingLocal.equalsIgnoreCase(accountLocal)) {
			return false;
		}
		String accountHost = account.getHost() != null && !account.getHost().isBlank()
			? account.getHost() : EmailAccountProperties.imapHostForEmail(account.getUsername());
		return EmailAccountProperties.imapHostForEmail(sourcingAccount).equalsIgnoreCase(accountHost);
	}

	/** 이메일 주소의 @ 앞부분. @가 없으면 전체를 local-part로 본다. */
	private static String localPart(String email) {
		if (email == null || email.isBlank()) {
			return null;
		}
		int at = email.lastIndexOf('@');
		return at < 0 ? email : email.substring(0, at);
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

			for (Folder folder : resolveSearchFolders(store, account)) {
				searchFolderForOrderNos(account, folder, orderNos);
			}

			store.close();
		} catch (Exception e) {
			log.error("IMAP 연결 실패: account={}, error={}", account.getUsername(), e.getMessage(), e);
		}
	}

	/**
	 * 검색할 메일함(폴더)을 고른다. INBOX만 보면 보관처리된 메일을 놓친다(D-112).
	 *
	 * <p>Gmail은 "전체보관함"({@code \All} 속성)이 INBOX·라벨·보관함을 모두 포함하는 상위집합이라
	 * 그 한 폴더만 검색하면 충분하다 — 전 폴더를 도는 것보다 검색 횟수가 배수로 줄어 30분 주기를 지킬 수 있다.
	 * ({@code \All} 폴더명은 로케일마다 다르므로("[Gmail]/All Mail" vs "[Gmail]/전체보관함")
	 * 이름이 아니라 IMAP SPECIAL-USE 속성으로 판별한다.)
	 * 그 외 제공자는 메일을 담는 모든 폴더를 훑는다. 폴더 목록 조회가 실패하면 INBOX로 폴백한다.
	 */
	private List<Folder> resolveSearchFolders(Store store, EmailAccountProperties.Account account) {
		List<Folder> holders = new ArrayList<>();
		try {
			for (Folder folder : store.getDefaultFolder().list("*")) {
				if ((folder.getType() & Folder.HOLDS_MESSAGES) == 0) {
					continue;
				}
				// 보낸편지함·임시보관함에는 iHerb가 보낸 메일이 있을 수 없다 — 계정당 폴더 2~3곳 절감.
				// (스팸·휴지통은 진짜 확인메일이 들어갈 수 있으므로 계속 검색한다.)
				if (isNonReceivingFolder(folder)) {
					continue;
				}
				if (isAllMailFolder(folder)) {
					log.info("전체보관함 단독 검색: account={}, folder={}",
						account.getUsername(), folder.getFullName());
					return List.of(folder);
				}
				holders.add(folder);
			}
		} catch (Exception e) {
			log.warn("폴더 목록 조회 실패 - INBOX만 검색: account={}, error={}",
				account.getUsername(), e.getMessage());
		}
		if (holders.isEmpty()) {
			try {
				return List.of(store.getFolder("INBOX"));
			} catch (Exception e) {
				log.error("INBOX 조회 실패: account={}", account.getUsername(), e);
				return List.of();
			}
		}
		log.info("전 폴더 검색: account={}, 폴더 {}곳 ({})", account.getUsername(), holders.size(),
			holders.stream().map(Folder::getFullName).toList());
		return holders;
	}

	/** IMAP SPECIAL-USE {@code \All} 속성 보유 여부(Gmail 전체보관함). 속성 조회 불가 구현체면 false. */
	private static boolean isAllMailFolder(Folder folder) {
		return hasAttribute(folder, "\\All");
	}

	/** 받은메일이 있을 수 없는 폴더(보낸편지함·임시보관함)인지. 속성 우선, 없으면 폴더명으로 판정. */
	private static boolean isNonReceivingFolder(Folder folder) {
		if (hasAttribute(folder, "\\Sent") || hasAttribute(folder, "\\Drafts")) {
			return true;
		}
		return isNonReceivingFolderName(folder.getName());
	}

	/** 제공자별 보낸편지함·임시보관함 이름(운영 계정 9곳에서 실제로 관측된 표기). */
	private static final Set<String> NON_RECEIVING_FOLDER_NAMES = Set.of(
		"sent", "sent messages", "sent items", "보낸편지함", "보낸 편지함",
		"보낼편지함", "보낼 편지함", "drafts", "임시보관함");

	// 테스트 접근을 위해 package-private
	static boolean isNonReceivingFolderName(String name) {
		if (name == null) {
			return false;
		}
		return NON_RECEIVING_FOLDER_NAMES.contains(name.trim().toLowerCase());
	}

	/** IMAP SPECIAL-USE 속성 보유 여부. 속성 조회가 불가능한 구현체·오류면 false. */
	private static boolean hasAttribute(Folder folder, String attribute) {
		if (!(folder instanceof IMAPFolder imapFolder)) {
			return false;
		}
		try {
			for (String found : imapFolder.getAttributes()) {
				if (attribute.equalsIgnoreCase(found)) {
					return true;
				}
			}
		} catch (Exception e) {
			return false;
		}
		return false;
	}

	/** 폴더 1곳을 열어 주어진 주문번호들을 서버측 SEARCH로 조회·처리한다. */
	private void searchFolderForOrderNos(EmailAccountProperties.Account account, Folder folder,
		Set<String> orderNos) {
		try {
			folder.open(Folder.READ_ONLY);
		} catch (Exception e) {
			log.debug("폴더 열기 실패 - 스킵: account={}, folder={}, error={}",
				account.getUsername(), folder.getFullName(), e.getMessage());
			return;
		}
		try {
			for (String orderNo : orderNos) {
				try {
					// 서버측 검색: 제목에 주문번호를 포함하는 메일만 반환
					Message[] matches = folder.search(new SubjectTerm(orderNo));
					for (Message message : matches) {
						processMatchedMessage(account, orderNo, message);
					}
				} catch (Exception e) {
					log.error("이메일 검색 실패: orderNo={}, account={}, folder={}",
						orderNo, account.getUsername(), folder.getFullName(), e);
				}
			}
		} finally {
			try {
				folder.close(false);
			} catch (Exception e) {
				log.debug("폴더 닫기 실패: {}", e.getMessage());
			}
		}
	}

	/** SEARCH로 매칭된 메일을 발송/확인 제목으로 분기해 처리한다. */
	private void processMatchedMessage(EmailAccountProperties.Account account, String orderNo, Message message)
		throws Exception {
		String subject = message.getSubject();
		if (subject == null) {
			return;
		}
		// 제목에 그 주문번호가 있는 메일만 처리한다. 문구와 주문번호의 순서는 고정하지 않는다 —
		// iHerb가 "확인되었습니다 #123"으로도, "주문 #123 결제가 처리되었습니다"로도 보낸다.
		if (!subject.contains("#" + orderNo)) {
			return;
		}
		if (OrderEmailParser.isShipmentSubject(subject)) {
			String body = getTextFromMessage(message);
			String from = message.getFrom() != null && message.getFrom().length > 0
				? message.getFrom()[0].toString() : account.getUsername();
			parser.parseIherbShipment(from, subject, body).ifPresent(shipmentData -> {
				log.info("iHerb 발송 이메일 발견: orderNo={}, tracking={}, account={}",
					shipmentData.getOrderNo(), shipmentData.getTrackingNo(), account.getUsername());
				processIherbShipment(shipmentData);
			});
		} else if (OrderEmailParser.isConfirmationSubject(subject)) {
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
					// 출처는 "무엇이 이 값을 확인했나"다. 값이 같아 쓰지 않고 지나가더라도,
					// 이메일이 이 송장을 진짜라고 확인해 준 사실은 남긴다 — 그러지 않으면
					// 마켓이 먼저 알려준 진짜 송장이 영영 ✍(진위 불명)로 표시된다.
					shippingWriter.promoteTrackingSourceToEmail(item);
					// D-147: "이미 동기화됨"을 trackingSentToMarket 플래그로 판정하면 안 된다. 그 플래그는
					// 전송이 실패해도 참으로 남는다(거짓 성공, D-145). 진실은 배송에 기록된 마켓 보유 송장이다.
					boolean synced = shippingWriter.marketHasTracking(item, shipmentData.getTrackingNo());
					if (synced) {
						log.info("iHerb 주문 {} 이미 배송 처리 및 마켓 동기화 완료 (tracking={}) - 스킵",
							shipmentData.getOrderNo(), shipmentData.getTrackingNo());
						continue;
					}
					// 마켓이 영구 거부해 사람이 고치기를 기다리는 중이면 재전송은 무의미하다 —
					// 30분마다 같은 거부를 받아낼 뿐이다. 사람이 고치면 동기화가 표시를 스스로 끈다(D-148).
					if (shippingWriter.isAwaitingManualFix(item)) {
						log.info("iHerb 주문 {} 마켓 수동수정 대기 중 (tracking={}) - 재전송 생략",
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
				// D-133: 저장은 통로가 한다. 배송이 붙어 있으면 배송에도 송장이 기록돼야 한다 —
				// 2단계에서 발송처리 단위가 배송이 되면 배송이 모르는 송장은 마켓에 나가지 않는다.
				shippingWriter.applyShipping(item, currentShipping.toBuilder()
					.trackingNo(shipmentData.getTrackingNo())
					.shippingCarrier(finalCarrier)
					.build(), TrackingSource.EMAIL); // 배송상태(DISPATCHED/SHIPPED)는 유지 — 마켓 동기화로 반영
				log.info("iHerb 주문 {} 송장 변경 감지(기존={} → 신규={}, 상태={}) - 마켓 수정 반영",
					shipmentData.getOrderNo(), existingTracking, shipmentData.getTrackingNo(), currentStatus);
				MarketShippingResult updResult = marketplaceShippingService.sendTrackingToMarketplace(item, true);
				handleMarketResult(item, updResult, shipmentData.getOrderNo(), "송장교정");
				continue;
			}

			// D-121: 종전에는 PREPARING일 때만 송장을 기록하고 그 외 상태(NEW·DELIVERED 등)는 통째로 스킵했다.
			// 그래서 발주확인 전(NEW)이나 이미 배송완료(DELIVERED)로 넘어간 뒤 발송메일이 도착하면 송장이
			// 영원히 비어 있었다(옥션 실사례). 송장은 배송 사실의 기록이므로, 우리에게 실값이 없으면
			// 상태와 무관하게 채운다 — 배송상태는 건드리지 않으므로 마켓 진실을 훼손하지 않는다.
			ShippingCarrier carrier = mapCarrier(shipmentData.getCarrier());
			ShippingData currentShipping = item.getShippingData() != null
				? item.getShippingData() : ShippingData.builder().build();

			// 송장번호·택배사만 기록 — 배송상태는 마켓 API 동기화로 반영
			shippingWriter.applyShipping(item, currentShipping.toBuilder()
				.trackingNo(shipmentData.getTrackingNo())
				.shippingCarrier(carrier)
				.build(), TrackingSource.EMAIL);

			log.info("iHerb 발송 처리 완료: itemId={}, status={}, tracking={}, carrier={}",
				item.getId(), currentStatus, shipmentData.getTrackingNo(), carrier);

			// 마켓 전송은 마켓이 받아주는 상태에서만 시도한다. PREPARING은 초기등록(shipOrder) 경로.
			// NEW(발주확인 전)·DELIVERED(배송완료) 등은 마켓이 송장 등록을 거부하므로 전송하지 않고
			// 로컬 기록만 남긴다 — 마켓 송장은 동기화가 가져온다.
			if (currentStatus == ShippingStatus.PREPARING) {
				MarketShippingResult sendResult = marketplaceShippingService.sendTrackingToMarketplace(item, false);
				handleMarketResult(item, sendResult, shipmentData.getOrderNo(), "최초발송");
			} else {
				log.info("iHerb 주문 {} 상태({}) — 송장은 기록하고 마켓 전송은 생략(마켓 동기화가 진실 원본)",
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
	/** 감사 로그용 마켓 타입(조회 실패 시 UNKNOWN — 로깅 때문에 본 처리를 깨뜨리지 않는다). */
	private String marketTypeOf(OrderLineItem item) {
		if (item.getOrderId() == null) {
			return "UNKNOWN";
		}
		return orderRepository.findById(item.getOrderId())
			.map(o -> o.getMarketType() != null ? o.getMarketType().name() : "UNKNOWN")
			.orElse("UNKNOWN");
	}

	private void handleMarketResult(OrderLineItem item, MarketShippingResult result,
		String orderNo, String phase) {
		if (result.sent()) {
			shippingWriter.markTrackingAsSent(item);
			return;
		}
		if (result.isTerminal()) {
			// 재시도해도 성공 불가 → 종결 마킹으로 30분 재시도 루프 중단. 실송장은 DB에 보존됨.
			shippingWriter.markTrackingAsSent(item);
			// 사람이 판매자센터에서 직접 고쳐야 한다 — 화면이 그 사실을 보여줄 수 있게 배송에 남긴다.
			// (마켓 값이 우리 송장을 따라오면 이 표시는 동기화가 스스로 끈다.)
			shippingWriter.markManualFixRequired(item);
			String tracking = item.getShippingData() != null ? item.getShippingData().getTrackingNo() : null;
			// D-123: 종전에는 마켓을 "COUPANG"으로 하드코딩해 11번가·Cafe24 종결 건까지 쿠팡으로 기록됐다.
			String market = marketTypeOf(item);
			actionLogService.record(ActionLogConstants.SHIPPING_UPDATE, market, ActionStatus.FAILED,
				"마켓(" + market + ") 상태 잠금으로 송장 전송 종결(재시도 중단, " + phase + "): iHerb주문 " + orderNo
					+ " — 실송장(" + tracking + ")은 DB 기록됨, 마켓 반영 불가. 사유: " + result.failureReason());
			log.warn("iHerb 주문 {} 마켓({}) 상태 잠금 - 전송 종결({}): {}",
				orderNo, market, phase, result.failureReason());
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

		BigDecimal amountKrw = toKrw(confirmData);
		if (amountKrw == null) {
			reportAmountParseFailure(confirmData);
			return;
		}

		for (OrderLineItem item : items) {
			if (amountKrw != null) {
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
					.sourcingAmount(amountKrw)
					.build();
				item.applySourcingData(updated);
				orderLineItemRepository.save(item);

				log.info("iHerb 주문 확인 실구매가 기록: itemId={}, orderNo={}, amount={} (액면 {} {})",
					item.getId(), confirmData.getOrderNo(), amountKrw,
					confirmData.getTotalAmount(), confirmData.getCurrency());
			}
		}
	}

	/**
	 * 확인메일은 찾았는데 금액을 못 읽은 경우를 활동 로그로 노출한다(D-115).
	 *
	 * <p>iHerb가 총액 라벨이나 제목을 바꾸면 조용히 누락되고, 지금까지는 서버 로그를 뒤져야만
	 * 알 수 있었다. 화면(활동 로그)에 남겨 즉시 눈에 띄게 한다.
	 * 같은 주문이 30분마다 재시도되므로 <b>주문번호당 1회</b>만 기록한다(JVM 단위 — 배포 후에는
	 * 다시 한 번 기록되어, 수정이 실제로 먹혔는지 확인할 수 있다).
	 */
	private void reportAmountParseFailure(OrderEmailParser.IherbConfirmationData confirmData) {
		if (!amountParseFailuresReported.add(confirmData.getOrderNo())) {
			return;
		}
		String detail = confirmData.getAmountDiagnostic() != null
			? confirmData.getAmountDiagnostic()
			: "액면 " + confirmData.getTotalAmount() + " " + confirmData.getCurrency() + " (환율 미설정)";
		actionLogService.record(ActionLogConstants.PURCHASE_AMOUNT_PARSE, "EMAIL", ActionStatus.FAILED,
			"iHerb 주문 " + confirmData.getOrderNo() + " 확인메일에서 실구매가를 읽지 못했습니다"
				+ " — 수동 입력 필요. 메일 본문: " + detail);
		log.warn("iHerb 주문 {} 실구매가 인식 실패 - 활동 로그 기록", confirmData.getOrderNo());
	}

	/**
	 * 확인메일의 액면 금액을 원화 실구매가로 환산한다.
	 * 달러 표기 주문은 원화 청구액이 카드사 환율로 정해져 메일에 없으므로, 설정된 실효환율로 근사한다
	 * (사용자 결정 2026-07-28 — 근사값이라도 넣는다). 정확한 값은 수동 편집으로 덮어쓸 수 있다.
	 */
	private BigDecimal toKrw(OrderEmailParser.IherbConfirmationData confirmData) {
		BigDecimal faceValue = confirmData.getTotalAmount();
		if (faceValue == null) {
			return null;
		}
		if (!OrderEmailParser.USD.equals(confirmData.getCurrency())) {
			return faceValue;
		}
		BigDecimal rate = properties.getUsdKrwRate();
		if (rate == null || rate.compareTo(BigDecimal.ZERO) <= 0) {
			log.warn("iHerb 주문 {} 달러 표기(${})지만 환율 미설정 - 자동 주입 스킵",
				confirmData.getOrderNo(), faceValue);
			return null;
		}
		BigDecimal converted = faceValue.multiply(rate).setScale(0, RoundingMode.HALF_UP);
		log.info("iHerb 주문 {} 달러 표기 ${} → 환율 {} 적용해 약 {}원으로 환산(근사값)",
			confirmData.getOrderNo(), faceValue, rate, converted);
		return converted;
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

	/**
	 * 메일 본문을 텍스트로 뽑는다. HTML 파트도 포함한다(D-112-2).
	 *
	 * <p>과거 구현은 {@code text/plain}과 multipart만 다루고 {@code text/html}을 통째로 버려,
	 * HTML 단독 발송 메일에서 본문이 빈 문자열이 됐다 — 확인메일을 찾고도 금액을 못 읽어
	 * 실구매가가 영구 누락됐다(younzara@nate.com 8건). HTML 태그 제거는 파서의 flattenHtml이 한다.
	 */
	// 테스트 접근을 위해 package-private
	String getTextFromMessage(Message message) throws Exception {
		if (message.isMimeType("text/plain") || message.isMimeType("text/html")) {
			return message.getContent().toString();
		} else if (message.isMimeType("multipart/*")) {
			Multipart multipart = (Multipart)message.getContent();
			return getTextFromMultipart(multipart);
		}
		return "";
	}

	/**
	 * multipart를 훑어 텍스트를 모은다. plain 파트를 앞에, html 파트를 뒤에 붙인다 —
	 * multipart/alternative는 같은 내용을 두 벌로 담으므로 순서가 곧 우선순위다
	 * (패턴은 첫 매칭을 채택하므로 plain이 있으면 기존 동작이 그대로 유지되고,
	 * plain이 없거나 금액을 담지 않은 메일에서만 html이 실제로 쓰인다).
	 */
	private String getTextFromMultipart(Multipart multipart) throws Exception {
		StringBuilder plain = new StringBuilder();
		StringBuilder html = new StringBuilder();
		for (int i = 0; i < multipart.getCount(); i++) {
			BodyPart bodyPart = multipart.getBodyPart(i);
			if (bodyPart.isMimeType("text/plain")) {
				plain.append(bodyPart.getContent());
			} else if (bodyPart.isMimeType("text/html")) {
				html.append(bodyPart.getContent());
			} else if (bodyPart.getContent() instanceof Multipart nested) {
				// 중첩 파트는 이미 그 서브트리의 최선 텍스트다 — plain 쪽에 이어 붙인다.
				plain.append(getTextFromMultipart(nested));
			}
		}
		return plain.append(' ').append(html).toString();
	}
}
