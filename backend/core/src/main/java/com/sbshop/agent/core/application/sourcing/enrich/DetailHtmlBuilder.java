package com.sbshop.agent.core.application.sourcing.enrich;

import com.sbshop.agent.core.application.sourcing.dto.ProductDetailDto;
import com.sbshop.agent.core.domain.sourcing.ProductDraft;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 상품 상세 <b>본문 섹션</b> 생성 — 성분·섭취방법·주의사항·구매대행 고지.
 *
 * <p>배너·제목·구성·이미지 나열은 {@code Product.generateTemplateHtml()}이 이미 붙인다.
 * 여기서 전체 HTML을 만들어 {@code rawSourceHtml}로 넘기면 배너 안에 배너가 들어가는
 * 이중 래핑이 된다 — 그래서 이 빌더는 <b>템플릿 안쪽에 들어갈 본문만</b> 만든다.
 *
 * <p>기존 파이프라인이 여기에 넣던 것은 iHerb 원문 HTML 덩어리였다. 대신 상세 크롤로 확보한
 * 한글 성분·섭취방법·주의사항을 구조화된 섹션으로 넣는다 — 상품정보제공고시와 내용이 일치해야 하고,
 * 구매자가 실제로 찾는 정보다.
 */
@Component
public class DetailHtmlBuilder {

	private static final String ACCENT = "#00B0A2";

	/**
	 * @param hostedImages 현재 미사용(템플릿이 이미지 배치를 담당). 시그니처는 향후
	 *                     본문 중간 삽입형 레이아웃을 대비해 유지한다.
	 */
	public String build(ProductDraft draft, List<String> hostedImages, ProductDetailDto detail) {
		StringBuilder sb = new StringBuilder(4096);

		// 구조화 섹션 — 상세 크롤에서 확보한 한글 정보
		section(sb, "제품 설명", detail != null ? detail.description() : null);
		section(sb, "주요 성분", detail != null ? detail.mainIngredients() : null);
		section(sb, "기타 성분", detail != null ? detail.otherIngredients() : null);
		section(sb, "섭취 방법", draft.getUsageKo());
		section(sb, "주의 사항", draft.getCautionKo());

		// 구매대행 고지 — 표기 누락은 표시광고법 이슈가 된다.
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

	/** 크롤한 문자열이 그대로 HTML에 들어가므로 태그 주입을 막는다. */
	private String esc(String s) {
		if (s == null)
			return "";
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
			.replace("\"", "&quot;");
	}

}
