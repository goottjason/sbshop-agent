package com.sbshop.agent.infrastructure.client.smartstore.component;

import com.sbshop.agent.core.domain.market.client.dto.MarketPublishContext;
import com.sbshop.agent.core.domain.product.Product;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class SmartstoreProductPayloadBuilder {

	private static final int IN_STOCK_QUANTITY = Product.DEFAULT_IN_STOCK_QUANTITY;

	private static final String NOTICE_TYPE_HEALTH = "HEALTH_FUNCTIONAL_FOOD";
	private static final String NOTICE_TYPE_PROCESSED = "PROCESSED_FOOD";

	public Map<String, Object> build(Product product, MarketPublishContext context) {
		String leafCategoryId = require(context.categoryId(), "스마트스토어 리프 카테고리(leafCategoryId)");
		String shippingAddressId = require(context.extraString("shippingAddressId"), "출고지 주소ID");
		String returnAddressId = require(context.extraString("returnAddressId"), "반품지 주소ID");
		String asTelephone = require(context.extraString("afterServiceTelephoneNumber"), "A/S 전화번호");

		int salePrice = context.salePrice() != null
			? context.salePrice().intValue()
			: (product.getSalePrice() != null ? product.getSalePrice().intValue() : 0);

		Map<String, Object> originProduct = new LinkedHashMap<>();
		originProduct.put("statusType", "SALE");
		originProduct.put("saleType", "NEW");
		originProduct.put("leafCategoryId", leafCategoryId);
		originProduct.put("name", product.getProductName());
		originProduct.put("detailContent", product.getDetailHtml());
		originProduct.put("salePrice", salePrice);
		originProduct.put("stockQuantity", IN_STOCK_QUANTITY);
		originProduct.put("images", images(product));
		originProduct.put("deliveryInfo", deliveryInfo(context, shippingAddressId, returnAddressId));
		originProduct.put("detailAttribute", detailAttribute(product, context, asTelephone));
		originProduct.put("customerBenefit", Map.of(
			"immediateDiscountPolicy", Map.of()));

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("originProduct", originProduct);
		body.put("smartstoreChannelProduct", Map.of(
			"naverShoppingRegistration", true,
			"channelProductDisplayStatusType", "ON"));
		return body;
	}

	private Map<String, Object> images(Product product) {
		List<String> hosted = product.getHostedImages();
		if (hosted.isEmpty()) {
			throw new IllegalStateException("스마트스토어 등록에 필요한 대표 이미지가 없습니다.");
		}
		Map<String, Object> images = new LinkedHashMap<>();
		images.put("representativeImage", Map.of("url", hosted.get(0)));
		if (hosted.size() > 1) {
			List<Map<String, String>> optional = new ArrayList<>();
			for (String url : hosted.subList(1, Math.min(hosted.size(), 10))) {
				optional.add(Map.of("url", url));
			}
			images.put("optionalImages", optional);
		}
		return images;
	}

	private Map<String, Object> deliveryInfo(MarketPublishContext context,
		String shippingAddressId, String returnAddressId) {
		Map<String, Object> claim = new LinkedHashMap<>();
		claim.put("returnDeliveryFee", context.extraInt("returnDeliveryFee", 7000));
		claim.put("exchangeDeliveryFee", context.extraInt("exchangeDeliveryFee", 14000));
		claim.put("shippingAddressId", asLong(shippingAddressId));
		claim.put("returnAddressId", asLong(returnAddressId));
		claim.put("freeReturnInsuranceYn", false);

		Map<String, Object> deliveryFee = new LinkedHashMap<>();
		deliveryFee.put("deliveryFeeType", "FREE");
		deliveryFee.put("deliveryFeePayType", "PREPAID");
		deliveryFee.put("deliveryAreaType", "AREA_2");

		Map<String, Object> delivery = new LinkedHashMap<>();
		delivery.put("deliveryType", "DELIVERY");
		delivery.put("deliveryAttributeType", "NORMAL");
		delivery.put("deliveryCompany", "CJGLS");
		delivery.put("deliveryBundleGroupUsable", false);
		delivery.put("deliveryFee", deliveryFee);
		delivery.put("claimDeliveryInfo", claim);
		delivery.put("todayStockQuantity", 0);
		delivery.put("expectedDeliveryPeriodType", "OVERSEAS_DELIVERY");
		return delivery;
	}

	private Map<String, Object> detailAttribute(Product product, MarketPublishContext context,
		String asTelephone) {
		Map<String, Object> attr = new LinkedHashMap<>();

		attr.put("naverShoppingSearchInfo", Map.of(
			"manufacturerName", nz(product.getBrand()),
			"brandName", nz(product.getBrand())));

		attr.put("afterServiceInfo", Map.of(
			"afterServiceTelephoneNumber", asTelephone,
			"afterServiceGuideContent", nz(context.extraString("afterServiceGuideContent"),
				"구매대행 상품 문의는 판매자 문의하기를 이용해 주세요.")));

		attr.put("originAreaInfo", Map.of(
			"originAreaCode", nz(context.extraString("originAreaCode"), "0200037"),
			"importer", nz(context.extraString("importer"), "구매대행"),
			"content", nz(originContent(product))));

		attr.put("sellerCodeInfo", Map.of(
			"sellerManagementCode", nz(product.getSbCode())));

		attr.put("minorPurchasable", true);
		attr.put("productInfoProvidedNotice", productInfoProvidedNotice(product, context));
		attr.put("certificationTargetExcludeContent", Map.of(
			"childCertifiedProductExclusionYn", true,
			"kcExemptionType", "OVERSEAS",
			"kcCertifiedProductExclusionYn", "TRUE"));
		attr.put("taxType", "TAX");
		return attr;
	}

	private Map<String, Object> productInfoProvidedNotice(Product product,
		MarketPublishContext context) {
		Map<String, String> notice = context.noticeFields();
		String noticeType = NOTICE_TYPE_HEALTH.equals(notice.get("noticeType"))
			|| notice.get("noticeType") == null ? NOTICE_TYPE_HEALTH : NOTICE_TYPE_PROCESSED;

		Map<String, Object> detail = new LinkedHashMap<>();
		detail.put("returnCostReason", "상세설명 참조");
		detail.put("noRefundReason", "상세설명 참조");
		detail.put("qualityAssuranceStandard", "상세설명 참조");
		detail.put("compensationProcedure", "상세설명 참조");
		detail.put("troubleShootingContents", "상세설명 참조");
		detail.put("itemName", pick(notice, "productName", product.getBaseName()));
		detail.put("foodType", pick(notice, "foodType", "건강기능식품"));
		detail.put("producer", pick(notice, "producer", nz(product.getBrand())));
		detail.put("capacity", pick(notice, "capacity", "상세설명 참조"));
		detail.put("expirationDate", pick(notice, "expirationDate", "상세설명 참조"));
		detail.put("rawMaterial", pick(notice, "ingredients", "상세설명 참조"));
		detail.put("nutritionInfo", pick(notice, "nutrition", "상세설명 참조"));
		detail.put("intakeMethod", pick(notice, "intakeMethod", "상세설명 참조"));
		detail.put("customerServiceNumber", pick(notice, "customerServiceNumber", "상세설명 참조"));
		detail.put("gmoInfo", pick(notice, "gmoInfo", "상세설명 참조"));
		detail.put("importDeclarationCheck",
			pick(notice, "importDeclaration", "「식품위생법」에 따른 수입신고를 필함(구매대행)"));

		Map<String, Object> block = new LinkedHashMap<>();
		block.put("productInfoProvidedNoticeType", noticeType);
		block.put(noticeType.equals(NOTICE_TYPE_HEALTH) ? "healthFunctionalFood" : "processedFood",
			detail);
		return block;
	}

	private String originContent(Product product) {
		if (product.getSourcingInfo() == null)
			return "상세설명 참조";
		String origin = product.getSourcingInfo().getOrigin();
		return origin == null || origin.isBlank() ? "상세설명 참조" : origin;
	}

	private String require(String value, String label) {
		if (value == null || value.isBlank()) {
			throw new IllegalStateException(
				"스마트스토어 등록 필수값이 없습니다: " + label + " — 검수 화면에서 채운 뒤 다시 시도하세요.");
		}
		return value.trim();
	}

	private Long asLong(String value) {
		try {
			return Long.parseLong(value.trim());
		} catch (NumberFormatException e) {
			throw new IllegalStateException("주소록 ID가 숫자가 아닙니다: " + value);
		}
	}

	private String pick(Map<String, String> notice, String key, String fallback) {
		String v = notice.get(key);
		return v == null || v.isBlank() ? (fallback == null || fallback.isBlank()
			? "상세설명 참조" : fallback) : v;
	}

	private String nz(String s) {
		return s == null ? "" : s;
	}

	private String nz(String s, String fallback) {
		return s == null || s.isBlank() ? fallback : s;
	}
}
