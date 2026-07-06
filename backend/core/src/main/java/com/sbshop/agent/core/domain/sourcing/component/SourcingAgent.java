package com.sbshop.agent.core.domain.sourcing.component;

import com.sbshop.agent.core.application.sourcing.dto.ScrapedProductDto;

public interface SourcingAgent {
	boolean supports(String url);

	ScrapedProductDto scrape(String url);
}
