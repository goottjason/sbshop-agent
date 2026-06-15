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

		credential.setClientId(command.getClientId());
		credential.setAccessKey(command.getAccessKey());
		credential.setSecretKey(command.getSecretKey());
		credential.setRedirectUri(command.getRedirectUri());

		MarketCredential saved = marketCredentialRepository.save(credential);
		return MarketCredentialDto.fromEntity(saved);
	}
}
