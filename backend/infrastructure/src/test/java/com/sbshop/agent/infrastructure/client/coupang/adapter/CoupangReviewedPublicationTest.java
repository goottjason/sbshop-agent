package com.sbshop.agent.infrastructure.client.coupang.adapter;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sbshop.agent.core.config.MarketRegistrationDefaults;
import com.sbshop.agent.core.domain.market.sync.MarketTransferFailure;
import com.sbshop.agent.core.domain.market.client.dto.MarketPublishContext;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.enums.*;
import com.sbshop.agent.core.domain.product.vo.*;
import com.sbshop.agent.infrastructure.client.coupang.client.CoupangRestClient;
import com.sbshop.agent.infrastructure.client.coupang.dto.CoupangProductPayload;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;

class CoupangReviewedPublicationTest {
	final ObjectMapper mapper = new ObjectMapper();
	final CoupangRestClient rest = mock(CoupangRestClient.class);
	final MarketRegistrationDefaults defaults = mock(MarketRegistrationDefaults.class);
	final Product product = mock(Product.class);
	final CoupangReviewedPublication publication = new CoupangReviewedPublication(rest, mapper, defaults);
	final String base = CoupangReviewedPublication.BASE;
	final String vendor = CoupangProductPayload.ShippingAccount.legacyDefaults().vendorId();
	final String metaPath = "/v2/providers/seller_api/apis/api/v1/marketplace/meta/category-related-metas/display-category-codes/73199";
	ObjectNode meta, returns, outbound;

	@BeforeEach void setup() throws Exception {
        when(rest.resolveVendorId()).thenReturn(vendor);when(product.getSbCode()).thenReturn("SB-123");when(product.getProductName()).thenReturn("Brand Product 250ml 2개");when(product.getBaseName()).thenReturn("Product");when(product.getBrand()).thenReturn("Brand");
        when(product.getStockStatus()).thenReturn(StockStatus.IN_STOCK);when(product.getSalesQuantity()).thenReturn(300);
        when(product.getDetailHtml()).thenReturn("<p>실제 상세</p>");when(product.getHostedImages()).thenReturn(List.of("https://example.com/main.jpg"));
        var logistics=mock(LogisticsInfo.class);when(logistics.getBundleQuantity()).thenReturn(2);when(product.getLogisticsInfo()).thenReturn(logistics);
        when(product.getProductSpec()).thenReturn(ProductSpec.builder().capacity(new BigDecimal("250.5")).measureUnit(MeasureUnit.ML).barcode("1234567890123").build());
        when(product.getSourcingInfo()).thenReturn(SourcingInfo.builder().origin("US").manufacturer("Maker").build());
        when(defaults.getCoupangOutboundShippingPlaceCode()).thenReturn("12345");when(defaults.getCoupangReturnCenterCode()).thenReturn("67890");when(defaults.getCoupangDeliveryChargeOnReturn()).thenReturn(15000);
        when(rest.requestWithBody(eq("POST"),eq("/v2/providers/openapi/apis/api/v1/categorization/predict"),any())).thenReturn("{\"code\":\"SUCCESS\",\"data\":{\"predictedCategoryId\":73199,\"predictedCategoryName\":\"서버 추천 카테고리\"}}");
        meta=(ObjectNode)mapper.readTree("""
          {"isAllowSingleItem":false,"attributes":[
            {"attributeTypeName":"수량","required":"MANDATORY","dataType":"NUMBER","inputType":"INPUT","usableUnits":["개"],"groupNumber":"NONE","exposed":"EXPOSED"},
            {"attributeTypeName":"개당 용량","required":"MANDATORY","dataType":"NUMBER","inputType":"SELECT","inputValues":["250.5ml"],"usableUnits":["ml"],"groupNumber":"NONE","exposed":"EXPOSED"}],
          "noticeCategories":[{"noticeCategoryName":"식품","noticeCategoryDetailNames":[{"noticeCategoryDetailName":"제품명","required":"MANDATORY"},{"noticeCategoryDetailName":"원산지","required":"MANDATORY"}]}],
          "certifications":[{"certificationType":"NOT_REQUIRED","required":"OPTIONAL"}],"requiredDocumentNames":[],"allowedOfferConditions":["NEW"]}
          """);
        outbound=(ObjectNode)mapper.readTree("{\"content\":[{\"outboundShippingPlaceCode\":12345,\"usable\":true,\"placeAddresses\":[{\"addressType\":\"OVERSEA\",\"countryCode\":\"US\"}]}]}");
        returns=(ObjectNode)mapper.readTree("{\"code\":200,\"message\":\"SUCCESS\",\"data\":{\"content\":[{\"returnCenterCode\":\"67890\",\"usable\":true,\"shippingPlaceName\":\"현재 반품지\",\"placeAddresses\":[{\"countryCode\":\"KR\",\"companyContactNumber\":\"02-000-0000\",\"returnZipCode\":\"00000\",\"returnAddress\":\"현재 서버 주소\",\"returnAddressDetail\":\"주소 상세\"}]}]}}");
        ((ObjectNode)returns.path("data").path("content").get(0)).put("vendorId",vendor);publishConfiguration();
    }

	void publishConfiguration(){when(rest.requestWithBody("GET",metaPath,null)).thenReturn(envelope(meta));when(rest.requestWithBody("GET","/v2/providers/marketplace_openapi/apis/api/v2/vendor/shipping-place/outbound?placeCodes=12345",null)).thenReturn(outbound.toString());when(rest.requestWithBody("GET","/v2/providers/openapi/apis/api/v5/vendors/"+vendor+"/returnShippingCenters?pageNum=1&pageSize=50",null)).thenReturn(returns.toString());}

	String envelope(JsonNode data) {
		return mapper.createObjectNode().put("code", "SUCCESS").set("data", data).toString();
	}

	MarketPublishContext context() {
		return new MarketPublishContext(null, null, null, List.of(), Map.of(),
			Map.of("certifications", Map.of("NOT_REQUIRED", "")));
	}

	ObjectNode prepared() {
		return read(publication.prepare(product, new BigDecimal("12300"), context()).payload());
	}

	ObjectNode read(String text) {
		try {
			return (ObjectNode)mapper.readTree(text);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	ObjectNode actual(ObjectNode prepared, String status) {
		ObjectNode actual = prepared.deepCopy().put("sellerProductId", 123).put("statusName", status);
		((ObjectNode)actual.path("items").get(0)).put("vendorItemId", 456).put("sellerProductItemId", 789);
		return actual;
	}

	void publishProduct(ObjectNode actual,int price,int quantity,boolean onSale){when(rest.requestWithBody("GET",base+"/123",null)).thenReturn(envelope(actual));when(rest.requestWithBody("GET","/v2/providers/seller_api/apis/api/v1/marketplace/vendor-items/456/inventories",null)).thenReturn("{\"code\":\"SUCCESS\",\"data\":{\"sellerItemId\":456,\"salePrice\":"+price+",\"amountInStock\":"+quantity+",\"onSale\":"+onSale+"}}");}

	@Test
	void preparesCurrentAccountResourcesAndExactReviewedProductFactsWithoutPublishing() {
		var review = publication.prepare(product, new BigDecimal("12300"), context());
		JsonNode p = read(review.payload()), item = p.path("items").get(0);
		assertThat(item.path("maximumBuyCount").intValue()).isEqualTo(300);
		assertThat(item.path("unitCount").intValue()).isEqualTo(2);
		assertThat(item.path("salePrice").intValue()).isEqualTo(12300);
		assertThat(item.path("attributes").toString()).contains("250.5ml", "2개");
		assertThat(p.path("returnAddress").asText()).isEqualTo("현재 서버 주소");
		assertThat(p.path("companyContactNumber").asText()).isEqualTo("02-000-0000");
		assertThat(p.path("requested").booleanValue()).isTrue();
		assertThat(review.shippingSummary()).containsEntry("반품비", "15000원").containsEntry("반품 주소", "현재 서버 주소 주소 상세");
		verify(rest, never()).requestWithBody(eq("POST"), eq(base), any());
	}

	@Test
	void frozenSubmitUsesGuardAndOnePostWithoutRebuildingOrTrustingAnErrorReceipt() {
		var frozen = prepared();
		clearInvocations(rest);
		when(rest.requestWithBody(eq("POST"), eq(base), any())).thenReturn("{\"code\":\"SUCCESS\",\"data\":123}");
		var guard = mock(Runnable.class);
		assertThat(publication.submit(product, UUID.randomUUID().toString(), frozen.toString(), guard))
			.containsEntry("sellerProductId", "123");
		var order = inOrder(guard, rest);
		order.verify(guard).run();
		order.verify(rest).requestWithBody("POST", base, frozen);
		verify(rest, never()).requestWithBody(eq("GET"), anyString(), any());
		when(rest.requestWithBody(eq("POST"), eq(base), any()))
			.thenReturn("{\"code\":\"ERROR\",\"message\":\"bad\",\"data\":123}");
		assertThatThrownBy(() -> publication.submit(product, UUID.randomUUID().toString(), frozen.toString(), () -> {}))
			.isInstanceOf(MarketTransferFailure.class);
	}

	@Test
	void callbackAndChangedVendorStopPostWithoutExceptionWrapping() {
		var frozen = prepared();
		var abort = new IllegalStateException("lease lost");
		assertThatThrownBy(() -> publication.submit(product, UUID.randomUUID().toString(), frozen.toString(), () -> {
			throw abort;
		})).isSameAs(abort);
		assertThatThrownBy(() -> publication.submit(product, UUID.randomUUID().toString(), frozen.toString(),
			() -> when(rest.resolveVendorId()).thenReturn("A00000000"))).hasMessageContaining("계정");
		verify(rest, never()).requestWithBody(eq("POST"), eq(base), any());
	}

	@Test
	void onlyApprovedExactFieldsAndLiveInventoryReturnVerifiedOptionIdentity() {
		var frozen = prepared();
		var actual = actual(frozen, "승인완료");
		publishProduct(actual, 12300, 300, true);
		var result = publication.read("123", "SB-123", frozen.toString());
		assertThat(result.verified()).isTrue();
		assertThat(result.identifiers()).containsEntry("vendorItemId", "456");
		publishProduct(actual, 12300, 299, true);
		assertThat(publication.read("123", "SB-123", frozen.toString()).verified()).isFalse();
		actual.put("displayProductName", "wrong");
		publishProduct(actual, 12300, 300, true);
		assertThat(publication.read("123", "SB-123", frozen.toString()).detail()).contains("displayProductName");
	}

	@Test
	void pendingAndRejectedNeverConfirmEvenIfRequestedFieldsMatch() {
		var frozen = prepared();
		for (String status : List.of("심사중", "승인대기중")) {
			publishProduct(actual(frozen, status), 12300, 300, true);
			var result = publication.read("123", "SB-123", frozen.toString());
			assertThat(result.verified()).isFalse();
			assertThat(result.approvalPending()).isTrue();
			assertThat(result.identifiers()).containsOnlyKeys("sellerProductId");
		}
		publishProduct(actual(frozen, "승인반려"), 12300, 300, true);
		var rejected = publication.read("123", "SB-123", frozen.toString());
		assertThat(rejected.verified()).isFalse();
		assertThat(rejected.approvalPending()).isFalse();
		assertThat(rejected.detail()).contains("승인반려");
	}

	@Test
	void wrongSbOrExtraOptionCannotBecomeARegistrationLink() {
		var frozen = prepared();
		var actual = actual(frozen, "승인완료");
		((ObjectNode)actual.path("items").get(0)).put("externalVendorSku", "other");
		publishProduct(actual, 12300, 300, true);
		assertThatThrownBy(() -> publication.read("123", "SB-123", frozen.toString())).hasMessageContaining("SB코드");
		actual = actual(frozen, "승인완료");
		((ArrayNode)actual.path("items")).add(actual.path("items").get(0).deepCopy());
		publishProduct(actual, 12300, 300, true);
		assertThatThrownBy(() -> publication.read("123", "SB-123", frozen.toString())).hasMessageContaining("단일 옵션");
	}

	@Test void outOfStockPublishesAndVerifiesExplicitZeroRatherThanConfigured300() {
        when(product.getStockStatus()).thenReturn(StockStatus.OUT_OF_STOCK);var review=publication.prepare(product,new BigDecimal("12300"),context());assertThat(review.quantity()).isZero();var frozen=read(review.payload());publishProduct(actual(frozen,"승인완료"),12300,0,false);assertThat(publication.read("123","SB-123",frozen.toString()).verified()).isTrue();
    }

	@Test
	void missingOrAmbiguousActualRequiredFactsDoNotFallBackToFirstOptionOrDetailedReference() {
		((ObjectNode)meta.path("attributes").get(1)).put("attributeTypeName", "알 수 없는 필수 옵션");
		publishConfiguration();
		assertThatThrownBy(() -> prepared()).hasMessageContaining("필수 옵션");
		((ArrayNode)meta.path("attributes")).removeAll();
		((ObjectNode)meta.path("noticeCategories").get(0).path("noticeCategoryDetailNames").get(0))
			.put("noticeCategoryDetailName", "소비기한");
		publishConfiguration();
		assertThatThrownBy(() -> prepared()).hasMessageContaining("소비기한");
	}

	@Test void missingCategoryDoesNotFallBackAndWrongAccountResourceBlocksPreparation() {
        when(rest.requestWithBody(eq("POST"),eq("/v2/providers/openapi/apis/api/v1/categorization/predict"),any())).thenReturn("{\"code\":\"ERROR\",\"data\":{\"predictedCategoryId\":73199}}");assertThatThrownBy(()->prepared()).isInstanceOf(MarketTransferFailure.class);
        setupRecommendation();((ObjectNode)outbound.path("content").get(0)).put("usable",false);publishConfiguration();assertThatThrownBy(()->prepared()).hasMessageContaining("출고지");
    }

	void setupRecommendation(){when(rest.requestWithBody(eq("POST"),eq("/v2/providers/openapi/apis/api/v1/categorization/predict"),any())).thenReturn("{\"code\":\"SUCCESS\",\"data\":{\"predictedCategoryId\":73199,\"predictedCategoryName\":\"서버 추천\"}}");}

	@Test
	void mandatoryDocumentCannotBeInventedAndCdnRewriteCannotPretendExactImageMatch() {
		((ArrayNode)meta.path("requiredDocumentNames")).addObject().put("required", "MANDATORY_OVERSEAS_PURCHASED")
			.put("templateName", "인보이스");
		publishConfiguration();
		assertThatThrownBy(() -> prepared()).hasMessageContaining("인보이스");
		((ArrayNode)meta.path("requiredDocumentNames")).removeAll();
		publishConfiguration();
		var frozen = prepared();
		var actual = actual(frozen, "승인완료");
		((ObjectNode)actual.path("items").get(0).path("images").get(0)).put("vendorPath", "image-filename.jpg");
		publishProduct(actual, 12300, 300, true);
		assertThat(publication.read("123", "SB-123", frozen.toString()).detail()).contains("images");
	}

	@Test
	void explicitNoticeAttributeDocumentAndCertificationInputsAreFrozenOnlyAfterMetadataValidation() {
		var attr = (ObjectNode)meta.path("attributes").get(1);
		attr.put("attributeTypeName", "색상").put("dataType", "STRING");
		((ArrayNode)attr.path("inputValues")).removeAll().add("빨강").add("파랑");
		((ObjectNode)meta.path("noticeCategories").get(0).path("noticeCategoryDetailNames").get(0))
			.put("noticeCategoryDetailName", "소비기한");
		((ArrayNode)meta.path("requiredDocumentNames")).addObject().put("templateName", "성분표").put("required",
			"MANDATORY_INGREDIENTS_PIC");
		publishConfiguration();
		var supplied = new MarketPublishContext(null, null, null, List.of(), Map.of("소비기한", "제품 라벨의 날짜 확인"),
			Map.of("noticeCategoryName", "식품", "attributes", Map.of("색상", "파랑"), "documentUrls",
				Map.of("성분표", "https://example.com/ingredients.jpg"), "certifications", Map.of("NOT_REQUIRED", "")));
		var result = read(publication.prepare(product, new BigDecimal("12300"), supplied).payload());
		assertThat(result.path("requiredDocuments").toString()).contains("성분표", "ingredients.jpg");
		assertThat(result.path("items").get(0).path("notices").toString()).contains("제품 라벨의 날짜 확인");
		assertThat(result.path("items").get(0).path("attributes").toString()).contains("파랑");
		var invalid = new MarketPublishContext(null, null, null, List.of(), supplied.noticeFields(),
			Map.of("attributes", Map.of("색상", "노랑"), "certifications", Map.of("NOT_REQUIRED", "")));
		assertThatThrownBy(() -> publication.prepare(product, new BigDecimal("12300"), invalid))
			.hasMessageContaining("허용 선택값");
		var skip = new MarketPublishContext(null, null, null, List.of(), supplied.noticeFields(),
			Map.of("attributes", Map.of("색상", "파랑"), "documentNotApplicable", Map.of("성분표", "true"), "certifications",
				Map.of("NOT_REQUIRED", "")));
		assertThatThrownBy(() -> publication.prepare(product, new BigDecimal("12300"), skip))
			.hasMessageContaining("비해당으로 생략");
	}

	@Test
	void metadataSchemaHasSafeSuggestionsAndPreviousEvidenceRequiresSameAccountSbAndCategory() {
		var schema = publication.describe(product, null);
		assertThat(schema).containsEntry("categoryId", "73199");
		var suggestion = (MarketPublishContext)schema.get("suggestedContext");
		assertThat(suggestion.noticeFields()).isEmpty();
		assertThat(suggestion.extraFields()).containsOnlyKeys("attributes");
		var frozen = prepared();
		assertThat(publication.previous(product, "73199", frozen.toString())).isPresent();
		assertThat(publication.previous(product, "73198", frozen.toString())).isEmpty();
		frozen.put("vendorId", "other");
		assertThat(publication.previous(product, "73199", frozen.toString())).isEmpty();
		frozen = prepared();
		((ObjectNode)frozen.path("items").get(0)).put("externalVendorSku", "wrong");
		assertThat(publication.previous(product, "73199", frozen.toString())).isEmpty();
	}

	@Test
	void missingCertificateSelectionNeverAssumesExemption() {
		assertThatThrownBy(() -> publication.prepare(product, new BigDecimal("12300"))).hasMessageContaining("인증 구분");
	}

	@Test
	void actual73137MetadataExposesGroupsAndAcceptsOnlyExplicitNoticeAndDocumentChoices() throws Exception {
		try (var stream = getClass().getResourceAsStream("/coupang/category-73137-required-inputs-2026-09-08.json")) {
			meta = (ObjectNode)mapper.readTree(stream).path("data");
		}
		when(rest.requestWithBody("GET",
			"/v2/providers/seller_api/apis/api/v1/marketplace/meta/category-related-metas/display-category-codes/73137",
			null)).thenReturn(envelope(meta));
		var schema = publication.describe(product, "73137");
		var attributes = (List<Map<String, Object>>)schema.get("attributes");
		assertThat(attributes.stream().filter(row -> Boolean.TRUE.equals(row.get("exclusiveGroup")))).hasSize(3);
		Map<String, String> noticeValues = new LinkedHashMap<>();
		for (JsonNode category : meta.path("noticeCategories"))
			if ("건강기능식품".equals(category.path("noticeCategoryName").asText()))
				for (JsonNode detail : category.path("noticeCategoryDetailNames"))
					noticeValues.put(detail.path("noticeCategoryDetailName").asText(), "사용자가 확인한 테스트 값");
		var context = new MarketPublishContext("73137", null, null, List.of(), noticeValues,
			Map.of("noticeCategoryName", "건강기능식품", "certifications", Map.of("NOT_REQUIRED", ""), "documentUrls",
				Map.of("MANDATORY INGREDIENTS PIC", "https://example.com/ingredients.jpg"), "documentNotApplicable",
				Map.of("UN 38.3 Test Report", "true", "MSDS Test Report", "true")));
		var body = read(publication.prepare(product, new BigDecimal("12300"), context).payload());
		assertThat(body.path("displayCategoryCode").asInt()).isEqualTo(73137);
		assertThat(body.path("items").get(0).path("notices").size()).isEqualTo(noticeValues.size());
		assertThat(body.path("requiredDocuments").size()).isEqualTo(1);
	}

}
