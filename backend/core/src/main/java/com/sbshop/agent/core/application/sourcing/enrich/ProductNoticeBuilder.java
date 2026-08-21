package com.sbshop.agent.core.application.sourcing.enrich;

import com.sbshop.agent.core.domain.sourcing.ProductDraft;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ProductNoticeBuilder {
	private static final String SEE_DETAIL = "상세설명 참조";

	public static final String TYPE_HEALTH_FUNCTIONAL_FOOD = "HEALTH_FUNCTIONAL_FOOD";

	public static final String TYPE_PROCESSED_FOOD = "PROCESSED_FOOD";

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
