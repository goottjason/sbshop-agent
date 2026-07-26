package com.sbshop.agent.core.application.sourcing.enrich;

import com.sbshop.agent.core.domain.sourcing.ProductDraft;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 상품정보제공고시 항목 생성.
 *
 * <p>전자상거래법상 필수 표기이고 마켓 등록의 필수필드다. 항목 이름은 마켓마다 다르지만
 * <b>내용은 같다</b> — 그래서 여기서 중립 키로 한 벌 만들고, 마켓 어댑터가 자기 스키마로 옮긴다.
 *
 * <p>값은 대부분 상세 크롤 결과(성분·용량·섭취방법·주의사항)에서 그대로 온다. 크롤이 준 게 없으면
 * "상세설명 참조"로 채운다 — 빈 값으로 보내면 마켓이 거절하거나 심사에서 반려된다.
 */
@Component
public class ProductNoticeBuilder {

	private static final String SEE_DETAIL = "상세설명 참조";

	/** 건강기능식품 고시 유형. 마켓별 코드는 어댑터가 매핑한다. */
	public static final String TYPE_HEALTH_FUNCTIONAL_FOOD = "HEALTH_FUNCTIONAL_FOOD";
	/** 가공식품 고시 유형. */
	public static final String TYPE_PROCESSED_FOOD = "PROCESSED_FOOD";

	/**
	 * @param categorySlug iHerb 카테고리 slug. grocery면 가공식품, 그 외는 건강기능식품으로 본다.
	 */
	public Map<String, String> build(ProductDraft draft, String categorySlug) {
		Map<String, String> notice = new LinkedHashMap<>();
		notice.put("noticeType", noticeType(categorySlug));
		notice.put("productName", nz(draft.getBaseNameKo()));
		notice.put("foodType", noticeType(categorySlug).equals(TYPE_PROCESSED_FOOD) ? "가공식품" : "건강기능식품");
		notice.put("producer", nz(draft.getBrand()));
		notice.put("origin", nz(draft.getOrigin()));
		notice.put("importDeclaration", "「식품위생법」에 따른 수입신고를 필함(구매대행)");
		notice.put("capacity", capacityText(draft));
		notice.put("expirationDate", SEE_DETAIL);
		notice.put("ingredients", firstNonBlank(draft.getIngredientsKo(), SEE_DETAIL));
		notice.put("nutrition", SEE_DETAIL);
		notice.put("intakeMethod", firstNonBlank(draft.getUsageKo(), SEE_DETAIL));
		notice.put("caution", firstNonBlank(draft.getCautionKo(), SEE_DETAIL));
		notice.put("gmoInfo", SEE_DETAIL);
		notice.put("customerServiceNumber", SEE_DETAIL);
		// 구매대행은 판매자가 수입자가 아니다 — 표기를 흐리면 안 된다.
		notice.put("purchaseAgentNotice",
			"본 상품은 해외 구매대행 상품으로, 판매자는 구매를 대행할 뿐 수입·판매의 주체가 아닙니다.");
		return notice;
	}

	public String noticeType(String categorySlug) {
		return "grocery".equalsIgnoreCase(categorySlug) ? TYPE_PROCESSED_FOOD
			: TYPE_HEALTH_FUNCTIONAL_FOOD;
	}

	private String capacityText(ProductDraft draft) {
		if (draft.getCapacity() == null || draft.getCapacity().signum() <= 0)
			return SEE_DETAIL;
		String unit = draft.getMeasureUnit() != null ? draft.getMeasureUnit().getDescription() : "";
		String single = draft.getCapacity().stripTrailingZeros().toPlainString() + unit;
		int bundle = draft.getBundleQty() != null ? draft.getBundleQty() : 1;
		return bundle > 1 ? "%s × %d개".formatted(single, bundle) : single;
	}

	private String firstNonBlank(String a, String b) {
		return a != null && !a.isBlank() ? a : b;
	}

	private String nz(String s) {
		return s == null || s.isBlank() ? SEE_DETAIL : s;
	}
}
