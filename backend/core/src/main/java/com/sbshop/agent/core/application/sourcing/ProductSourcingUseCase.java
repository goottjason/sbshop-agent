package com.sbshop.agent.core.application.sourcing;

import com.sbshop.agent.core.application.product.port.ProductInfoCrawlerPort;
import com.sbshop.agent.core.application.sourcing.dto.ScrapedProductDto;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductSourcingUseCase {

	private final ProductInfoCrawlerPort productInfoCrawlerPort;

	public List<ScrapedProductDto> sourceFromIherb(List<String> urls) {
		log.info("iHerb 소싱 시작: {}개 URL", urls.size());
		return productInfoCrawlerPort.crawlProducts(urls);
	}
}
