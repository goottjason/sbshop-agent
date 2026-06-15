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
public class CoupangApiExplorationTest {

	@Autowired
	private MarketCredentialRepository marketCredentialRepository;

	@Autowired
	private CoupangOrderApiClient coupangOrderApiClient;

	@Test
	public void exploreCoupangOrderApi() {
		MarketCredential cred = marketCredentialRepository.findByMarketType(MarketType.COUPANG)
			.orElseThrow(() -> new RuntimeException("Coupang credentials not found"));

		// Just fetch orders from the last 3 days to see the payload structure
		String fromDate = LocalDate.now().minusDays(3).format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
		String toDate = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));

		System.out.println("Fetching orders from " + fromDate + " to " + toDate);
		JsonNode response = coupangOrderApiClient.fetchOrders(
			cred.getClientId(), // vendorId is stored in clientId
			cred.getAccessKey(),
			cred.getSecretKey(),
			fromDate,
			toDate,
			"ACCEPT");

		System.out.println("========== RAW COUPANG API RESPONSE ==========");
		System.out.println(response.toPrettyString());
		System.out.println("==============================================");
	}
}
