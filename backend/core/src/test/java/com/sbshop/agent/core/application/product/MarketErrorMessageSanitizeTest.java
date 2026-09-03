package com.sbshop.agent.core.application.product;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MarketErrorMessageSanitizeTest {

	private static final String COUPANG_SUSPENDED_RESPONSE =
		"400 Bad Request: \"{\"code\":\"ERROR\",\"message\":\"판매자에 실패했습니다. "
			+ "[<span style=\"font-weight: normal\">옵션ID(89379431593)은 쿠팡에 의해 '판매중지'된 상품입니다. "
			+ "자세한 중지 사유와 재개 방법은 <span style=\"font-weight: bold\">판매자님의 대표 메일로 "
			+ "전달되었으니</span> 확인 부탁드립니다.<br /><br />판매 재개 요청을 위해 수신하신 쿠팡 메일에 "
			+ "회신 부탁드립니다. ... <a href=\"https://helpseller.coupangcorp.com/hc/ko/articles/360023048573\" "
			+ "class=\"wing-web-component\" data-wuic-props=\"name:link\" target=\"_blank\" "
			+ "style=\"font-size: inherit;\">경로: WING&gt;온라인 문의&gt;상품수정/블라인드 메일을 받았는데 "
			+ "어떻게 해야 하나요</a>?]\"}\"";

	@Test
	@DisplayName("D-285: 쿠팡 판매중지 응답의 태그를 걷어내고 문장은 남긴다")
	void stripsTagsFromRealCoupangSuspendedResponse() {
		String sanitized = ProductMarketSyncService.sanitizeMarketMessage(COUPANG_SUSPENDED_RESPONSE);

		assertThat(sanitized).doesNotContain("<span");
		assertThat(sanitized).doesNotContain("</span>");
		assertThat(sanitized).doesNotContain("<br");
		assertThat(sanitized).doesNotContain("<a href");
		assertThat(sanitized).doesNotContain("</a>");
		assertThat(sanitized).doesNotContain("&gt;");
		assertThat(sanitized).contains(
			"옵션ID(89379431593)은 쿠팡에 의해 '판매중지'된 상품입니다.");
		assertThat(sanitized).contains(
			"자세한 중지 사유와 재개 방법은 판매자님의 대표 메일로 전달되었으니 확인 부탁드립니다.");
		assertThat(sanitized).contains("WING>온라인 문의>상품수정/블라인드");
	}

	@Test
	@DisplayName("D-285: HTML 엔티티(gt/lt/amp/quot/apos/nbsp)를 풀어낸다")
	void decodesHtmlEntities() {
		String input = "WING&gt;온라인 &amp; 오프라인 &lt;문의&gt; &quot;확인&quot; &#39;예&#39; 등록&nbsp;완료";

		String sanitized = ProductMarketSyncService.sanitizeMarketMessage(input);

		assertThat(sanitized).isEqualTo("WING>온라인 & 오프라인 <문의> \"확인\" '예' 등록 완료");
	}

	@Test
	@DisplayName("D-285: 연속 공백·줄바꿈을 공백 하나로 접고 앞뒤를 자른다")
	void collapsesWhitespace() {
		String input = "  첫줄입니다.\n\n   여러   공백과\t탭이   섞였습니다.  ";

		String sanitized = ProductMarketSyncService.sanitizeMarketMessage(input);

		assertThat(sanitized).isEqualTo("첫줄입니다. 여러 공백과 탭이 섞였습니다.");
	}

	@Test
	@DisplayName("D-285: HTML 이 없는 평범한 메시지는 한 글자도 바뀌지 않는다")
	void plainMessageUnchanged() {
		String input = "400 Bad Request: {\"code\":\"BAD_REQUEST\",\"message\":\"판매가 항목은 10원 단위로 입력해 주세요.\"}";

		String sanitized = ProductMarketSyncService.sanitizeMarketMessage(input);

		assertThat(sanitized).isEqualTo(input);
	}

	@Test
	@DisplayName("D-285: 480자를 넘으면 잘라내고 말줄임표를 붙인다")
	void truncatesOverLimit() {
		String longPlainMessage = "가".repeat(600);

		String sanitized = ProductMarketSyncService.sanitizeMarketMessage(longPlainMessage);

		assertThat(sanitized).hasSize(481);
		assertThat(sanitized).endsWith("…");
		assertThat(sanitized).startsWith("가".repeat(480));
	}

	@Test
	@DisplayName("D-285: null 입력은 null 을 돌려준다 — rootMessage 쪽에서 클래스명으로 대체한다")
	void nullInputYieldsNull() {
		assertThat(ProductMarketSyncService.sanitizeMarketMessage(null)).isNull();
	}
}
