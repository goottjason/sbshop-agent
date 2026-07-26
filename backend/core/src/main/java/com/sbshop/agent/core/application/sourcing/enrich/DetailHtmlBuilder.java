package com.sbshop.agent.core.application.sourcing.enrich;

import com.sbshop.agent.core.application.sourcing.dto.ProductDetailDto;
import com.sbshop.agent.core.domain.sourcing.ProductDraft;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 상품 상세 HTML 생성.
 *
 * <p>기존 {@code Product.generateTemplateHtml()}은 상단/하단 배너 + 이미지 나열 + 원문 HTML
 * 덩어리였다. 여기서는 상세 크롤로 확보한 <b>한글 성분·섭취방법·주의사항</b>을 구조화된 섹션으로
 * 넣는다 — 상품정보제공고시와 내용이 일치해야 하고, 구매자가 실제로 찾는 정보다.
 *
 * <p>기존 배너 URL은 그대로 재사용한다(운영 중인 자산). 11번가는 http 이미지에 방화벽 409를 내므로
 * https로 넣는다.
 */
@Component
public class DetailHtmlBuilder {

	private static final String TOP_BANNER = "https://ai.esmplus.com/shouldbe2480/notice/sb_top.png";
	private static final String BOTTOM_BANNER = "https://ai.esmplus.com/shouldbe2480/notice/sb_bottom.png";

	private static final String ACCENT = "#00B0A2";
	private static final String HIGHLIGHT = "#EF007C";

	public String build(ProductDraft draft, List<String> hostedImages, ProductDetailDto detail) {
		StringBuilder sb = new StringBuilder(4096);

		sb.append(img(TOP_BANNER, "100%")).append("<br/><br/>");

		// 제목
		sb.append("<div style=\"text-align:center; margin-bottom:10px;\">")
			.append("<span style=\"font-size:22px; color:").append(ACCENT)
			.append("; font-weight:bold;\">").append(esc(displayName(draft))).append("</span><br/>")
			.append("<span style=\"font-size:16px; color:#777;\">")
			.append(esc(nz(draft.getOriginalName()))).append("</span></div><br/>");

		// 구성
		int bundle = draft.getBundleQty() != null ? draft.getBundleQty() : 1;
		sb.append("<div style=\"text-align:center; margin-bottom:24px;\">")
			.append("<span style=\"font-size:20px; color:").append(HIGHLIGHT)
			.append("; font-weight:bold;\">[구성] ").append(bundle).append("개 묶음");
		if (detail != null && detail.packageQuantity() != null) {
			sb.append(" (1개당 ").append(detail.packageQuantity()).append("정)");
		}
		sb.append("</span></div><br/>");

		// 이미지
		for (String url : hostedImages) {
			sb.append(img(url, "800px")).append("<br/><br/>");
		}

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
			.append("</div><br/>");

		sb.append(img(BOTTOM_BANNER, "100%"));
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

	private String displayName(ProductDraft draft) {
		StringBuilder sb = new StringBuilder();
		if (draft.getBrand() != null && !draft.getBrand().isBlank())
			sb.append(draft.getBrand()).append(' ');
		sb.append(nz(draft.getBaseNameKo()));
		return sb.toString().trim();
	}

	private String img(String url, String maxWidth) {
		return "<img src=\"" + url + "\" style=\"margin:0 auto; display:block; max-width:"
			+ maxWidth + ";\"/>";
	}

	/** 크롤한 문자열이 그대로 HTML에 들어가므로 태그 주입을 막는다. */
	private String esc(String s) {
		if (s == null)
			return "";
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
			.replace("\"", "&quot;");
	}

	private String nz(String s) {
		return s == null ? "" : s;
	}
}
