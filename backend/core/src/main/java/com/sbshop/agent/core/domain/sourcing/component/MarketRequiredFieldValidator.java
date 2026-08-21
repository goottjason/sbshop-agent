package com.sbshop.agent.core.domain.sourcing.component;

import com.sbshop.agent.core.domain.sourcing.MarketDraft;
import com.sbshop.agent.core.domain.sourcing.ProductDraft;
import java.util.ArrayList;
import java.util.List;

public final class MarketRequiredFieldValidator {
	private MarketRequiredFieldValidator() {}

	public static List<String> validate(ProductDraft draft, MarketDraft marketDraft) {
		List<String> missing = new ArrayList<>();

		if (isBlank(marketDraft.getProductName()))
			missing.add("상품명");
		if (marketDraft.getSalePrice() == null || marketDraft.getSalePrice().signum() <= 0)
			missing.add("판매가");
		if (isBlank(marketDraft.getCategoryId()))
			missing.add("카테고리");
		if (isBlank(draft.getDetailHtml()))
			missing.add("상세설명");
		if (isBlank(draft.getHostedImages()) || "[]".equals(draft.getHostedImages().trim()))
			missing.add("대표이미지");

		switch (marketDraft.getMarketType()) {
			case COUPANG -> validateCoupang(draft, marketDraft, missing);
			case SMART_STORE -> validateSmartstore(draft, marketDraft, missing);
			case ELEVEN_STREET -> validateElevenst(draft, marketDraft, missing);
			case CAFE24 -> validateCafe24(draft, marketDraft, missing);
			default -> {}
		}
		return missing;
	}

	private static void validateCoupang(ProductDraft draft, MarketDraft md, List<String> missing) {
		if (isBlank(draft.getOrigin()))
			missing.add("원산지");
		if (isBlank(md.getKeywords()) || "[]".equals(md.getKeywords().trim()))
			missing.add("검색태그");
		requireExtra(md, missing, "outboundShippingPlaceCode", "출고지 코드");
		requireExtra(md, missing, "returnCenterCode", "반품지 코드");
		if (isBlank(md.getNoticeFields()))
			missing.add("상품고시정보");
	}

	private static void validateSmartstore(ProductDraft draft, MarketDraft md, List<String> missing) {
		if (isBlank(draft.getOrigin()))
			missing.add("원산지");
		if (isBlank(md.getNoticeFields()))
			missing.add("상품정보제공고시");
		requireExtra(md, missing, "afterServiceTelephoneNumber", "A/S 전화번호");
		requireExtra(md, missing, "afterServiceGuideContent", "A/S 안내");
		requireExtra(md, missing, "shippingAddressId", "출고지 주소ID");
		requireExtra(md, missing, "returnAddressId", "반품지 주소ID");
		requireExtra(md, missing, "returnDeliveryFee", "반품 배송비");
		requireExtra(md, missing, "exchangeDeliveryFee", "교환 배송비");
		requireExtra(md, missing, "originAreaCode", "원산지 코드");
	}

	private static void validateElevenst(ProductDraft draft, MarketDraft md, List<String> missing) {
		if (isBlank(draft.getOrigin()))
			missing.add("원산지");

		requireExtra(md, missing, "addrSeqOut", "출고지 주소코드");
		requireExtra(md, missing, "addrSeqIn", "반품지 주소코드");
		requireExtra(md, missing, "dlvEtprsCd", "발송택배사");
		requireExtra(md, missing, "abrdBuyPlace", "해외구매대행 구매처");
		if (isBlank(md.getNoticeFields()))
			missing.add("상품고시정보");
	}

	private static void validateCafe24(ProductDraft draft, MarketDraft md, List<String> missing) {
		if (isBlank(draft.getOrigin()))
			missing.add("원산지");
	}

	private static void requireExtra(MarketDraft md, List<String> missing, String jsonKey, String label) {
		String extra = md.getExtraFields();
		if (extra == null || !containsNonEmptyKey(extra, jsonKey))
			missing.add(label);
	}

	private static boolean containsNonEmptyKey(String json, String key) {
		String needle = "\"" + key + "\"";
		int idx = json.indexOf(needle);
		if (idx < 0)
			return false;
		int colon = json.indexOf(':', idx + needle.length());
		if (colon < 0)
			return false;
		String rest = json.substring(colon + 1).trim();
		return !(rest.startsWith("\"\"") || rest.startsWith("null"));
	}

	private static boolean isBlank(String s) {
		return s == null || s.isBlank();
	}
}
