package com.sbshop.agent.worker.service;

import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.order.service.LineItemShippingWriter;
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
import jakarta.mail.BodyPart;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.Store;
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
	private final LineItemShippingWriter shippingWriter;

	private final AtomicBoolean fetching = new AtomicBoolean(false);

	private final Set<String> amountParseFailuresReported = ConcurrentHashMap.newKeySet();

	public boolean fetchAndProcessEmails() {
		if (!fetching.compareAndSet(false, true)) {
			log.info("이메일 수집·처리가 이미 실행 중입니다 - 이번 호출은 스킵(중복 실행 방지, F-MISC-18)");
			return false;
		}
		try {
			if (properties.getAccounts() == null || properties.getAccounts().isEmpty()) {
				log.warn("IMAP 이메일 계정이 설정되지 않았습니다.");
				return true;
			}

			List<OrderLineItem> shipmentItems = orderLineItemRepository.findIherbItemsNeedingEmailProcessing();
			List<OrderLineItem> amountItems = orderLineItemRepository.findIherbItemsNeedingPurchaseAmount();
			if (shipmentItems.isEmpty() && amountItems.isEmpty()) {
				log.debug("이메일 처리가 필요한 iHerb 주문이 없습니다.");
				return true;
			}

			Map<EmailAccountProperties.Account, Set<String>> plan = buildSearchPlan(
				shipmentItems, amountItems, properties.getAccounts());
			log.info("이메일 검색 계획: 계정 {}곳, 주문 {}건", plan.size(),
				plan.values().stream().mapToInt(Set::size).sum());

			for (Map.Entry<EmailAccountProperties.Account, Set<String>> entry : plan.entrySet()) {
				searchAccountForOrderNos(entry.getKey(), entry.getValue());
			}
			return true;
		} finally {
			fetching.set(false);
		}
	}

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
		if (!isImapSearchable(orderNo)) {
			log.warn("이메일 검색 제외 — 비ASCII 구매주문번호(itemId={}, orderNo='{}')", item.getId(), orderNo);
			return;
		}
		String sourcingAccount = sourcingData.getSourcingAccount();
		for (EmailAccountProperties.Account target : resolveTargetAccounts(sourcingAccount, accounts)) {
			plan.computeIfAbsent(target, k -> new LinkedHashSet<>()).add(orderNo);
		}
	}

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

	private static String localPart(String email) {
		if (email == null || email.isBlank()) {
			return null;
		}
		int at = email.lastIndexOf('@');
		return at < 0 ? email : email.substring(0, at);
	}

	static boolean isImapSearchable(String value) {
		if (value == null || value.isBlank()) {
			return false;
		}
		return value.chars().allMatch(c -> c < 128);
	}

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

	private List<Folder> resolveSearchFolders(Store store, EmailAccountProperties.Account account) {
		List<Folder> holders = new ArrayList<>();
		try {
			for (Folder folder : store.getDefaultFolder().list("*")) {
				if ((folder.getType() & Folder.HOLDS_MESSAGES) == 0) {
					continue;
				}
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

	private static boolean isAllMailFolder(Folder folder) {
		return hasAttribute(folder, "\\All");
	}

	private static boolean isNonReceivingFolder(Folder folder) {
		if (hasAttribute(folder, "\\Sent") || hasAttribute(folder, "\\Drafts")) {
			return true;
		}
		return isNonReceivingFolderName(folder.getName());
	}

	private static final Set<String> NON_RECEIVING_FOLDER_NAMES = Set.of(
		"sent", "sent messages", "sent items", "보낸편지함", "보낸 편지함",
		"보낼편지함", "보낼 편지함", "drafts", "임시보관함");

	static boolean isNonReceivingFolderName(String name) {
		if (name == null) {
			return false;
		}
		return NON_RECEIVING_FOLDER_NAMES.contains(name.trim().toLowerCase());
	}

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

	private void processMatchedMessage(EmailAccountProperties.Account account, String orderNo, Message message)
		throws Exception {
		String subject = message.getSubject();
		if (subject == null) {
			return;
		}
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

	void processIherbShipment(OrderEmailParser.IherbShipmentData shipmentData) {
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

			boolean marketHasInvoice = currentStatus == ShippingStatus.DISPATCHED
				|| currentStatus == ShippingStatus.SHIPPED;

			if (marketHasInvoice) {
				boolean sameTracking = shipmentData.getTrackingNo().equals(existingTracking);
				if (sameTracking) {
					shippingWriter.promoteTrackingSourceToEmail(item);
					boolean synced = shippingWriter.marketHasTracking(item, shipmentData.getTrackingNo());
					if (synced) {
						log.info("iHerb 주문 {} 이미 배송 처리 및 마켓 동기화 완료 (tracking={}) - 스킵",
							shipmentData.getOrderNo(), shipmentData.getTrackingNo());
						continue;
					}
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

				ShippingCarrier carrier = mapCarrier(shipmentData.getCarrier());
				ShippingData currentShipping = item.getShippingData() != null
					? item.getShippingData() : ShippingData.builder().build();
				ShippingCarrier finalCarrier = (carrier != null && carrier != ShippingCarrier.ETC)
					? carrier : currentShipping.getShippingCarrier();
				shippingWriter.applyShipping(item, currentShipping.toBuilder()
					.trackingNo(shipmentData.getTrackingNo())
					.shippingCarrier(finalCarrier)
					.build(), TrackingSource.EMAIL);
				log.info("iHerb 주문 {} 송장 변경 감지(기존={} → 신규={}, 상태={}) - 마켓 수정 반영",
					shipmentData.getOrderNo(), existingTracking, shipmentData.getTrackingNo(), currentStatus);
				MarketShippingResult updResult = marketplaceShippingService.sendTrackingToMarketplace(item, true);
				handleMarketResult(item, updResult, shipmentData.getOrderNo(), "송장교정");
				continue;
			}

			ShippingCarrier carrier = mapCarrier(shipmentData.getCarrier());
			ShippingData currentShipping = item.getShippingData() != null
				? item.getShippingData() : ShippingData.builder().build();

			shippingWriter.applyShipping(item, currentShipping.toBuilder()
				.trackingNo(shipmentData.getTrackingNo())
				.shippingCarrier(carrier)
				.build(), TrackingSource.EMAIL);

			log.info("iHerb 발송 처리 완료: itemId={}, status={}, tracking={}, carrier={}",
				item.getId(), currentStatus, shipmentData.getTrackingNo(), carrier);

			if (currentStatus == ShippingStatus.PREPARING) {
				MarketShippingResult sendResult = marketplaceShippingService.sendTrackingToMarketplace(item, false);
				handleMarketResult(item, sendResult, shipmentData.getOrderNo(), "최초발송");
			} else {
				log.info("iHerb 주문 {} 상태({}) — 송장은 기록하고 마켓 전송은 생략(마켓 동기화가 진실 원본)",
					shipmentData.getOrderNo(), currentStatus);
			}
		}
	}

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
			shippingWriter.markTrackingAsSent(item);
			shippingWriter.markManualFixRequired(item);
			String tracking = item.getShippingData() != null ? item.getShippingData().getTrackingNo() : null;
			String market = marketTypeOf(item);
			actionLogService.record(ActionLogConstants.SHIPPING_UPDATE, market, ActionStatus.FAILED,
				"마켓(" + market + ") 상태 잠금으로 송장 전송 종결(재시도 중단, " + phase + "): iHerb주문 " + orderNo
					+ " — 실송장(" + tracking + ")은 DB 기록됨, 마켓 반영 불가. 사유: " + result.failureReason());
			log.warn("iHerb 주문 {} 마켓({}) 상태 잠금 - 전송 종결({}): {}",
				orderNo, market, phase, result.failureReason());
			return;
		}
		log.warn("iHerb 주문 {} 마켓 송장 전송 실패 - 미마킹(다음 사이클 재시도, {}): {}",
			orderNo, phase, result.failureReason());
	}

	void processIherbConfirmation(OrderEmailParser.IherbConfirmationData confirmData) {
		List<OrderLineItem> items = orderLineItemRepository.findBySourcingData_SourcingOrderNo(
			confirmData.getOrderNo());

		if (items.isEmpty()) {
			log.info("iHerb 주문번호 {}에 해당하는 라인아이템 없음 (이미 처리되었거나 미동기화)", confirmData.getOrderNo());
			return;
		}

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
				BigDecimal existingAmount = item.getSourcingData() != null
					? item.getSourcingData().getSourcingAmount() : null;
				if (existingAmount != null && existingAmount.compareTo(BigDecimal.ZERO) > 0) {
					log.info("iHerb 주문 {} 이미 실구매가 기록됨 ({}) - 스킵",
						confirmData.getOrderNo(), existingAmount);
					continue;
				}

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

	String getTextFromMessage(Message message) throws Exception {
		if (message.isMimeType("text/plain") || message.isMimeType("text/html")) {
			return message.getContent().toString();
		} else if (message.isMimeType("multipart/*")) {
			Multipart multipart = (Multipart)message.getContent();
			return getTextFromMultipart(multipart);
		}
		return "";
	}

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
				plain.append(getTextFromMultipart(nested));
			}
		}
		return plain.append(' ').append(html).toString();
	}
}
