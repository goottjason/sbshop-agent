package com.sbshop.agent.core.application.market.marketplus;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.repository.*;
import com.sbshop.agent.core.domain.market.marketplus.MarketPlusTransmissionRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.*;

class MarketPlusStorageRetryTest {
	@Test
	void databaseFailureIsRetryableButWrongSellerIsNot() {
		var credentials = mock(MarketCredentialRepository.class);
		var registrations = mock(MarketRegistrationRepository.class);
		var manager = mock(PlatformTransactionManager.class);
		when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
		when(credentials.findByMarketType(MarketType.CAFE24)).thenReturn(
			Optional.of(MarketCredential.builder().marketType(MarketType.CAFE24).clientId("testmall").build()));
		when(registrations.findIdentifierCandidates(MarketType.CAFE24, "10186"))
			.thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private connection details"));
		var service = new MarketPlusTransmissionService(registrations, credentials,
			mock(MarketPlusTransmissionRepository.class), new ObjectMapper(), manager, "seller-g", "seller-a");
		var at = Instant.parse("2026-09-06T07:02:00Z");
		var batch = new MarketPlusTransmissionService.Batch(1, "LIVE_CHROME_MARKETPLUS", "CURRENT_PAGE", "testmall", 1,
			at, List.of(
				new MarketPlusTransmissionService.Row(MarketType.GMARKET, "seller-g", "10186", "P0000PBU", "3490115053",
					"상품수정", "FAILURE", "[실패] 확인 필요", at, at),
				new MarketPlusTransmissionService.Row(MarketType.GMARKET, "other-seller", "10186", "P0000PBU",
					"3490115053", "상품수정", "FAILURE", "[실패] 확인 필요", at, at)));
		var result = service.ingest(batch, "admin");
		assertThat(result.rejected()).isEqualTo(2);
		assertThat(result.items().get(0).retryable()).isTrue();
		assertThat(result.items().get(1).retryable()).isFalse();
		assertThat(result.items().toString()).doesNotContain("private connection details");
	}
}
