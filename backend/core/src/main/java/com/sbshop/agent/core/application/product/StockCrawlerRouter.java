package com.sbshop.agent.core.application.product;

import com.sbshop.agent.core.application.product.dto.StockCheckResult;
import com.sbshop.agent.core.application.product.port.ProductStockCrawlerPort;
import com.sbshop.agent.core.application.product.port.VendorAwareStockCrawler;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class StockCrawlerRouter {
	private final Map<VendorType, ProductStockCrawlerPort> byVendor;
	private final ProductStockCrawlerPort defaultCrawler;

	public StockCrawlerRouter(List<VendorAwareStockCrawler> crawlers) {
		this.byVendor = crawlers.stream()
			.collect(Collectors.toMap(VendorAwareStockCrawler::vendor, c -> c, (a, b) -> a));
		this.defaultCrawler = byVendor.getOrDefault(VendorType.IHB,
			crawlers.isEmpty() ? null : crawlers.get(0));
	}

	public StockCheckResult checkStockWithDetails(VendorType vendor, String sourceUrl) {
		ProductStockCrawlerPort crawler = byVendor.getOrDefault(vendor, defaultCrawler);
		if (crawler == null) {
			throw new IllegalStateException("등록된 재고 크롤러가 없습니다: vendor=" + vendor);
		}
		return crawler.checkStockWithDetails(sourceUrl);
	}
}
