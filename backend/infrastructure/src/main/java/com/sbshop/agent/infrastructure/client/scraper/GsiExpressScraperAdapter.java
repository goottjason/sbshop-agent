package com.sbshop.agent.infrastructure.client.scraper;

import com.sbshop.agent.core.application.order.port.CustomsClearancePort;
import com.sbshop.agent.core.domain.order.Order;
import com.sbshop.agent.core.domain.order.enums.CustomsStatus;
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
	public Map<Long, CustomsStatus> verifyBulk(List<Order> orders) {
		Map<Long, CustomsStatus> resultMap = new HashMap<>();

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
				resultMap.put(order.getId(), CustomsStatus.PENDING);
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

			resultMap.put(order.getId(), CustomsStatus.PENDING);
		}

		String chkData = sb.toString().trim();
		if (chkData.isEmpty()) {
			return resultMap;
		}

		// 2. Perform POST request via Jsoup
		try {
			log.info("Sending bulk check to GSI Express for {} orders", orders.size());
			Document doc = Jsoup.connect(TARGET_URL)
				.data("action_type", "query")
				.data("chk_data", chkData)
				.userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
				.timeout(10000)
				.post();

			// 3. Parse result table
			// The table might not have a tbody tag, let's just select all 'tr' elements containing 'td'
			Elements rows = doc.select("tr");
			log.info("Scraped {} rows from GSI Express response", rows.size());

			for (Element row : rows) {
				Elements cols = row.select("td");
				if (cols.size() >= 4) {
					String rowText = row.text();
					log.info("Parsing row text: {}", rowText);

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

						boolean nameMatches = rowText.contains(recipientName);
						if (!nameMatches && ordererName != null && !ordererName.isBlank()) {
							nameMatches = rowText.contains(ordererName.trim());
						}

						// Check if row matches BOTH name and PCCC to correctly identify the specific order,
						// especially if multiple orders share the same PCCC.
						if (nameMatches && rowText.contains(pccc)) {
							log.info("Matched name (recipient={}, orderer={}) and PCCC {} for order ID {}",
								recipientName, ordererName, pccc, orderId);
							if (rowText.contains("정상")) {
								log.info("Status marked as VALID for order {}", orderId);
								resultMap.put(orderId, CustomsStatus.VALID);
							} else if (rowText.contains("전화번호가 일치하지 않습니다")) {
								log.info("Status marked as VALID_PHONE_MISMATCH for order {}", orderId);
								resultMap.put(orderId, CustomsStatus.VALID_PHONE_MISMATCH);
							} else if (rowText.contains("오류") || rowText.contains("불일치")) {
								log.info("Status marked as INVALID for order {}", orderId);
								resultMap.put(orderId, CustomsStatus.INVALID);
							} else {
								log.warn("Name/PCCC matched but result text unclear: {}", rowText);
							}
						}
					}
				}
			}

			// Add log if no rows were found
			if (rows.isEmpty() || resultMap.values().stream().allMatch(s -> s == CustomsStatus.PENDING)) {
				log.warn("Failed to find or match any rows. HTML body snippet: {}",
					doc.body().text().length() > 500 ? doc.body().text().substring(0, 500) : doc.body().text());
			}

		} catch (Exception e) {
			log.error("Failed to scrape GSI Express for customs clearance", e);
			// On failure, items remain PENDING.
		}

		return resultMap;
	}
}
