package com.sbshop.agent.core.application.sourcing.port;

import com.sbshop.agent.core.application.sourcing.dto.ProductDetailDto;

public interface ProductDetailCrawlerPort {
	ProductDetailDto fetchDetail(String sourceUrl);
}
