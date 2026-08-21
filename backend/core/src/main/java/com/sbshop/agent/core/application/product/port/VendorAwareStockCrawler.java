package com.sbshop.agent.core.application.product.port;

import com.sbshop.agent.core.domain.product.enums.VendorType;

public interface VendorAwareStockCrawler extends ProductStockCrawlerPort {
	VendorType vendor();
}
