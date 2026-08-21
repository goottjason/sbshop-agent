package com.sbshop.agent.core.application.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.market.dto.MarketCredentialDto;
import com.sbshop.agent.core.application.market.dto.MarketCredentialSaveCommand;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * F-CRED-8: 마스킹된 응답으로 인해 프론트 폼이 기존 시크릿을 표시하지 못하므로,
 * 저장 시 비어 있는 시크릿 필드는 기존 값을 <b>유지</b>해야 한다(빈 값 제출로 저장된 시크릿이
 * 지워지면 안 됨). 새 값이 들어오면 갱신한다. 저장되는 값 자체는 불변이며 응답만 마스킹된다.
 */
@ExtendWith(MockitoExtension.class)
class MarketCredentialServiceSavePreservationTest {

	@Mock
	private MarketCredentialRepository repository;

	private MarketCredentialSaveCommand command(String clientId, String accessKey,
		String secretKey, String redirectUri) {
		MarketCredentialSaveCommand cmd = new MarketCredentialSaveCommand();
		cmd.setMarketType(MarketType.COUPANG);
		cmd.setClientId(clientId);
		cmd.setAccessKey(accessKey);
		cmd.setSecretKey(secretKey);
		cmd.setRedirectUri(redirectUri);
		return cmd;
	}

	private MarketCredential existing() {
		return MarketCredential.builder()
			.marketType(MarketType.COUPANG)
			.clientId("OLD_VENDOR")
			.accessKey("OLD_ACCESS")
			.secretKey("OLD_SECRET")
			.redirectUri("https://old/cb")
			.build();
	}

	@Test
	@DisplayName("빈 시크릿 필드로 저장하면 기존 accessKey·secretKey가 유지된다")
	void blankSecretsPreserveExistingValues() {
		MarketCredentialService service = new MarketCredentialService(repository);
		when(repository.findByMarketType(MarketType.COUPANG)).thenReturn(Optional.of(existing()));
		when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		// 프론트가 마스킹된 응답을 그대로 되보내는 시나리오: 시크릿 필드는 비어 있고 식별자만 변경
		service.saveCredential(command("NEW_VENDOR", "", "  ", "https://new/cb"));

		ArgumentCaptor<MarketCredential> captor = ArgumentCaptor.forClass(MarketCredential.class);
		verify(repository).save(captor.capture());
		MarketCredential saved = captor.getValue();
		assertThat(saved.getAccessKey()).isEqualTo("OLD_ACCESS");
		assertThat(saved.getSecretKey()).isEqualTo("OLD_SECRET");
		// 비시크릿 필드는 새 값으로 갱신
		assertThat(saved.getClientId()).isEqualTo("NEW_VENDOR");
		assertThat(saved.getRedirectUri()).isEqualTo("https://new/cb");
	}

	@Test
	@DisplayName("새 시크릿 값을 입력하면 갱신된다")
	void nonBlankSecretsUpdate() {
		MarketCredentialService service = new MarketCredentialService(repository);
		when(repository.findByMarketType(MarketType.COUPANG)).thenReturn(Optional.of(existing()));
		when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		service.saveCredential(command("V", "NEW_ACCESS", "NEW_SECRET", "https://r"));

		ArgumentCaptor<MarketCredential> captor = ArgumentCaptor.forClass(MarketCredential.class);
		verify(repository).save(captor.capture());
		assertThat(captor.getValue().getAccessKey()).isEqualTo("NEW_ACCESS");
		assertThat(captor.getValue().getSecretKey()).isEqualTo("NEW_SECRET");
	}

	@Test
	@DisplayName("저장 응답은 시크릿 평문 필드가 없고 설정 여부 플래그만 담는다(마스킹)")
	void saveResponseIsMasked() {
		MarketCredentialService service = new MarketCredentialService(repository);
		when(repository.findByMarketType(MarketType.COUPANG)).thenReturn(Optional.empty());
		when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		MarketCredentialDto dto = service.saveCredential(command("V", "A_PLAIN", "S_PLAIN", "https://r"));

		// getSecretKey()/getAccessKey()는 DTO에서 제거됨(평문 필드 부재로 마스킹 보증).
		assertThat(dto.isHasAccessKey()).isTrue();
		assertThat(dto.isHasSecretKey()).isTrue();
	}
}
