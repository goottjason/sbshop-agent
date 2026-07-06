package com.sbshop.agent.core.application.product.port;

import com.sbshop.agent.core.application.sourcing.dto.ScrapedProductDto;
import java.util.List;

public interface ProductInfoCrawlerPort {
	ScrapedProductDto crawlProductInfoAsDto(String url);

	List<ScrapedProductDto> crawlProducts(List<String> urls);
}
