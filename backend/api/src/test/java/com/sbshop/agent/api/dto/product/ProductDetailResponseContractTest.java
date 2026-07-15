package com.sbshop.agent.api.dto.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.enums.MeasureUnit;
import com.sbshop.agent.core.domain.product.enums.ProductCategory;
import com.sbshop.agent.core.domain.product.enums.StockStatus;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import com.sbshop.agent.core.domain.product.vo.LogisticsInfo;
import com.sbshop.agent.core.domain.product.vo.PriceInfo;
import com.sbshop.agent.core.domain.product.vo.ProductSpec;
import com.sbshop.agent.core.domain.product.vo.SourcingInfo;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * F-PROD-6: ProductDetailResponse가 도메인 VO(PriceInfo/LogisticsInfo/ProductSpec/SourcingInfo)를
 * 직접 노출하지 않도록 DTO로 평탄화/래핑하되, GET /products/{id}의 JSON 계약(중첩 구조·필드명)은
 * 그대로 보존한다.
 */
@ExtendWith(MockitoExtension.class)
class ProductDetailResponseContractTest {

	// Spring Boot 기본 ObjectMapper와 동일하게 jsr310(LocalDate ISO 문자열) 모듈을 등록해
	// 실제 직렬화 형태(GET /products/{id} 응답)를 그대로 재현한다.
	private final ObjectMapper mapper =
		com.fasterxml.jackson.databind.json.JsonMapper.builder()
			.findAndAddModules()
			.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
			.build();

	@Mock
	private Product product;

	private void stubProduct() {
		when(product.getId()).thenReturn(7L);
		when(product.getSbCode()).thenReturn("SB-7");
		when(product.getBrand()).thenReturn("BRD");
		when(product.getProductName()).thenReturn("상품");
		when(product.getBaseName()).thenReturn("base");
		when(product.getOriginalName()).thenReturn("orig");
		when(product.getCategory()).thenReturn(ProductCategory.SUPPLEMENT);
		when(product.getVendor()).thenReturn(VendorType.IHB);
		when(product.getPriceInfo()).thenReturn(PriceInfo.builder()
			.costPrice(new BigDecimal("1000"))
			.exchangeRate(new BigDecimal("1350.00"))
			.deliveryFee(new BigDecimal("3000"))
			.marginRate(new BigDecimal("20.00"))
			.salePrice(new BigDecimal("15000"))
			.build());
		when(product.getLogisticsInfo()).thenReturn(LogisticsInfo.builder()
			.stock(999)
			.weight(new BigDecimal("1.50"))
			.bundleQuantity(2)
			.build());
		when(product.getProductSpec()).thenReturn(ProductSpec.builder()
			.barcode("BC-1")
			.capacity(new BigDecimal("100.00"))
			.measureUnit(MeasureUnit.CAPSULE)
			.build());
		when(product.getSourcingInfo()).thenReturn(SourcingInfo.builder()
			.vendor(VendorType.IHB)
			.sourceUrl("https://iherb.com/x")
			.manufacturer("MFR")
			.origin("USA")
			.hsCode("HS-1")
			.build());
		when(product.getSourceImages()).thenReturn(List.of("s1"));
		when(product.getHostedImages()).thenReturn(List.of("h1"));
		when(product.getSearchKeywords()).thenReturn("kw");
		when(product.getDetailHtml()).thenReturn("<p>d</p>");
		when(product.getMemo()).thenReturn("memo");
		when(product.getStockStatus()).thenReturn(StockStatus.IN_STOCK);
		when(product.getRestockDate()).thenReturn(LocalDate.of(2026, 7, 15));
	}

	@Test
	@DisplayName("F-PROD-6: 응답 record가 도메인 VO 타입을 직접 컴포넌트로 노출하지 않는다")
	void doesNotExposeDomainVoTypesDirectly() {
		List<Class<?>> forbidden =
			List.of(PriceInfo.class, LogisticsInfo.class, ProductSpec.class, SourcingInfo.class);
		for (RecordComponent rc : ProductDetailResponse.class.getRecordComponents()) {
			assertThat(forbidden)
				.as("컴포넌트 %s 가 도메인 VO %s 를 직접 노출", rc.getName(), rc.getType().getSimpleName())
				.doesNotContain(rc.getType());
		}
	}

	@Test
	@DisplayName("F-PROD-6: JSON 계약(중첩 구조·필드명) 보존 — 프론트 소비 형태 그대로")
	void preservesJsonContract() throws Exception {
		stubProduct();
		JsonNode json = mapper.valueToTree(ProductDetailResponse.from(product));

		// 최상위 flat 필드
		assertThat(json.get("id").asLong()).isEqualTo(7L);
		assertThat(json.get("sbCode").asText()).isEqualTo("SB-7");
		assertThat(json.get("brand").asText()).isEqualTo("BRD");
		assertThat(json.get("productName").asText()).isEqualTo("상품");
		assertThat(json.get("baseName").asText()).isEqualTo("base");
		assertThat(json.get("originalName").asText()).isEqualTo("orig");
		assertThat(json.get("category").asText()).isEqualTo("SUPPLEMENT");
		assertThat(json.get("vendor").asText()).isEqualTo("IHB");
		assertThat(json.get("searchKeywords").asText()).isEqualTo("kw");
		assertThat(json.get("detailHtml").asText()).isEqualTo("<p>d</p>");
		assertThat(json.get("memo").asText()).isEqualTo("memo");
		assertThat(json.get("stockStatus").asText()).isEqualTo("IN_STOCK");
		assertThat(json.get("restockDate").asText()).isEqualTo("2026-07-15");
		assertThat(json.get("sourceImages").isArray()).isTrue();
		assertThat(json.get("sourceImages").get(0).asText()).isEqualTo("s1");
		assertThat(json.get("hostedImages").get(0).asText()).isEqualTo("h1");

		// priceInfo: {costPrice, exchangeRate, deliveryFee, marginRate, salePrice}
		JsonNode price = json.get("priceInfo");
		assertThat(price.get("costPrice").decimalValue()).isEqualByComparingTo("1000");
		assertThat(price.get("exchangeRate").decimalValue()).isEqualByComparingTo("1350.00");
		assertThat(price.get("deliveryFee").decimalValue()).isEqualByComparingTo("3000");
		assertThat(price.get("marginRate").decimalValue()).isEqualByComparingTo("20.00");
		assertThat(price.get("salePrice").decimalValue()).isEqualByComparingTo("15000");

		// logisticsInfo: {stock, weight, bundleQuantity}
		JsonNode logi = json.get("logisticsInfo");
		assertThat(logi.get("stock").asInt()).isEqualTo(999);
		assertThat(logi.get("weight").decimalValue()).isEqualByComparingTo("1.50");
		assertThat(logi.get("bundleQuantity").asInt()).isEqualTo(2);

		// productSpec: {barcode, capacity, measureUnit}
		JsonNode spec = json.get("productSpec");
		assertThat(spec.get("barcode").asText()).isEqualTo("BC-1");
		assertThat(spec.get("capacity").decimalValue()).isEqualByComparingTo("100.00");
		assertThat(spec.get("measureUnit").asText()).isEqualTo("CAPSULE");

		// sourcingInfo: {vendor, sourceUrl, manufacturer, origin, hsCode}
		JsonNode sourcing = json.get("sourcingInfo");
		assertThat(sourcing.get("vendor").asText()).isEqualTo("IHB");
		assertThat(sourcing.get("sourceUrl").asText()).isEqualTo("https://iherb.com/x");
		assertThat(sourcing.get("manufacturer").asText()).isEqualTo("MFR");
		assertThat(sourcing.get("origin").asText()).isEqualTo("USA");
		assertThat(sourcing.get("hsCode").asText()).isEqualTo("HS-1");
	}
}
