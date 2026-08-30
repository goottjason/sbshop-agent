package com.sbshop.agent.infrastructure.client.sourcing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.pricing.VendorPricePolicyService;
import com.sbshop.agent.core.application.product.port.VendorAwareStockCrawler;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import com.sbshop.agent.infrastructure.client.fx.FxRateClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 스크래퍼 사이드카를 쓰는 소싱처별 재고 크롤러 등록.
 *
 * <p>소싱처를 하나 추가하려면 ① 사이드카에 스크래퍼를 만들고 ② 여기에 빈을 하나 더한다.
 * 빈으로 명시하는 이유는 <b>어떤 소싱처를 지원하는지가 코드에 드러나야</b> 하기 때문이다 —
 * {@code StockCrawlerRouter} 는 등록되지 않은 소싱처를 다른 크롤러로 대체하지 않고 실패시킨다(D-239).
 */
@Configuration
public class ScraplingCrawlerConfig {

	@Bean
	public VendorAwareStockCrawler fortnumStockCrawler(ObjectMapper objectMapper,
		@Value("${scraper.base-url:http://localhost:8099}")
		String baseUrl, VendorPricePolicyService vendorPricePolicyService, FxRateClient fxRateClient) {
		return new ScraplingSourcingClient(objectMapper, baseUrl, VendorType.FTN, vendorPricePolicyService, fxRateClient);
	}

	@Bean
	public VendorAwareStockCrawler vitabioticsStockCrawler(ObjectMapper objectMapper,
		@Value("${scraper.base-url:http://localhost:8099}")
		String baseUrl, VendorPricePolicyService vendorPricePolicyService, FxRateClient fxRateClient) {
		return new ScraplingSourcingClient(objectMapper, baseUrl, VendorType.VTB, vendorPricePolicyService, fxRateClient);
	}

	@Bean
	public VendorAwareStockCrawler ocadoStockCrawler(ObjectMapper objectMapper,
		@Value("${scraper.base-url:http://localhost:8099}")
		String baseUrl, VendorPricePolicyService vendorPricePolicyService, FxRateClient fxRateClient) {
		return new ScraplingSourcingClient(objectMapper, baseUrl, VendorType.OCD, vendorPricePolicyService, fxRateClient);
	}

	@Bean
	public VendorAwareStockCrawler costcoUkStockCrawler(ObjectMapper objectMapper,
		@Value("${scraper.base-url:http://localhost:8099}")
		String baseUrl, VendorPricePolicyService vendorPricePolicyService, FxRateClient fxRateClient) {
		return new ScraplingSourcingClient(objectMapper, baseUrl, VendorType.COK, vendorPricePolicyService, fxRateClient);
	}

	/**
	 * Tesco 는 운영서버 IP 가 도메인 전체에서 차단된다(홈페이지도 403). 스크래퍼는 등록해 두되
	 * 결과는 항상 {@code blocked} 로 돌아온다 — 재고를 건드리지 않고 실패로 남는다(D-239).
	 * 프록시를 붙이기 전까지 TES 98건은 자동 갱신 대상이 아니다.
	 */
	@Bean
	public VendorAwareStockCrawler tescoStockCrawler(ObjectMapper objectMapper,
		@Value("${scraper.base-url:http://localhost:8099}")
		String baseUrl, VendorPricePolicyService vendorPricePolicyService, FxRateClient fxRateClient) {
		return new ScraplingSourcingClient(objectMapper, baseUrl, VendorType.TES, vendorPricePolicyService, fxRateClient);
	}
}
