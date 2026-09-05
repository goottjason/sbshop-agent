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
	@DisplayName("D-298: 운영 코드표가 공식 엑셀 기준으로 채워져 있다 "
		+ "— 건기식 891032 13항목 · 가공식품 891031 11항목")
	void productionTableIsResolved() {
		NoticeSpec health = ElevenstProductNotice.specOf(NoticeType.HEALTH_FUNCTIONAL_FOOD);
		NoticeSpec processed = ElevenstProductNotice.specOf(NoticeType.PROCESSED_FOOD);

		assertThat(health.isResolved()).isTrue();
		assertThat(health.typeCode()).isEqualTo("891032");
		assertThat(health.items()).hasSize(13);

		assertThat(processed.isResolved()).isTrue();
		assertThat(processed.typeCode()).isEqualTo("891031");
		assertThat(processed.items()).hasSize(11);
	}

	@Test
	@DisplayName("D-298: 표의 항목코드는 유형 안에서 유일하고 라벨과 함께 비어 있지 않다")
	void tableCodesAreUniqueAndNonBlank() {
		for (NoticeType type : NoticeType.values()) {
			List<NoticeItem> items = ElevenstProductNotice.specOf(type).items();
			assertThat(items).allSatisfy(i -> {
				assertThat(i.code()).isNotBlank();
				assertThat(i.label()).isNotBlank();
			});
			assertThat(items.stream().map(NoticeItem::code).distinct().count())
				.as("유형 %s 의 항목코드 중복", type)
				.isEqualTo(items.size());
		}
	}

	@Test
	@DisplayName("D-298: 건기식 표의 실제 항목코드가 공식 엑셀 값과 일치한다 (표본)")
	void healthFunctionalFoodCodesMatchOfficialTable() {
		List<NoticeItem> items = ElevenstProductNotice.specOf(NoticeType.HEALTH_FUNCTIONAL_FOOD).items();
		Map<String, String> codeByLabel = items.stream()
			.collect(java.util.stream.Collectors.toMap(NoticeItem::label, NoticeItem::code));

		assertThat(codeByLabel).containsEntry("제품명", "176317774");
		assertThat(codeByLabel).containsEntry("기능정보", "23755783");
		assertThat(codeByLabel).containsEntry("소비자상담 관련 전화번호", "23756754");
		assertThat(codeByLabel).containsEntry("소비기한 및 보관방법", "23759354");
		assertThat(codeByLabel).containsEntry("영양정보", "23757103");
		assertThat(codeByLabel).containsEntry("소비자안전을 위한 주의사항", "176312674");
	}

	@Test
	@DisplayName("D-298: 운영 건기식 spec 으로 만든 블록은 13항목이고 '의약품이 아니다' 항목은 고정 문구로 채운다")
	void productionHealthBlockHasThirteenItemsAndFixedDrugDisclaimer() {
		String out = ElevenstProductNotice.buildBlock(
			ElevenstProductNotice.specOf(NoticeType.HEALTH_FUNCTIONAL_FOOD), Map.of());

		assertThat(out).contains("<type>891032</type>");
		assertThat(countItems(out)).isEqualTo(13);
		assertThat(out).contains("<code>23759747</code>"
			+ "<name><![CDATA[본 제품은 질병의 예방 및 치료를 위한 의약품이 아닙니다.]]></name>");
		assertThat(out).contains("<code>176317774</code><name><![CDATA[상세설명 참조]]></name>");
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
