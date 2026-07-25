package com.sbshop.agent.core.application.product.port;

import com.sbshop.agent.core.domain.product.enums.VendorType;

/**
 * 벤더별 재고/가격 크롤러. {@link StockCrawlerRouter}가 {@link #vendor()}로 URL/상품 벤더에 맞는
 * 구현체를 선택한다(iHerb=IHB 내부 API, Fortnum&amp;Mason=FTN Scrapling 서비스 등).
 */
public interface VendorAwareStockCrawler extends ProductStockCrawlerPort {

	VendorType vendor();
}
