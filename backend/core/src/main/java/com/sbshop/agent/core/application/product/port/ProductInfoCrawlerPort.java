package com.sbshop.agent.core.application.product.port;

import com.sbshop.agent.core.application.sourcing.dto.ScrapedProductDto;
import com.sbshop.agent.core.application.sourcing.dto.SourcingCrawlResult;
import java.util.List;

public interface ProductInfoCrawlerPort {
	ScrapedProductDto crawlProductInfoAsDto(String url);

	SourcingCrawlResult crawlProducts(List<String> urls);
}
