package com.sbshop.agent.infrastructure.client.smartstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sbshop.agent.core.domain.market.client.dto.MarketPublishContext;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.dto.ProductCreateCommand;
import com.sbshop.agent.core.domain.product.enums.MeasureUnit;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import com.sbshop.agent.infrastructure.client.smartstore.component.SmartstoreProductPayloadBuilder;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 스마트스토어 등록 payload 형태를 고정한다.
 *
 * <p>종전 구현은 {@code originProduct}에 6필드만 담아 커머스API 필수필드를 못 채웠다.
 * 이 테스트는 그 회귀를 막는다 — 필수 블록이 빠지면 등록이 400으로 실패하는데,
 * 그건 라이브 등록을 시도해야만 드러나므로 여기서 잡아야 한다.
 */
class SmartstoreProductPayloadBuilderTest {

	private final SmartstoreProductPayloadBuilder builder = new SmartstoreProductPayloadBuilder();

	private Product product() {
		ProductCreateCommand command = new ProductCreateCommand(
			"https://kr.iherb.com/pr/x/1", new BigDecimal("20000"), "비타민D3 K2",
			"Vitamin D3 K2", "California Gold Nutrition", "미국",
			new BigDecimal("60"), new BigDecimal("180"), MeasureUnit.EA,
			List.of("https://src/1.jpg"), List.of("https://cdn/1.jpg", "https://cdn/2.jpg"),
			"<div>본문</div>", "보충제", true, 1, new BigDecimal("20"), VendorType.IHB);
		return Product.create("250726IHB001", command);
	}

	private MarketPublishContext context() {
		Map<String, Object> extra = new LinkedHashMap<>();
		extra.put("shippingAddressId", "1001");
		extra.put("returnAddressId", "1002");
		extra.put("afterServiceTelephoneNumber", "010-2597-2480");
		extra.put("afterServiceGuideContent", "문의하기 이용");
		extra.put("returnDeliveryFee", 7000);
		extra.put("exchangeDeliveryFee", 14000);
		extra.put("originAreaCode", "0200037");
		Map<String, String> notice = new LinkedHashMap<>();
		notice.put("noticeType", "HEALTH_FUNCTIONAL_FOOD");
		notice.put("productName", "비타민D3 K2");
		notice.put("ingredients", "비타민D3, 비타민K2");
		return new MarketPublishContext("50000123", "건강기능식품 > 비타민",
			new BigDecimal("42300"), List.of("비타민D3"), notice, extra);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> originProduct(Map<String, Object> body) {
		return (Map<String, Object>)body.get("originProduct");
	}

	@Test
	@DisplayName("originProduct에 커머스API 필수필드가 모두 들어간다")
	void includesAllRequiredOriginProductFields() {
		Map<String, Object> body = builder.build(product(), context());
		Map<String, Object> origin = originProduct(body);

		assertThat(origin).containsKeys(
			"statusType", "saleType", "leafCategoryId", "name", "detailContent",
			"salePrice", "stockQuantity", "images", "deliveryInfo", "detailAttribute");
		assertThat(origin.get("statusType")).isEqualTo("SALE");
		assertThat(origin.get("leafCategoryId")).isEqualTo("50000123");
		// 검수된 판매가가 상품 엔티티 값을 이긴다.
		assertThat(origin.get("salePrice")).isEqualTo(42300);
	}

	@Test
	@DisplayName("smartstoreChannelProduct 블록이 반드시 포함된다 — 없으면 채널 상품이 안 만들어진다")
	void includesChannelProductBlock() {
		Map<String, Object> body = builder.build(product(), context());

		assertThat(body).containsKey("smartstoreChannelProduct");
		@SuppressWarnings("unchecked") Map<String, Object> channel = (Map<String, Object>)body
			.get("smartstoreChannelProduct");
		assertThat(channel.get("channelProductDisplayStatusType")).isEqualTo("ON");
		assertThat(channel.get("naverShoppingRegistration")).isEqualTo(true);
	}

	@Test
	@DisplayName("배송정보에 반품·교환 배송비와 출고지·반품지 주소록 ID가 들어간다")
	void includesClaimDeliveryInfo() {
		Map<String, Object> origin = originProduct(builder.build(product(), context()));

		@SuppressWarnings("unchecked") Map<String, Object> delivery = (Map<String, Object>)origin.get("deliveryInfo");
		@SuppressWarnings("unchecked") Map<String, Object> claim = (Map<String, Object>)delivery
			.get("claimDeliveryInfo");

		assertThat(claim.get("returnDeliveryFee")).isEqualTo(7000);
		assertThat(claim.get("exchangeDeliveryFee")).isEqualTo(14000);
		// 주소록 ID는 숫자로 보내야 한다(문자열이면 커머스API가 거절한다).
		assertThat(claim.get("shippingAddressId")).isEqualTo(1001L);
		assertThat(claim.get("returnAddressId")).isEqualTo(1002L);
	}

	@Test
	@DisplayName("detailAttribute에 A/S·원산지·고시정보·판매자코드가 들어간다")
	void includesDetailAttribute() {
		Map<String, Object> origin = originProduct(builder.build(product(), context()));

		@SuppressWarnings("unchecked") Map<String, Object> attr = (Map<String, Object>)origin.get("detailAttribute");
		assertThat(attr).containsKeys("afterServiceInfo", "originAreaInfo",
			"productInfoProvidedNotice", "sellerCodeInfo", "minorPurchasable");

		@SuppressWarnings("unchecked") Map<String, Object> as = (Map<String, Object>)attr.get("afterServiceInfo");
		assertThat(as.get("afterServiceTelephoneNumber")).isEqualTo("010-2597-2480");

		@SuppressWarnings("unchecked") Map<String, Object> notice = (Map<String, Object>)attr
			.get("productInfoProvidedNotice");
		assertThat(notice.get("productInfoProvidedNoticeType")).isEqualTo("HEALTH_FUNCTIONAL_FOOD");
		assertThat(notice).containsKey("healthFunctionalFood");
	}

	@Test
	@DisplayName("대표이미지는 오브젝트로 넣는다 — 최상위 문자열은 네이버가 조용히 무시한다")
	void representativeImageIsObject() {
		Map<String, Object> origin = originProduct(builder.build(product(), context()));

		@SuppressWarnings("unchecked") Map<String, Object> images = (Map<String, Object>)origin.get("images");
		assertThat(images.get("representativeImage")).isInstanceOf(Map.class);
		assertThat(images).containsKey("optionalImages");
	}

	@Test
	@DisplayName("필수 계정값이 없으면 빈 값으로 보내지 않고 원인을 밝히며 실패한다")
	void failsFastWhenAccountFieldsMissing() {
		MarketPublishContext bare = new MarketPublishContext(
			"50000123", null, new BigDecimal("42300"), List.of(), Map.of(), Map.of());

		assertThatThrownBy(() -> builder.build(product(), bare))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("출고지 주소ID");
	}

	@Test
	@DisplayName("카테고리가 없으면 등록을 시도하지 않는다")
	void failsFastWhenCategoryMissing() {
		MarketPublishContext noCategory = new MarketPublishContext(
			null, null, new BigDecimal("42300"), List.of(), Map.of(),
			context().extraFields());

		assertThatThrownBy(() -> builder.build(product(), noCategory))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("leafCategoryId");
	}
}
