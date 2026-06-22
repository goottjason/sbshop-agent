package com.sbshop.agent.infrastructure.client.customs;

import com.sbshop.agent.core.application.order.dto.CustomsVerificationResult;
import com.sbshop.agent.core.application.order.port.CustomsClearancePort;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.enums.CustomsStatus;
import com.sbshop.agent.core.domain.order.enums.VerifiedPerson;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class GsiExpressScraperAdapter implements CustomsClearancePort {

	private static final String TARGET_URL = "https://www.gsiexpress.com/pcc_chk.php";

	@Override
	public Map<Long, CustomsVerificationResult> verifyBulk(List<Order> orders) {
		Map<Long, CustomsVerificationResult> resultMap = new HashMap<>();

		if (orders == null || orders.isEmpty()) {
			return resultMap;
		}

		// 1. Build chk_data string payload
		// Format per row: 이름/통관고유부호/핸드폰번호/우편번호
		StringBuilder sb = new StringBuilder();
		for (Order order : orders) {
			String recipientName = order.getRecipientName();
			String ordererName = order.getOrdererName();
			String pccc = order.getCustomsData() != null ? order.getCustomsData().getCustomsClearanceNo() : null;
			String phone = order.getRecipientPhone();
			String zip = order.getZipcode();

			if (pccc == null || pccc.isBlank() || recipientName == null || recipientName.isBlank()) {
				resultMap.put(order.getId(), CustomsVerificationResult.pending());
				continue;
			}

			recipientName = recipientName.trim();
			pccc = pccc.trim().toUpperCase();
			phone = phone != null ? phone.trim() : "";
			zip = zip != null ? zip.trim() : "";

			// 수취인명으로 검사
			sb.append(recipientName).append("/")
				.append(pccc).append("/")
				.append(phone).append("/")
				.append(zip).append("\n");

			// 주문자명이 다르면 별도 행으로 추가 (둘 중 하나라도 통관되면 VALID)
			if (ordererName != null && !ordererName.isBlank() && !ordererName.trim().equals(recipientName)) {
				sb.append(ordererName.trim()).append("/")
					.append(pccc).append("/")
					.append(phone).append("/")
					.append(zip).append("\n");
			}

			resultMap.put(order.getId(), CustomsVerificationResult.pending());
		}

		String chkData = sb.toString().trim();
		if (chkData.isEmpty()) {
			return resultMap;
		}

		// 2. Perform POST request via Jsoup
		try {
			log.info("GSI Express에 {}건의 주문 벌크 검증 요청 중", orders.size());
			Document doc = Jsoup.connect(TARGET_URL)
				.data("action_type", "query")
				.data("chk_data", chkData)
				.userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
				.timeout(10000)
				.post();

			// 3. Parse result table
			Elements rows = doc.select("tr");
			log.info("GSI Express 응답에서 {}행 파싱 완료", rows.size());

			for (Element row : rows) {
				Elements cols = row.select("td");
				if (cols.size() >= 4) {
					String rowText = row.text();
					log.debug("행 텍스트 파싱: {}", rowText);

					for (Order order : orders) {
						String recipientName = order.getRecipientName();
						String ordererName = order.getOrdererName();
						String pccc = order.getCustomsData() != null ? order.getCustomsData().getCustomsClearanceNo()
							: null;
						Long orderId = order.getId();

						if (recipientName == null || pccc == null) {
							continue;
						}

						recipientName = recipientName.trim();
						pccc = pccc.trim().toUpperCase();

						// Track which person (recipient or orderer) matched this response row
						VerifiedPerson matchedPerson = null;
						boolean nameMatches = false;

						if (rowText.contains(recipientName)) {
							nameMatches = true;
							matchedPerson = VerifiedPerson.RECIPIENT;
						} else if (ordererName != null && !ordererName.isBlank()
							&& rowText.contains(ordererName.trim())) {
							nameMatches = true;
							matchedPerson = VerifiedPerson.ORDERER;
						}

						// Check if row matches BOTH name and PCCC to correctly identify the specific order
						if (nameMatches && rowText.contains(pccc)) {
							log.info("주문 ID {}에서 name={} 및 PCCC {} 매칭됨",
								orderId, matchedPerson, pccc);

						// 에러 메시지 패턴에 따라 통관 상태 결정
						// 우선순위: 납세의무자명/개인통관고유부호(1순위) > 전화번호(2순위) > 우편번호(3순위)
						CustomsStatus rowStatus = CustomsStatus.PENDING;
						if (rowText.contains("정상")) {
							rowStatus = CustomsStatus.VALID;
						} else if (rowText.contains("납세의무자명") || rowText.contains("개인통관고유부호가 존재하지 않습니다")) {
							rowStatus = CustomsStatus.INVALID_PCCC;
						} else if (rowText.contains("전화번호가 일치하지 않습니다")) {
							rowStatus = CustomsStatus.INVALID_PHONE;
						} else if (rowText.contains("우편번호가 일치하지 않습니다")) {
							rowStatus = CustomsStatus.INVALID_ZIPCODE;
						} else if (rowText.contains("오류") || rowText.contains("불일치")) {
							// 기타 불일치 에러는 통관번호 불일치로 처리
							rowStatus = CustomsStatus.INVALID_PCCC;
						}

							// Accumulate: only update if new status has higher priority
							// Priority: VALID(3) > VALID_PHONE_MISMATCH(2) > INVALID(1) > PENDING(0)
							CustomsVerificationResult current = resultMap.get(orderId);
							int currentPriority = current != null ? priority(current.getStatus()) : -1;
							int newPriority = priority(rowStatus);

							if (newPriority > currentPriority) {
								log.info("주문 {} 상태 업데이트: {} -> {} (매칭={})",
									orderId, current != null ? current.getStatus() : null, rowStatus, matchedPerson);
								resultMap.put(orderId, CustomsVerificationResult.of(rowStatus, matchedPerson));
							}
						}
					}
				}
			}

			// Log if no rows were found or all still PENDING
			if (rows.isEmpty() || resultMap.values().stream().allMatch(
				r -> r.getStatus() == CustomsStatus.PENDING)) {
				log.warn("행을 찾거나 매칭하지 못함. HTML 본문 일부: {}",
					doc.body().text().length() > 500 ? doc.body().text().substring(0, 500) : doc.body().text());
			}

		} catch (Exception e) {
			log.error("GSI Express 통관 검증 스크래핑 실패", e);
		}

		return resultMap;
	}

	// 통관 상태 우선순위 (높을수록 우선)
	// VALID(4) > INVALID_PHONE(3) > INVALID_ZIPCODE(2) > INVALID_PCCC(1) > PENDING(0)
	private int priority(CustomsStatus status) {
		if (status == null)
			return -1;
		switch (status) {
			case VALID:
				return 4;
			case INVALID_PHONE:
				return 3;
			case INVALID_ZIPCODE:
				return 2;
			case INVALID_PCCC:
				return 1;
			case PENDING:
				return 0;
			default:
				return 0;
		}
	}
}
