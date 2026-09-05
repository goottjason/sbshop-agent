package com.sbshop.agent.infrastructure.client.elevenst.component;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.infrastructure.client.elevenst.component.ElevenstProductNotice.NoticeItem;
import com.sbshop.agent.infrastructure.client.elevenst.component.ElevenstProductNotice.NoticeSpec;
import com.sbshop.agent.infrastructure.client.elevenst.component.ElevenstProductNotice.NoticeType;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ElevenstProductNoticeTest {

	private static final NoticeSpec RESOLVED = new NoticeSpec("07", List.of(
		new NoticeItem("0701", "제품명"),
		new NoticeItem("0702", "내용량 및 원료명"),
		new NoticeItem("0703", "소비자상담 관련 전화번호")));

	private static final String XML_WITHOUT_NOTICE = "<?xml version=\"1.0\" encoding=\"euc-kr\" standalone=\"yes\"?>"
		+ "<Product><prdNo>3168912619</prdNo>"
		+ "<prdNm>Whole World Botanicals Royal Maca 500mg 180캡슐</prdNm>"
		+ "<dispCtgrNo>1127358</dispCtgrNo><selStatCd>103</selStatCd>"
		+ "<ProductTag/><nResult>0</nResult></Product>";

	private static int countItems(String xml) {
		Matcher m = Pattern.compile("<item>").matcher(xml);
		int n = 0;
		while (m.find()) {
			n++;
		}
		return n;
	}

	private static String block(String xml) {
		Matcher m = Pattern.compile("(?s)<ProductNotification>.*?</ProductNotification>").matcher(xml);
		return m.find() ? m.group() : "";
	}

	@Test
	@DisplayName("D-298: 고시 블록은 현행 규격 형태(type + item{code,name})로 만든다")
	void buildsBlockInCurrentSpecShape() {
		String out = ElevenstProductNotice.buildBlock(RESOLVED, Map.of(
			"제품명", "로얄 마카",
			"내용량 및 원료명", "500mg 180캡슐",
			"소비자상담 관련 전화번호", "070-0000-0000"));

		assertThat(out).startsWith("<ProductNotification>");
		assertThat(out).contains("<type>07</type>");
		assertThat(out).contains("<item><code>0701</code><name><![CDATA[로얄 마카]]></name></item>");
		assertThat(out).contains("<item><code>0702</code><name><![CDATA[500mg 180캡슐]]></name></item>");
		assertThat(out).contains("<item><code>0703</code><name><![CDATA[070-0000-0000]]></name></item>");
		assertThat(out).endsWith("</ProductNotification>");
		assertThat(out).doesNotContain("<pdNo>");
		assertThat(out).doesNotContain("ProductNotificationItem");
	}

	@Test
	@DisplayName("D-298: 주입한 item 개수는 유형 규격의 항목 개수와 정확히 같다 — 개수 불일치가 결함의 본체다")
	void injectedItemCountMatchesSpec() {
		String out = ElevenstProductNotice.inject(XML_WITHOUT_NOTICE, RESOLVED, Map.of());

		assertThat(countItems(out)).isEqualTo(RESOLVED.items().size());
	}

	@Test
	@DisplayName("D-298: 값이 없는 항목은 '상세설명 참조' 로 채운다 — 항목을 빼면 개수가 깨진다")
	void fillsMissingValuesWithPlaceholder() {
		String out = ElevenstProductNotice.buildBlock(RESOLVED, Map.of("제품명", "로얄 마카"));

		assertThat(out).contains("<code>0701</code><name><![CDATA[로얄 마카]]></name>");
		assertThat(out).contains("<code>0702</code><name><![CDATA[상세설명 참조]]></name>");
		assertThat(out).contains("<code>0703</code><name><![CDATA[상세설명 참조]]></name>");
		assertThat(countItems(out)).isEqualTo(3);
	}

	@Test
	@DisplayName("D-298: 고시 블록이 없는 실측 GET XML(실패상품 3168912619)에 블록을 </Product> 앞에 넣는다")
	void injectsIntoRealXmlThatHasNoNoticeBlock() {
		assertThat(XML_WITHOUT_NOTICE).doesNotContain("ProductNotification");

		String out = ElevenstProductNotice.inject(XML_WITHOUT_NOTICE, RESOLVED, Map.of());

		assertThat(out).contains("<ProductNotification>");
		assertThat(out).endsWith("</Product>");
		assertThat(out.indexOf("<ProductNotification>")).isLessThan(out.indexOf("</Product>"));
		assertThat(out).contains("<prdNo>3168912619</prdNo>");
		assertThat(out).contains("<dispCtgrNo>1127358</dispCtgrNo>");
	}

	@Test
	@DisplayName("D-298: 이미 낡은 고시 블록이 있으면 교체한다 — 두 벌이 남으면 개수가 또 깨진다")
	void replacesStaleBlockInsteadOfAppending() {
		String stale = XML_WITHOUT_NOTICE.replace("</Product>",
			"<ProductNotification><pdNo>1</pdNo>"
				+ "<ProductNotificationItem><itemName>제품명</itemName><itemValue>옛값</itemValue>"
				+ "</ProductNotificationItem></ProductNotification></Product>");

		String out = ElevenstProductNotice.inject(stale, RESOLVED, Map.of());

		assertThat(out).doesNotContain("ProductNotificationItem");
		assertThat(out).doesNotContain("옛값");
		assertThat(out).doesNotContain("<pdNo>");
		assertThat(countItems(out)).isEqualTo(3);
		assertThat(block(out)).contains("<type>07</type>");
	}

	@Test
	@DisplayName("D-298 안전장치: 코드표가 아직 안 채워진 유형은 주입하지 않고 XML 을 그대로 둔다 "
		+ "— 추측 코드를 라이브 상품 1,681건에 쓰는 것을 막는다")
	void unresolvedSpecIsInert() {
		NoticeSpec blankType = new NoticeSpec("", List.of(new NoticeItem("0701", "제품명")));
		NoticeSpec noItems = new NoticeSpec("07", List.of());
		NoticeSpec blankItemCode = new NoticeSpec("07", List.of(new NoticeItem("", "제품명")));

		assertThat(blankType.isResolved()).isFalse();
		assertThat(noItems.isResolved()).isFalse();
		assertThat(blankItemCode.isResolved()).isFalse();
		assertThat(RESOLVED.isResolved()).isTrue();

		for (NoticeSpec spec : List.of(blankType, noItems, blankItemCode)) {
			assertThat(ElevenstProductNotice.buildBlock(spec, Map.of())).isEmpty();
			assertThat(ElevenstProductNotice.inject(XML_WITHOUT_NOTICE, spec, Map.of()))
				.isEqualTo(XML_WITHOUT_NOTICE);
		}
	}

	@Test
	@DisplayName("D-298: 운영 코드표는 아직 미확보 상태다 — 표가 도착하면 이 테스트가 빨개져서 갱신을 강제한다")
	void productionTableIsStillUnresolved() {
		for (NoticeType type : NoticeType.values()) {
			assertThat(ElevenstProductNotice.specOf(type).isResolved())
				.as("유형 %s 의 코드표가 채워졌다면 이 테스트와 주입 경로 테스트를 함께 갱신하라", type)
				.isFalse();
		}
	}

	@Test
	@DisplayName("D-298: 건기식·가공식품 두 유형 자리가 표에 준비돼 있다")
	void tableHasSlotsForBothFoodTypes() {
		assertThat(NoticeType.values())
			.containsExactlyInAnyOrder(NoticeType.HEALTH_FUNCTIONAL_FOOD, NoticeType.PROCESSED_FOOD);
		assertThat(ElevenstProductNotice.specOf(NoticeType.HEALTH_FUNCTIONAL_FOOD)).isNotNull();
		assertThat(ElevenstProductNotice.specOf(NoticeType.PROCESSED_FOOD)).isNotNull();
	}
}
