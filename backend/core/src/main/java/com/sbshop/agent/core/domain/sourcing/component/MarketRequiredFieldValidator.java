package com.sbshop.agent.core.domain.sourcing.component;

import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.sourcing.MarketDraft;
import com.sbshop.agent.core.domain.sourcing.ProductDraft;
import java.util.ArrayList;
import java.util.List;

/**
 * 마켓별 등록 필수필드 검사.
 *
 * <p>마켓 API가 400으로 거절한 뒤에 원인을 찾는 대신, <b>검수 화면에서 무엇이 빠졌는지 먼저</b>
 * 보여주기 위한 것이다. 미충족 항목이 있으면 해당 마켓 등록 버튼을 비활성화한다.
 *
 * <p>여기 목록은 각 마켓 공식 스펙의 REQUIRED 항목에서 뽑았다. 예를 들어 스마트스토어
 * {@code POST /v2/products}는 {@code originProduct.statusType/name/detailContent/images/
 * salePrice/detailAttribute}와 {@code smartstoreChannelProduct}가 모두 REQUIRED이고,
 * {@code leafCategoryId}·{@code stockQuantity}는 "상품 등록 시 필수"다.
 * 현행 구현이 보내던 6필드로는 등록이 성립하지 않는다.
 */
public final class MarketRequiredFieldValidator {

	private MarketRequiredFieldValidator() {
	}

	/** 미충족 필수필드 라벨 목록. 비어 있으면 등록 가능. */
	public static List<String> validate(ProductDraft draft, MarketDraft marketDraft) {
		List<String> missing = new ArrayList<>();

		// --- 전 마켓 공통 ---
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
			default -> { }
		}
		return missing;
	}

	/**
	 * 쿠팡 seller-products 등록.
	 * 구매대행(해외직구) 상품은 원산지·수입 관련 항목이 추가로 필요하다.
	 */
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

	/**
	 * 스마트스토어 커머스API {@code POST /v2/products}.
	 * originProduct.detailAttribute와 smartstoreChannelProduct가 REQUIRED다.
	 */
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

	/** 11번가 신규상품 등록 XML. */
	private static void validateElevenst(ProductDraft draft, MarketDraft md, List<String> missing) {
		if (isBlank(draft.getOrigin()))
			missing.add("원산지");
		requireExtra(md, missing, "dlvCnAreaCd", "출고지 주소코드");
		requireExtra(md, missing, "rtngdDlvCnAreaCd", "반품지 주소코드");
		requireExtra(md, missing, "abrdBuyPlace", "해외구매대행 구매처");
		if (isBlank(md.getNoticeFields()))
			missing.add("상품고시정보");
	}

	/** Cafe24 자사몰. 진열 분류가 없으면 등록은 되나 노출되지 않는다. */
	private static void validateCafe24(ProductDraft draft, MarketDraft md, List<String> missing) {
		if (isBlank(draft.getOrigin()))
			missing.add("원산지");
	}

	private static void requireExtra(MarketDraft md, List<String> missing, String jsonKey, String label) {
		String extra = md.getExtraFields();
		if (extra == null || !containsNonEmptyKey(extra, jsonKey))
			missing.add(label);
	}

	/**
	 * extraFields JSON에 해당 키가 <b>비어 있지 않게</b> 들어 있는지 본다.
	 * 정식 파싱 대신 문자열 검사인 이유는 도메인 컴포넌트가 ObjectMapper에 의존하지 않게 하기 위함이며,
	 * {@code "key":""}·{@code "key":null}을 "있음"으로 세지 않는 것이 핵심이다.
	 */
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
