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

	public StockCrawlerRouter(List<VendorAwareStockCrawler> crawlers) {
		this.byVendor = crawlers.stream()
			.collect(Collectors.toMap(VendorAwareStockCrawler::vendor, c -> c, (a, b) -> a));
	}

	public java.util.Set<VendorType> supportedVendors() {
		return java.util.Set.copyOf(byVendor.keySet());
	}

	public boolean supports(VendorType vendor) {
		return byVendor.containsKey(vendor);
	}

	public StockCheckResult checkStockWithDetails(VendorType vendor, String sourceUrl) {
		ProductStockCrawlerPort crawler = byVendor.get(vendor);
		if (crawler == null) {
			throw new IllegalStateException("재고 크롤러가 없는 소싱처다: vendor=" + vendor
				+ " — 다른 소싱처 크롤러로 대체하지 않는다(잘못된 재고 판정을 만든다). 지원 소싱처="
				+ byVendor.keySet());
		}
		return crawler.checkStockWithDetails(sourceUrl);
	}
}
