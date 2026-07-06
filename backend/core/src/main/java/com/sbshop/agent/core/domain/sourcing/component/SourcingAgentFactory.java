package com.sbshop.agent.core.domain.sourcing.component;

import com.sbshop.agent.core.application.sourcing.dto.ScrapedProductDto;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SourcingAgentFactory {

	private final List<SourcingAgent> agents;

	public SourcingAgent getAgentByUrl(String url) {
		return agents.stream()
				.filter(agent -> agent.supports(url))
				.findFirst()
				.orElseThrow(() -> new IllegalArgumentException("지원하지 않는 소싱 URL입니다: " + url));
	}

	public ScrapedProductDto scrapeProduct(String url) {
		return getAgentByUrl(url).scrape(url);
	}
}
