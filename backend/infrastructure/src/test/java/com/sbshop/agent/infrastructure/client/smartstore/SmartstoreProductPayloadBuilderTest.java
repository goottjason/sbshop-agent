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

	private Product product(BigDecimal costPrice, BigDecimal marginRate) {
		ProductCreateCommand command = new ProductCreateCommand(
			"https://kr.iherb.com/pr/x/1", costPrice, "비타민D3 K2",
			"Vitamin D3 K2", "California Gold Nutrition", "미국",
			new BigDecimal("60"), new BigDecimal("180"), MeasureUnit.EA,
			List.of("https://src/1.jpg"), List.of("https://cdn/1.jpg", "https://cdn/2.jpg"),
			"<div>본문</div>", "보충제", true, 1, marginRate, VendorType.IHB);
		return Product.create("250726IHB001", command);
	}

	private MarketPublishContext contextWithSalePrice(BigDecimal salePrice) {
		MarketPublishContext base = context();
		return new MarketPublishContext(base.categoryId(), base.categoryPath(), salePrice,
			base.keywords(), base.noticeFields(), base.extraFields());
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
		assertThat(notice.get("productInfoProvidedNoticeType")).isEqualTo("DIET_FOOD");
		assertThat(notice).containsKey("dietFood");
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> notice(Map<String, Object> body) {
		Map<String, Object> attr = (Map<String, Object>)originProduct(body).get("detailAttribute");
		return (Map<String, Object>)attr.get("productInfoProvidedNotice");
	}

	private MarketPublishContext processedFoodContext() {
		Map<String, String> notice = new LinkedHashMap<>();
		notice.put("noticeType", "PROCESSED_FOOD");
		notice.put("productName", "곡물 시리얼");
		notice.put("producer", "제조사A");
		notice.put("capacity", "500g");
		notice.put("customerServiceNumber", "010-2597-2480");
		return new MarketPublishContext("50000456", "식품 > 시리얼",
			new BigDecimal("19900"), List.of("시리얼"), notice, context().extraFields());
	}

	@Test
	@DisplayName("건강기능식품 고시는 DIET_FOOD/dietFood 스키마로 나간다 — 구세대 HEALTH_FUNCTIONAL_FOOD는 현행 API가 NotValidEnum으로 거부한다(라이브 실측)")
	void healthFunctionalNoticeUsesDietFoodSchema() {
		Map<String, Object> notice = notice(builder.build(product(), context()));

		assertThat(notice.get("productInfoProvidedNoticeType")).isEqualTo("DIET_FOOD");
		assertThat(notice).containsKey("dietFood").doesNotContainKey("healthFunctionalFood");

		@SuppressWarnings("unchecked") Map<String, Object> diet = (Map<String, Object>)notice.get("dietFood");
		assertThat(diet).containsKeys("productName", "ingredients", "specification", "weight",
			"amount", "producer", "location", "customerServicePhoneNumber", "cautionAndSideEffect",
			"consumerSafetyCaution", "storageMethod", "nutritionFacts", "intakeMethod",
			"consumptionDateText");
		assertThat(diet).doesNotContainKeys("manufacturer", "noMedicinePhrase", "funtionalInfo",
			"rawMaterial", "nutritionInfo", "gmoInfo", "customerServiceNumber", "capacity",
			"expirationDate");
		assertThat(diet.get("productName")).isEqualTo("비타민D3 K2");
		assertThat(diet.get("producer")).isEqualTo("California Gold Nutrition");
		assertThat(diet.get("ingredients")).isEqualTo("비타민D3, 비타민K2");
		assertThat(diet.get("nutritionFacts")).isEqualTo("상세설명 참조");
		assertThat(diet.get("storageMethod")).isEqualTo("상세설명 참조");
		assertThat(diet.get("location")).isEqualTo("상세설명 참조");
	}

	@Test
	@DisplayName("건기식 고시의 boolean 필드는 Boolean 타입으로 나간다 — 문자열이면 네이버가 역직렬화를 거부한다(라이브 실측)")
	void dietFoodNoticeBooleanFieldsAreBooleanTyped() {
		Map<String, Object> notice = notice(builder.build(product(), context()));

		@SuppressWarnings("unchecked") Map<String, Object> diet = (Map<String, Object>)notice.get("dietFood");
		assertThat(diet.get("nonMedicinalUsesMessage")).isInstanceOf(Boolean.class).isEqualTo(true);
		assertThat(diet.get("importDeclarationCheck")).isInstanceOf(Boolean.class).isEqualTo(true);
		assertThat(diet.get("geneticallyModified")).isInstanceOf(Boolean.class).isEqualTo(false);
		assertThat(diet).extractingByKeys("returnCostReason", "noRefundReason",
			"qualityAssuranceStandard", "compensationProcedure", "troubleShootingContents")
			.allSatisfy(v -> assertThat(v).isInstanceOf(Boolean.class).isEqualTo(true));
	}

	@Test
	@DisplayName("일반식품 고시는 FOOD/food 블록에 필수 9필드를 모두 채운다")
	void processedFoodNoticeUsesFoodSchema() {
		Map<String, Object> notice = notice(builder.build(product(), processedFoodContext()));

		assertThat(notice.get("productInfoProvidedNoticeType")).isEqualTo("FOOD");
		assertThat(notice).containsKey("food").doesNotContainKey("processedFood");

		@SuppressWarnings("unchecked") Map<String, Object> food = (Map<String, Object>)notice.get("food");
		assertThat(food).extractingByKeys("foodItem", "amount", "producer", "weight", "keep",
			"adCaution", "productComposition", "size", "customerServicePhoneNumber")
			.allSatisfy(v -> assertThat(v).isInstanceOf(String.class).asString().isNotBlank());
		assertThat(food.get("weight")).isEqualTo("500g");
		assertThat(food.get("amount")).isEqualTo("500g");
		assertThat(food.get("producer")).isEqualTo("제조사A");
		assertThat(food.get("customerServicePhoneNumber")).isEqualTo("010-2597-2480");
		assertThat(food.get("keep")).isEqualTo("상세설명 참조");
	}

	@Test
	@DisplayName("일반식품 고시의 공통 5필드도 Boolean 타입으로 나간다")
	void foodNoticeFooterFieldsAreBooleanTyped() {
		Map<String, Object> notice = notice(builder.build(product(), processedFoodContext()));

		@SuppressWarnings("unchecked") Map<String, Object> food = (Map<String, Object>)notice.get("food");
		assertThat(food).extractingByKeys("returnCostReason", "noRefundReason",
			"qualityAssuranceStandard", "compensationProcedure", "troubleShootingContents")
			.allSatisfy(v -> assertThat(v).isInstanceOf(Boolean.class).isEqualTo(true));
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
	@DisplayName("KC 인증제외 블록에 kcExemptionType을 넣지 않는다 — 카테고리에 따라 'KC 면제대상 항목은 설정하실 수 없습니다'로 거부된다(라이브 실측)")
	void certificationExcludeContentOmitsKcExemptionType() {
		Map<String, Object> origin = originProduct(builder.build(product(), context()));

		@SuppressWarnings("unchecked") Map<String, Object> attr = (Map<String, Object>)origin.get("detailAttribute");
		@SuppressWarnings("unchecked") Map<String, Object> exclude = (Map<String, Object>)attr
			.get("certificationTargetExcludeContent");

		assertThat(exclude).doesNotContainKey("kcExemptionType");
		assertThat(exclude.get("childCertifiedProductExclusionYn")).isInstanceOf(Boolean.class).isEqualTo(true);
		assertThat(exclude.get("kcCertifiedProductExclusionYn")).isInstanceOf(String.class).isEqualTo("TRUE");
	}

	@Test
	@DisplayName("판매가는 10원 단위로 내려서 보낸다 — 네이버는 1원 단위 판매가를 NumberUnit으로 거부한다(라이브 실측)")
	void salePriceIsFlooredToTenWon() {
		Map<String, Object> origin = originProduct(
			builder.build(product(), contextWithSalePrice(new BigDecimal("51912"))));

		assertThat(origin.get("salePrice")).isEqualTo(51910);
	}

	@Test
	@DisplayName("컨텍스트 판매가가 없어 상품 판매가로 폴백할 때도 10원 단위로 내린다")
	void fallbackSalePriceIsFlooredToTenWon() {
		Product product = product(new BigDecimal("20000"), new BigDecimal("19.56"));
		assertThat(product.getSalePrice().intValue()).isEqualTo(23912);

		Map<String, Object> origin = originProduct(builder.build(product, contextWithSalePrice(null)));

		assertThat(origin.get("salePrice")).isEqualTo(23910);
	}

	@Test
	@DisplayName("이미 10원 단위인 판매가는 그대로 나간다")
	void tenWonUnitSalePriceIsUnchanged() {
		Map<String, Object> origin = originProduct(
			builder.build(product(), contextWithSalePrice(new BigDecimal("51910"))));

		assertThat(origin.get("salePrice")).isEqualTo(51910);
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
