package com.sbshop.agent.core.application.market;

import com.sbshop.agent.core.application.market.dto.MarketCredentialDto;
import com.sbshop.agent.core.application.market.dto.MarketCredentialSaveCommand;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.market.repository.MarketCredentialRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MarketCredentialService {

	private final MarketCredentialRepository marketCredentialRepository;

	public List<MarketCredentialDto> getAllCredentials() {
		return marketCredentialRepository.findAll().stream()
			.map(MarketCredentialDto::fromEntity)
			.collect(Collectors.toList());
	}

	public MarketCredentialDto getCredential(MarketType marketType) {
		return marketCredentialRepository
			.findByMarketType(marketType)
			.map(MarketCredentialDto::fromEntity)
			.orElse(null);
	}

	@Transactional
	public MarketCredentialDto saveCredential(MarketCredentialSaveCommand command) {
		MarketCredential credential = marketCredentialRepository
			.findByMarketType(command.getMarketType())
			.orElseGet(
				() -> MarketCredential.builder().marketType(command.getMarketType()).build());

		// 식별자·리다이렉트는 그대로 반영(마스킹 대상 아님).
		credential.setClientId(command.getClientId());
		credential.setRedirectUri(command.getRedirectUri());
		// F-CRED-8: 응답이 마스킹되어 프론트 폼이 기존 시크릿을 표시하지 못하므로, 저장 시 비어 있는
		// 시크릿 필드(accessKey·secretKey)는 기존 값을 유지한다(빈 값 제출로 저장된 시크릿이 지워지면 안 됨).
		// 새 값이 입력된 경우에만 갱신.
		if (isPresent(command.getAccessKey())) {
			credential.setAccessKey(command.getAccessKey());
		}
		if (isPresent(command.getSecretKey())) {
			credential.setSecretKey(command.getSecretKey());
		}

		MarketCredential saved = marketCredentialRepository.save(credential);
		return MarketCredentialDto.fromEntity(saved);
	}

	private static boolean isPresent(String value) {
		return value != null && !value.isBlank();
	}
}
