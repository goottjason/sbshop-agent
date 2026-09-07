package com.sbshop.agent.infrastructure.client.coupang.adapter;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sbshop.agent.core.domain.market.client.dto.*;
import com.sbshop.agent.core.domain.market.sync.MarketTransferFailure;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.vo.ProductSpec;
import com.sbshop.agent.core.domain.product.vo.SourcingInfo;
import com.sbshop.agent.infrastructure.client.coupang.client.CoupangRestClient;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.http.HttpStatus;

class CoupangReviewedFieldsTest {
	final ObjectMapper mapper = new ObjectMapper();
	final CoupangRestClient rest = mock(CoupangRestClient.class);
	final Product product = mock(Product.class);
	final CoupangReviewedFields fields = new CoupangReviewedFields(rest, mapper);
	final String base = "/v2/providers/seller_api/apis/api/v1/marketplace/seller-products";
	final String inventory = "/v2/providers/seller_api/apis/api/v1/marketplace/vendor-items/456/inventories";
	ObjectNode current;

	@BeforeEach void setup() throws Exception {
        when(rest.resolveVendorId()).thenReturn("A00012345");
        when(product.getSbCode()).thenReturn("210121IHB031");when(product.getProductName()).thenReturn("새 상품명");
        when(product.getBrand()).thenReturn("새브랜드");when(product.getDetailHtml()).thenReturn("<p>새 HTML</p>");
        when(product.getHostedImages()).thenReturn(List.of("https://images.example.com/new.jpg","https://images.example.com/extra.jpg"));
        current=(ObjectNode)mapper.readTree("""
            {"sellerProductId":123,"statusName":"승인완료","vendorId":"A00012345",
             "sellerProductName":"기존 등록명","displayProductName":"기존 노출명","brand":"기존브랜드","manufacture":"기존제조사",
             "displayCategoryCode":777,"requested":false,"deliveryCharge":3000,"untouched":{"nested":"keep"},
             "items":[{"sellerProductItemId":789,"vendorItemId":456,"externalVendorSku":"210121IHB031",
             "salePrice":12300,"maximumBuyCount":15,"unitCount":2,"barcode":"old","emptyBarcode":false,"emptyBarcodeReason":null,
             "attributes":[{"attributeTypeName":"keep","attributeValueName":"1개"}],
             "images":[{"imageOrder":0,"imageType":"REPRESENTATION","vendorPath":"https://images.example.com/old.jpg","cdnPath":"image/copied.jpg"}],
             "contents":[{"contentsType":"TEXT","contentDetails":[{"content":"old","detailType":"TEXT"}]}]}]}
            """);
        publish();when(rest.get(inventory)).thenReturn("{\"code\":\"SUCCESS\",\"data\":{\"sellerItemId\":456,\"onSale\":true}}");
        when(rest.put(any(),any())).thenReturn("{\"code\":\"200\",\"data\":{\"code\":\"SUCCESS\",\"data\":123}}");
    }

	void publish(){when(rest.get(base+"/123")).thenReturn(mapper.createObjectNode().put("code","SUCCESS").set("data",current).toString());}

	ObjectNode item() {
		return (ObjectNode)current.path("items").get(0);
	}

	@Test
	void requestedApprovalUsesFreshWholeDocumentAndOnlySelectedDelta() {
		var prepared = fields.prepare(product, "123", null, Set.of("name", "brand", "hostedImages", "detailHtml"));
		assertThat(prepared.requiresApproval()).isTrue();
		assertThat(prepared.resolvedOptionId()).isEqualTo("456");
		current.put("deliveryCharge", 9000);
		item().put("salePrice", 34500);
		publish();
		var guard = mock(Runnable.class);
		fields.write("123", "456", "210121IHB031", prepared, guard);
		var captor = org.mockito.ArgumentCaptor.forClass(Object.class);
		var order = inOrder(guard, rest);
		order.verify(guard).run();
		order.verify(rest).put(eq(base), captor.capture());
		var body = (ObjectNode)captor.getValue();
		assertThat(body.path("requested").booleanValue()).isTrue();
		assertThat(body.path("sellerProductName").asText()).isEqualTo("새 상품명");
		assertThat(body.path("displayProductName").asText()).isEqualTo("새 상품명");
		assertThat(body.path("deliveryCharge").intValue()).isEqualTo(9000);
		assertThat(body.path("displayCategoryCode").intValue()).isEqualTo(777);
		assertThat(body.path("untouched")).isEqualTo(current.path("untouched"));
		var sent = body.path("items").get(0);
		assertThat(sent.path("salePrice").intValue()).isEqualTo(34500);
		assertThat(sent.path("attributes")).isEqualTo(item().path("attributes"));
		assertThat(sent.path("unitCount").intValue()).isEqualTo(2);
		assertThat(sent.path("barcode").asText()).isEqualTo("old");
		assertThat(sent.path("images").size()).isEqualTo(2);
		assertThat(sent.path("contents").get(0).path("contentsType").asText()).isEqualTo("HTML");
	}

	@Test
	void manufacturerUsesDocumentedManufactureAndBarcodeDoesNotRepairOtherAttributes() {
		var info = mock(SourcingInfo.class);
		when(info.getManufacturer()).thenReturn("새 제조사");
		when(product.getSourcingInfo()).thenReturn(info);
		var spec = mock(ProductSpec.class);
		when(spec.getBarcode()).thenReturn("1234567890123");
		when(product.getProductSpec()).thenReturn(spec);
		var prepared = fields.prepare(product, "123", "456", Set.of("manufacturer", "barcode"));
		fields.write("123", "456", "210121IHB031", prepared, () -> {});
		var captor = org.mockito.ArgumentCaptor.forClass(Object.class);
		verify(rest).put(eq(base), captor.capture());
		JsonNode body = (JsonNode)captor.getValue();
		assertThat(body.path("manufacture").asText()).isEqualTo("새 제조사");
		assertThat(body.has("manufacturer")).isFalse();
		assertThat(body.path("items").get(0).path("emptyBarcode").booleanValue()).isFalse();
		assertThat(body.path("items").get(0).path("emptyBarcodeReason").isNull()).isTrue();
		assertThat(body.path("items").get(0).path("attributes")).isEqualTo(item().path("attributes"));
	}

	@ParameterizedTest
	@CsvSource({"심사중,PENDING", "승인대기중,PENDING", "승인반려,REJECTED", "부분승인완료,UNKNOWN", "상품삭제,UNKNOWN", "임시저장,UNKNOWN",
		"승인완료,APPROVED"})
	void explicitApprovalStateIsNotAWriteReceipt(String state, MarketFieldsRead.Approval expected) {
		current.put("statusName", state);
		publish();
		var read = fields.read("123", "456", "210121IHB031", Set.of("name"));
		assertThat(read.approval()).isEqualTo(expected);
		assertThat(read.values().get("name")).contains("기존 등록명", "기존 노출명");
		if (expected != MarketFieldsRead.Approval.APPROVED)
			assertThatThrownBy(() -> fields.prepare(product, "123", "456", Set.of("name")))
				.isInstanceOf(UnsupportedOperationException.class);
		verify(rest, never()).put(any(), any());
	}

	@Test
	void wrongAccountListingOptionSbAndMultipleOptionsAllBlockBeforeWrite() {
		current.put("vendorId", "other");
		publish();
		assertThatThrownBy(() -> fields.prepare(product, "123", "456", Set.of("name"))).hasMessageContaining("계정");
		current.put("vendorId", "A00012345").put("sellerProductId", 999);
		publish();
		assertThatThrownBy(() -> fields.prepare(product, "123", "456", Set.of("name"))).hasMessageContaining("등록상품");
		current.put("sellerProductId", 123);
		item().put("vendorItemId", 999);
		publish();
		assertThatThrownBy(() -> fields.prepare(product, "123", "456", Set.of("name")))
			.hasMessageContaining("vendorItemId");
		item().put("vendorItemId", 456).put("externalVendorSku", "wrong");
		publish();
		assertThatThrownBy(() -> fields.prepare(product, "123", "456", Set.of("name"))).hasMessageContaining("SB코드");
		item().put("externalVendorSku", "210121IHB031");
		((ArrayNode)current.path("items")).add(item().deepCopy());
		publish();
		assertThatThrownBy(() -> fields.prepare(product, "123", "456", Set.of("name"))).hasMessageContaining("단일 옵션");
		verify(rest, never()).put(any(), any());
	}

	@Test
	void sourceItemIdentityAndAccountRecheckedAfterPreparation() {
		var prepared = fields.prepare(product, "123", "456", Set.of("name"));
		item().put("sellerProductItemId", 999);
		publish();
		assertThatThrownBy(() -> fields.write("123", "456", "210121IHB031", prepared, () -> {}))
			.hasMessageContaining("옵션");
		item().put("sellerProductItemId", 789);
		publish();
		assertThatThrownBy(() -> fields.write("123", "456", "210121IHB031", prepared,
			() -> when(rest.resolveVendorId()).thenReturn("other"))).hasMessageContaining("계정");
		verify(rest, never()).put(any(), any());
	}

	@Test
	void callbackFailureIsUnwrappedAndNeverSendsPut() {
		var prepared = fields.prepare(product, "123", "456", Set.of("name"));
		var abort = new IllegalStateException("lease lost");
		assertThatThrownBy(() -> fields.write("123", "456", "210121IHB031", prepared, () -> {
			throw abort;
		})).isSameAs(abort);
		verify(rest, never()).put(any(), any());
	}

	@Test
	void suspendedOptionDoesNotResumeOrWriteAndMatchingCurrentFieldsSkipPut() {
		var prepared = fields.prepare(product, "123", "456", Set.of("brand"));
		current.put("brand", "새브랜드");
		publish();
		fields.write("123", "456", "210121IHB031", prepared, () -> {});
		verify(rest, never()).put(any(), any());
		when(rest.get(inventory)).thenReturn("{\"code\":\"SUCCESS\",\"data\":{\"sellerItemId\":456,\"onSale\":false}}");
		assertThat(fields.read("123", "456", "210121IHB031", Set.of("brand")).writeBlockReason()).contains("중지");
		assertThatThrownBy(() -> fields.write("123", "456", "210121IHB031", prepared, () -> {}))
			.hasMessageContaining("판매 중지");
	}

	@Test
	void cdnOnlyGalleryNeverPretendsToMatchSubmittedVendorUrlsAndUsedImagesAreNotDropped() {
		var prepared = fields.prepare(product, "123", "456", Set.of("hostedImages"));
		var image = (ObjectNode)item().path("images").get(0);
		image.remove("vendorPath");
		publish();
		var read = fields.read("123", "456", "210121IHB031", Set.of("hostedImages"));
		assertThat(read.values()).isNotEqualTo(prepared.expectedValues());
		assertThat(read.values().get("hostedImages")).contains("cdnPath");
		image.put("imageType", "USED_PRODUCT");
		publish();
		assertThatThrownBy(() -> fields.write("123", "456", "210121IHB031", prepared, () -> {}))
			.hasMessageContaining("중고");
		verify(rest, never()).put(any(), any());
	}

	@Test
	void foreignOrDuplicateImageOrderAndEmptyBarcodeReject() {
		var image = (ObjectNode)item().path("images").get(0);
		image.put("imageOrder", 3);
		publish();
		assertThatThrownBy(() -> fields.prepare(product, "123", "456", Set.of("hostedImages")))
			.hasMessageContaining("순서");
		assertThatThrownBy(() -> fields.prepare(product, "123", "456", Set.of("barcode")))
			.hasMessageContaining("바코드 삭제");
		assertThatThrownBy(() -> fields.prepare(product, "123", "456", Set.of("categoryId")))
			.hasMessageContaining("확인하지 않은");
		verify(rest, never()).put(any(), any());
	}

	@Test
	void explicitErrorReceiptRetainsRejectedClassificationAnd429RetainsRetryAfter() {
		var prepared = fields.prepare(product, "123", "456", Set.of("brand"));
		when(rest.put(any(), any())).thenReturn("{\"code\":\"ERROR\",\"message\":\"invalid field\"}");
		assertThatThrownBy(() -> fields.write("123", "456", "210121IHB031", prepared, () -> {}))
			.isInstanceOfSatisfying(MarketTransferFailure.class, e -> assertThat(e.getCode()).isEqualTo("HTTP_400"));
		HttpHeaders headers = new HttpHeaders();
		headers.set("Retry-After", "900");
		when(rest.get(base + "/123")).thenThrow(new RuntimeException("wrapped",
			HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "rate", headers, new byte[0], null)));
		Instant min = Instant.now().plusSeconds(895);
		assertThatThrownBy(() -> fields.read("123", "456", "210121IHB031", Set.of("brand")))
			.isInstanceOfSatisfying(MarketTransferFailure.class, e -> {
				assertThat(e.rateLimited()).isTrue();
				assertThat(e.getRetryAfter()).isAfter(min);
			});
	}

	@Test
	void documentedFilenameVendorPathIsObservedButNeverMatchesSubmittedHttpsUrl() {
		var image = (ObjectNode)item().path("images").get(0);
		image.put("vendorPath", "151009021007000006.jpg");
		publish();
		var prepared = fields.prepare(product, "123", "456", Set.of("hostedImages"));
		var read = fields.read("123", "456", "210121IHB031", Set.of("hostedImages"));
		assertThat(read.values().get("hostedImages")).contains("151009021007000006.jpg");
		assertThat(read.values()).isNotEqualTo(prepared.expectedValues());
	}

}
