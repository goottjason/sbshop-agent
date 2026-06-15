package com.sbshop.agent.worker.service;

import com.sbshop.agent.core.application.order.port.CoupangOrderApiPort;
import com.sbshop.agent.core.application.order.port.SmartStoreOrderApiPort;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.worker.config.EmailAccountProperties;
import jakarta.mail.*;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
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
	private final MarketCredentialRepository credentialRepository;
	private final SmartStoreOrderApiPort smartStoreOrderApiPort;
	private final CoupangOrderApiPort coupangOrderApiPort;

	@Transactional
	public void fetchAndProcessEmails() {
		if (properties.getAccounts() == null || properties.getAccounts().isEmpty()) {
			log.warn("No email accounts configured for IMAP fetching.");
			return;
		}

		// 1. DB에서 이메일 처리가 필요한 iHerb 주문번호 조회
		List<OrderLineItem> items = orderLineItemRepository.findIherbItemsNeedingEmailProcessing();
		if (items.isEmpty()) {
			log.debug("이메일 처리가 필요한 iHerb 주문이 없습니다.");
			return;
		}

		// 2. 소싱 주문번호 추출 및 중복 제거
		Set<String> orderNos = new HashSet<>();
		for (OrderLineItem item : items) {
			String orderNo = item.getSourcingData() != null
				? item.getSourcingData().getSourcingOrderNo() : null;
			if (orderNo != null && !orderNo.isBlank()) {
				orderNos.add(orderNo);
			}
		}

		log.info("이메일 검색 대상 iHerb 주문번호 {}건: {}", orderNos.size(), orderNos);

		// 3. 각 주문번호별로 이메일 계정 순회하며 검색
		for (String orderNo : orderNos) {
			searchAndProcessForOrderNo(orderNo);
		}
	}

	/**
	 * 특정 iHerb 주문번호에 대해 이메일 검색 및 처리
	 */
	private void searchAndProcessForOrderNo(String orderNo) {
		for (EmailAccountProperties.Account account : properties.getAccounts()) {
			searchInAccountForOrderNo(account, orderNo);
		}
	}

	/**
	 * Gmail 호환: 특정 주문번호의 발송/확인 이메일을 검색하여 처리
	 * Gmail IMAP은 SubjectTerm을 지원하지 않으므로 최근 메일을 가져와서 필터링
	 */
	private void searchInAccountForOrderNo(EmailAccountProperties.Account account, String orderNo) {
		log.debug("IMAP 연결 시도: account={}, orderNo={}", account.getUsername(), orderNo);
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
			log.debug("IMAP 연결 성공: account={}", account.getUsername());

			Folder inbox = store.getFolder("INBOX");
			inbox.open(Folder.READ_ONLY);

			String shipmentSubject = "주문이 발송되었습니다 #" + orderNo;
			String confirmationSubject = "주문이 확인되었습니다 #" + orderNo;

			int totalMessages = inbox.getMessageCount();
			int start = Math.max(1, totalMessages - 199);
			log.debug("이메일 검색: account={}, orderNo={}, totalMessages={}, range={}~{}",
				account.getUsername(), orderNo, totalMessages, start, totalMessages);
			Message[] messages = inbox.getMessages(start, totalMessages);

			boolean shipmentFound = false;
			boolean confirmationFound = false;

			for (Message message : messages) {
				try {
					String subject = message.getSubject();
					if (subject == null)
						continue;

					// 발송 이메일 처리
					if (!shipmentFound && subject.contains(shipmentSubject)) {
						String body = getTextFromMessage(message);
						String from = message.getFrom() != null && message.getFrom().length > 0
							? message.getFrom()[0].toString() : account.getUsername();

						parser.parseIherbShipment(from, subject, body).ifPresent(shipmentData -> {
							log.info("iHerb 발송 이메일 발견: orderNo={}, tracking={}, account={}",
								shipmentData.getOrderNo(), shipmentData.getTrackingNo(), account.getUsername());
							processIherbShipment(shipmentData);
						});
						shipmentFound = true;
					}

					// 확인 이메일 처리
					if (!confirmationFound && subject.contains(confirmationSubject)) {
						String body = getTextFromMessage(message);

						parser.parseIherbConfirmation(subject, body).ifPresent(confirmData -> {
							log.info("iHerb 확인 이메일 발견: orderNo={}, amount={}, account={}",
								confirmData.getOrderNo(), confirmData.getTotalAmount(), account.getUsername());
							processIherbConfirmation(confirmData);
						});
						confirmationFound = true;
					}

					// 두 이메일 모두 찾았으면 조기 종료
					if (shipmentFound && confirmationFound)
						break;

				} catch (Exception e) {
					log.error("메일 처리 실패: orderNo={}, account={}", orderNo, account.getUsername(), e);
				}
			}

			inbox.close(false);
			store.close();
			log.debug("이메일 검색 완료: account={}, orderNo={}, shipmentFound={}, confirmationFound={}",
				account.getUsername(), orderNo, shipmentFound, confirmationFound);
		} catch (Exception e) {
			log.error("IMAP 연결 실패: account={}, orderNo={}, error={}", account.getUsername(), orderNo, e.getMessage(),
				e);
		}
	}

	// iHerb 발송 처리
	private void processIherbShipment(OrderEmailParser.IherbShipmentData shipmentData) {
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
					&& Boolean.TRUE.equals(item.getShippingData().getMarketplaceSynced());
				if (synced) {
					log.info("iHerb 주문 {} 이미 배송 처리 및 마켓 동기화 완료 (tracking={}) - 스킵",
						shipmentData.getOrderNo(), shipmentData.getTrackingNo());
					continue;
				}
				// 마켓 미동기화 건은 재시도
				log.info("iHerb 주문 {} 배송 처리됨但 마켓 미동기화 (tracking={}) - 재시도",
					shipmentData.getOrderNo(), shipmentData.getTrackingNo());
				ShippingCarrier carrier = mapCarrier(shipmentData.getCarrier());
				syncTrackingToMarketplace(item, carrier);
				continue;
			}

			// PURCHASED 상태인 경우에만 배송 처리
			if (currentStatus == ShippingStatus.PURCHASED) {
				ShippingCarrier carrier = mapCarrier(shipmentData.getCarrier());
				item.updateTrackingInfo(shipmentData.getTrackingNo(), carrier);
				item.updateShippingStatus(ShippingStatus.SHIPPED);
				orderLineItemRepository.save(item);

				log.info("iHerb 발송 처리 완료: itemId={}, tracking={}, carrier={}",
					item.getId(), shipmentData.getTrackingNo(), carrier);

				// 마켓플러스에 송장 전송
				syncTrackingToMarketplace(item, carrier);
			} else {
				log.info("iHerb 주문 {} 상태({})가 PURCHASED가 아니어서 배송 처리 스킵",
					shipmentData.getOrderNo(), currentStatus);
			}
		}
	}

	// 마켓플러스에 송장번호 전송
	private void syncTrackingToMarketplace(OrderLineItem item, ShippingCarrier carrier) {
		try {
			Order order = orderRepository.findById(item.getOrderId()).orElse(null);
			if (order == null) {
				log.warn("마켓 송장 전송 실패: 주문을 찾을 수 없습니다. orderId={}", item.getOrderId());
				return;
			}

			MarketCredential cred = credentialRepository.findByMarketType(order.getMarketType()).orElse(null);
			if (cred == null) {
				log.warn("마켓 송장 전송 실패: 인증 정보를 찾을 수 없습니다. marketType={}", order.getMarketType());
				return;
			}

			String trackingNo = item.getShippingData().getTrackingNo();
			String deliveryCompanyCode = mapCarrierToMarketCode(carrier);

			switch (order.getMarketType()) {
				case SMART_STORE -> {
					smartStoreOrderApiPort.shipOrder(
						cred.getClientId(), cred.getSecretKey(),
						order.getMarketOrderNo(), trackingNo, deliveryCompanyCode);
					log.info("스마트스토어 송장 전송 완료: order={}, tracking={}",
						order.getMarketOrderNo(), trackingNo);
				}
				case COUPANG -> {
					String vendorItemId = item.getMarketProductCode();
					if (vendorItemId != null && !vendorItemId.isEmpty()) {
						coupangOrderApiPort.shipOrder(
							cred.getClientId(), cred.getAccessKey(), cred.getSecretKey(),
							order.getMarketOrderNo(), vendorItemId, trackingNo, deliveryCompanyCode);
						log.info("쿠팡 송장 전송 완료: order={}, vendorItemId={}, tracking={}",
							order.getMarketOrderNo(), vendorItemId, trackingNo);
					} else {
						log.warn("쿠팡 송장 전송 실패: vendorItemId가 없습니다. order={}", order.getMarketOrderNo());
						return;
					}
				}
				default -> {
					log.debug("송장 전송 미지원 마켓: {}", order.getMarketType());
					return;
				}
			}

			// 마켓 동기화 성공 시 플래그 업데이트
			com.sbshop.agent.core.domain.order.vo.ShippingData currentShipping = item.getShippingData();
			com.sbshop.agent.core.domain.order.vo.ShippingData updatedShipping = (currentShipping != null
				? currentShipping : com.sbshop.agent.core.domain.order.vo.ShippingData.builder().build())
				.toBuilder()
				.marketplaceSynced(true)
				.build();
			item.updateShippingData(updatedShipping);
			orderLineItemRepository.save(item);

		} catch (Exception e) {
			log.error("마켓 송장 전송 실패: itemId={}, error={}", item.getId(), e.getMessage(), e);
		}
	}

	// 택배사 -> 마켓 코드 매핑
	private String mapCarrierToMarketCode(ShippingCarrier carrier) {
		if (carrier == null)
			return "CJGLS";
		return switch (carrier) {
			case CJ_LOGISTICS -> "CJGLS";
			case HANJIN -> "HANJIN";
			case KOREA_POST -> "EPOST";
			case LOTTE_LOGISTICS -> "LOTTE";
			case ROCKET -> "COUPANG";
			default -> "CJGLS";
		};
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
				com.sbshop.agent.core.domain.order.vo.SourcingData current = item.getSourcingData();
				com.sbshop.agent.core.domain.order.vo.SourcingData updated = (current != null ? current
					: com.sbshop.agent.core.domain.order.vo.SourcingData.builder().build())
					.toBuilder()
					.sourcingAmount(confirmData.getTotalAmount())
					.build();
				item.updateSourcingData(updated);
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
	public java.util.Optional<BigDecimal> findIherbConfirmationAmount(String orderNo) {
		if (properties.getAccounts() == null || properties.getAccounts().isEmpty()) {
			return java.util.Optional.empty();
		}

		for (EmailAccountProperties.Account account : properties.getAccounts()) {
			java.util.Optional<BigDecimal> result = searchConfirmationInAccount(account, orderNo);
			if (result.isPresent()) {
				return result;
			}
		}
		return java.util.Optional.empty();
	}

	/**
	 * Gmail 호환: 특정 주문번호의 확인 이메일을 검색하여 실구매가 반환
	 */
	private java.util.Optional<BigDecimal> searchConfirmationInAccount(
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

			// Gmail 호환: 최근 200건만 가져와서 제목 필터링
			int totalMessages = inbox.getMessageCount();
			int start = Math.max(1, totalMessages - 199);
			Message[] messages = inbox.getMessages(start, totalMessages);

			for (Message message : messages) {
				String subject = message.getSubject();
				if (subject != null && subject.contains(searchSubject)) {
					String body = getTextFromMessage(message);

					java.util.Optional<OrderEmailParser.IherbConfirmationData> parsed = parser
						.parseIherbConfirmation(subject, body);

					inbox.close(false);
					store.close();

					return parsed.flatMap(data -> {
						if (data.getTotalAmount() != null) {
							log.info("iHerb 주문 확인 메일 발견: orderNo={}, amount={}, account={}",
								orderNo, data.getTotalAmount(), account.getUsername());
						}
						return java.util.Optional.ofNullable(data.getTotalAmount());
					});
				}
			}

			inbox.close(false);
			store.close();
		} catch (Exception e) {
			log.debug("이메일 검색 실패 (account: {}): {}", account.getUsername(), e.getMessage());
		}
		return java.util.Optional.empty();
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
