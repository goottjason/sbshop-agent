package com.sbshop.agent.core.application.market.marketplus;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.repository.*;
import com.sbshop.agent.core.domain.market.marketplus.MarketPlusTransmissionRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

class MarketPlusReadinessTest {
	final MarketCredentialRepository credentials = mock(MarketCredentialRepository.class);
	final MarketRegistrationRepository registrations = mock(MarketRegistrationRepository.class);
	final MarketPlusTransmissionRepository events = mock(MarketPlusTransmissionRepository.class);

	MarketPlusTransmissionService service(String gmarket, String auction) {
		return new MarketPlusTransmissionService(registrations, credentials, events, new ObjectMapper(),
			mock(PlatformTransactionManager.class), gmarket, auction);
	}

	@Test
	void missingMallAndSellerAccountsExposeOnlyConfigurationReasons() {
		when(credentials.findByMarketType(MarketType.CAFE24)).thenReturn(Optional.empty());
		var service = service("", "");
		assertThat(service.readiness().ready()).isFalse();
		assertThat(service.readiness().reasons()).hasSize(3).allMatch(r -> r.contains("설정"));
		assertThatThrownBy(service::requireSearchScope).isInstanceOf(IllegalStateException.class);
		verifyNoInteractions(registrations, events);
	}

	@Test
	void oneMissingSellerAccountCannotProducePartialSearchDisguisedAsComplete() {
		when(credentials.findByMarketType(MarketType.CAFE24)).thenReturn(Optional.of(MarketCredential.builder().marketType(MarketType.CAFE24)
			.clientId("private-mall").secretKey("not-for-output").build()));
		var service = service("private-seller", "");
		assertThat(service.readiness().reasons()).containsExactly("옥션 판매 계정의 연결 설정이 필요합니다.");
		assertThatThrownBy(service::requireSearchScope).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("옥션").hasMessageNotContaining("private").hasMessageNotContaining("not-for-output");
		verifyNoInteractions(registrations, events);
	}
}
