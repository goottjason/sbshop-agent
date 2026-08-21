package com.sbshop.agent.core.application.sourcing.enrich;

import com.sbshop.agent.core.application.sourcing.dto.ProductDetailDto;
import com.sbshop.agent.core.domain.sourcing.ProductDraft;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class DetailHtmlBuilder {
	private static final String ACCENT = "#00B0A2";

	public String build(ProductDraft draft, List<String> hostedImages, ProductDetailDto detail) {
		StringBuilder sb = new StringBuilder(4096);

		section(sb, "제품 설명", detail != null ? detail.description() : null);
		section(sb, "주요 성분", detail != null ? detail.mainIngredients() : null);
		section(sb, "기타 성분", detail != null ? detail.otherIngredients() : null);
		section(sb, "섭취 방법", draft.getUsageKo());
		section(sb, "주의 사항", draft.getCautionKo());

		sb.append("<div style=\"max-width:800px; margin:24px auto; padding:16px; ")
			.append("background:#F7F7F7; color:#666; font-size:13px; line-height:1.7;\">")
			.append("본 상품은 <strong>해외 구매대행</strong> 상품입니다. 판매자는 구매를 대행할 뿐 ")
			.append("수입·판매의 주체가 아니며, 관세 및 부가세는 구매자 부담입니다.<br/>")
			.append("해외 배송 특성상 배송에 영업일 기준 7~14일이 소요될 수 있습니다.<br/>")
			.append("개인통관고유부호가 필요하며, 자가사용 인정 기준을 초과하는 수량은 통관이 제한될 수 있습니다.")
			.append("</div>");
		return sb.toString();
	}

	private void section(StringBuilder sb, String title, String body) {
		if (body == null || body.isBlank())
			return;
		sb.append("<div style=\"max-width:800px; margin:0 auto 24px;\">")
			.append("<h3 style=\"font-size:18px; color:").append(ACCENT)
			.append("; border-bottom:2px solid ").append(ACCENT)
			.append("; padding-bottom:6px; margin-bottom:12px;\">")
			.append(esc(title)).append("</h3>")
			.append("<div style=\"font-size:15px; color:#555; line-height:1.8;\">")
			.append(esc(body)).append("</div></div>");
	}

	private String esc(String s) {
		if (s == null)
			return "";
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
			.replace("\"", "&quot;");
	}
}
