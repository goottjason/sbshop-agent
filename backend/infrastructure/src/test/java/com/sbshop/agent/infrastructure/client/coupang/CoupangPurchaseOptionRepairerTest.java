package com.sbshop.agent.infrastructure.client.coupang;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.infrastructure.client.coupang.component.CoupangPurchaseOptionRepairer;
import com.sbshop.agent.infrastructure.client.coupang.dto.CoupangAttributeMeta;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CoupangPurchaseOptionRepairerTest {

	private final CoupangPurchaseOptionRepairer repairer = new CoupangPurchaseOptionRepairer();

	private static CoupangAttributeMeta option(String type, String group, String... units) {
		return new CoupangAttributeMeta(type, "NUMBER", List.of(units), true, true, group);
	}

	private static final List<CoupangAttributeMeta> SUPPLEMENT_META = List.of(
		option("개당 캡슐/정", "1", "정", "회분"),
		option("개당 중량", "1", "g", "kg"),
		option("개당 용량", "1", "L", "ml"),
		option("수량", "NONE", "개", "박스", "세트"),
		new CoupangAttributeMeta("제품 형태", "STRING", List.of(), false, true, "NONE"));

	private static Map<String, Object> attr(String type, String value, String exposed) {
		Map<String, Object> a = new LinkedHashMap<>();
		a.put("attributeTypeName", type);
		a.put("attributeValueName", value);
		a.put("exposed", exposed);
		a.put("editable", true);
		return a;
	}

	private static List<Map<String, Object>> attrs(Map<String, Object>... entries) {
		return new ArrayList<>(List.of(entries));
	}

	private static String valueOf(CoupangPurchaseOptionRepairer.Result result, String type) {
		return result.attributes().stream()
			.filter(a -> type.equals(a.get("attributeTypeName")))
			.map(a -> String.valueOf(a.get("attributeValueName")))
			.findFirst().orElse(null);
	}

	private static long countOf(CoupangPurchaseOptionRepairer.Result result, String type) {
		return result.attributes().stream().filter(a -> type.equals(a.get("attributeTypeName"))).count();
	}

	@Test
	@DisplayName("D-340: 값이 빈 속성과 카테고리 메타에 없는 폐지 속성을 제거한다")
	void removesBlankAndRetiredAttributes() {
		var result = repairer.repair(attrs(
			attr("개당 중량", "", "NONE"),
			attr("제품 형태", "", "NONE"),
			attr("개당 용량/중량/정", "90정", "EXPOSED"),
			attr("수량", "1개", "EXPOSED")), SUPPLEMENT_META, null, "나우푸드 비타민 D 90정", null);

		assertThat(countOf(result, "개당 용량/중량/정")).isZero();
		assertThat(countOf(result, "제품 형태")).isZero();
		assertThat(result.removed()).contains("개당 용량/중량/정(폐지)", "제품 형태(빈값)", "개당 중량(빈값)");
		assertThat(valueOf(result, "개당 캡슐/정")).isEqualTo("90정");
		assertThat(result.missing()).isEmpty();
	}

	@Test
	@DisplayName("D-340: 캡슐은 단위 목록에 없으면 '정'으로 쓴다")
	void capsuleBecomesTablet() {
		var result = repairer.repair(attrs(), SUPPLEMENT_META, null, "1개 나우푸드 오메가3 120소프트젤", null);

		assertThat(valueOf(result, "개당 캡슐/정")).isEqualTo("120정");
		assertThat(result.filled()).contains("개당 캡슐/정=120정");
	}

	@Test
	@DisplayName("D-340: '(2개)' 접두어는 수량 2개, 없으면 1개")
	void quantityFromBundlePrefix() {
		var bundle = repairer.repair(attrs(), SUPPLEMENT_META, null, "(2개) 나우푸드 마그네슘 180정", null);
		var single = repairer.repair(attrs(), SUPPLEMENT_META, null, "나우푸드 마그네슘 180정", null);

		assertThat(valueOf(bundle, "수량")).isEqualTo("2개");
		assertThat(valueOf(single, "수량")).isEqualTo("1개");
	}

	@Test
	@DisplayName("D-340: 수량은 itemName 끝 'N개' → 이름 앞 '(N개)' → 1개 순으로 정한다")
	void quantityFromItemNameThenPrefix() {
		assertThat(valueOf(repairer.repair(attrs(), SUPPLEMENT_META, "120캡슐 2개", "나우푸드 마그네슘", null), "수량"))
			.isEqualTo("2개");
		assertThat(valueOf(repairer.repair(attrs(), SUPPLEMENT_META, "2개", "나우푸드 마그네슘", null), "수량"))
			.isEqualTo("2개");
		assertThat(valueOf(repairer.repair(attrs(), SUPPLEMENT_META, "단일상품", "(3팩) 나우푸드 마그네슘", null), "수량"))
			.isEqualTo("3개");
		assertThat(valueOf(repairer.repair(attrs(), SUPPLEMENT_META, "(2개) 나우푸드 마그네슘", "(2개) 나우푸드 마그네슘",
			null), "수량")).isEqualTo("2개");
		assertThat(valueOf(repairer.repair(attrs(), SUPPLEMENT_META, "단일상품", "나우푸드 마그네슘", null), "수량"))
			.isEqualTo("1개");
	}

	@Test
	@DisplayName("D-340: 한글명에 없으면 영문 원래 이름에서 추출한다(캡슐·fl oz·oz·lbs)")
	void fallsBackToOriginalName() {
		var caps = repairer.repair(attrs(), SUPPLEMENT_META, null, "나우푸드 아슈와간다",
			"NOW Foods, Ashwagandha, 90 Veg Capsules");
		var floz = repairer.repair(attrs(), List.of(option("개당 용량", "1", "L", "ml")), null, "나우푸드 글리세린",
			"NOW Foods, Vegetable Glycerin, 16 fl oz");
		var oz = repairer.repair(attrs(), List.of(option("개당 중량", "1", "g", "kg")), null, "나우푸드 파우더",
			"NOW Foods, Powder, 5.64 oz");
		var lbs = repairer.repair(attrs(), List.of(option("개당 중량", "1", "g", "kg")), null, "나우푸드 프로틴",
			"NOW Foods, Whey Protein, 2 lbs");

		assertThat(valueOf(caps, "개당 캡슐/정")).isEqualTo("90정");
		assertThat(valueOf(floz, "개당 용량")).isEqualTo("473ml");
		assertThat(valueOf(oz, "개당 중량")).isEqualTo("160g");
		assertThat(valueOf(lbs, "개당 중량")).isEqualTo("907g");
	}

	@Test
	@DisplayName("D-340: mg 는 개당 중량으로 쓰지 않는다")
	void milligramIsNotWeight() {
		var result = repairer.repair(attrs(), List.of(option("개당 중량", "1", "g", "kg")),
			null, "나우푸드 브로멜라인 500mg", "NOW Foods, Bromelain, 500 mg");

		assertThat(countOf(result, "개당 중량")).isZero();
		assertThat(result.missing()).containsExactly("개당 중량");
	}

	@Test
	@DisplayName("D-340: 같은 그룹은 하나만 채운다")
	void fillsOnlyOneMemberPerGroup() {
		var result = repairer.repair(attrs(), SUPPLEMENT_META, null, "나우푸드 프로틴 바 12개입 60g 120정", null);

		long groupFilled = countOf(result, "개당 캡슐/정") + countOf(result, "개당 중량") + countOf(result, "개당 용량");
		assertThat(groupFilled).isEqualTo(1);
		assertThat(valueOf(result, "개당 캡슐/정")).isEqualTo("120정");
	}

	@Test
	@DisplayName("D-340: 이미 값이 있는 유효 속성은 바꾸지 않고, 그룹이 충족돼 있으면 추가하지 않는다")
	void keepsValidExistingAttributes() {
		var result = repairer.repair(attrs(attr("개당 중량", "5L", "EXPOSED"), attr("수량", "3개", "EXPOSED")),
			SUPPLEMENT_META, null, "(2개) 나우푸드 웨이 프로틴 907g 30정", null);

		assertThat(valueOf(result, "개당 중량")).isEqualTo("5L");
		assertThat(valueOf(result, "수량")).isEqualTo("3개");
		assertThat(countOf(result, "개당 캡슐/정")).isZero();
		assertThat(result.filled()).isEmpty();
		assertThat(result.removed()).isEmpty();
	}

	@Test
	@DisplayName("D-340: GET 쓰레기값(타입명과 같은 값·단위만 있는 값)은 제거하고 다시 추출한다")
	void replacesPlaceholderValues() {
		var result = repairer.repair(attrs(attr("수량", "수량", "EXPOSED"), attr("개당 캡슐/정", "정", "EXPOSED"),
			attr("개당 중량", "g", "EXPOSED")), SUPPLEMENT_META, "2개", "나우푸드 비타민 C 100정", null);

		assertThat(result.removed()).contains("수량(쓰레기값)", "개당 캡슐/정(쓰레기값)", "개당 중량(쓰레기값)");
		assertThat(valueOf(result, "수량")).isEqualTo("2개");
		assertThat(valueOf(result, "개당 캡슐/정")).isEqualTo("100정");
		assertThat(countOf(result, "개당 중량")).isZero();
		assertThat(result.filled()).contains("수량=2개", "개당 캡슐/정=100정");
	}

	@Test
	@DisplayName("D-340: 정상값 '3개'는 쓰레기값으로 보지 않는다")
	void keepsRealQuantityValue() {
		var result = repairer.repair(attrs(attr("수량", "3개", "EXPOSED")), SUPPLEMENT_META, "2개",
			"나우푸드 비타민 C 100정", null);

		assertThat(valueOf(result, "수량")).isEqualTo("3개");
		assertThat(result.removed()).isEmpty();
		assertThat(result.filled()).doesNotContain("수량=2개");
	}

	@Test
	@DisplayName("D-340: 추출할 수 없으면 충족 못 한 그룹 멤버를 missing 으로 돌려준다")
	void reportsMissingWhenUnextractable() {
		var result = repairer.repair(attrs(), SUPPLEMENT_META, null, "나우푸드 프로바이오틱", "NOW Foods, Probiotic");

		assertThat(result.missing()).containsExactly("개당 캡슐/정", "개당 중량", "개당 용량");
		assertThat(valueOf(result, "수량")).isEqualTo("1개");
	}

	@Test
	@DisplayName("D-340: 새 속성은 EXPOSED·editable 형식으로 넣고, 결과 단위가 사용 단위에 없으면 버린다")
	void newAttributeFormatAndUnitFilter() {
		var result = repairer.repair(attrs(), List.of(option("개당 용량", "1", "ml"), option("개당 중량", "1", "kg")),
			null, "나우푸드 오일 2L 500g", null);

		Map<String, Object> added = result.attributes().get(0);
		assertThat(added).containsEntry("attributeTypeName", "개당 용량").containsEntry("attributeValueName", "2000ml")
			.containsEntry("exposed", "EXPOSED").containsEntry("editable", true);
		assertThat(result.attributes()).hasSize(1);

		var discarded = repairer.repair(attrs(), List.of(option("개당 중량", "1", "kg")), null, "나우푸드 파우더 500g", null);
		assertThat(discarded.attributes()).isEmpty();
		assertThat(discarded.missing()).containsExactly("개당 중량");
	}
}
