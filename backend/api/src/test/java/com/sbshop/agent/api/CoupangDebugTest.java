package com.sbshop.agent.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.infrastructure.client.coupang.CoupangOrderApiClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@SpringBootTest
public class CoupangDebugTest {
	@Autowired
	private MarketCredentialRepository marketCredentialRepository;
	@Autowired
	private CoupangOrderApiClient coupangOrderApiClient;

	@Test
	public void debugCoupangApi() {
		MarketCredential cred = marketCredentialRepository.findByMarketType(MarketType.COUPANG).orElseThrow();
		String fromDate = LocalDate.now().minusDays(30).format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
		String toDate = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
		String[] statuses = {"ACCEPT", "INSTRUCT", "DEPARTURE", "DELIVERING", "FINAL_DELIVERY", "NONE_TRACKING"};
		for (String status : statuses) {
			System.out.println("\n=== STATUS: " + status + " ===");
			try {
				// Instead of fetchOrders (which filters), let's call a lower-level method
				// Actually, let's modify CoupangOrderApiClient to expose the raw response
				JsonNode response = coupangOrderApiClient.fetchOrders(cred, fromDate, toDate, status);
				System.out.println("Result: " + response.toPrettyString());
				System.out.println("Size: " + response.size());
			} catch (Exception e) {
				System.out.println("Error: " + e.getMessage());
			}
		}
	}
}
