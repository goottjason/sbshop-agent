package com.sbshop.agent.core.domain.sourcing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 반입차단 성분의 매칭 키 생성 규칙을 고정한다.
 *
 * <p>여기가 틀리면 통관 게이트가 조용히 무력화된다 — 차단 성분이 든 상품이 PASS로 흘러가
 * 주문받은 뒤 통관에서 폐기·반송된다. 특히 식약처 원문의 괄호 설명("카바카바(뿌리, 잎, 줄기)")을
 * 그대로 정규화하면 성분표의 "카바카바"와 절대 매칭되지 않는다.
 */
class BannedIngredientNormKeyTest {

	private List<String> keys(String ko, String en, List<String> aliases) {
		return BannedIngredient.of(ko, en, aliases, "사유", "TEST").normKeyList();
	}

	@Test
	@DisplayName("괄호 설명이 붙은 한글명은 '괄호 앞 머리부'도 키로 만든다")
	void stripsParentheticalIntoSeparateKey() {
		List<String> k = keys("카바카바(뿌리, 잎, 줄기)", "Kava kava", List.of());

		// 머리부 키가 없으면 성분표의 "카바카바"를 못 잡는다.
		assertThat(k).contains("카바카바");
		assertThat(k).contains("카바카바뿌리잎줄기");
	}

	@Test
	@DisplayName("법령 인용이 붙은 '대마(…)'도 머리부 키를 만든다")
	void handlesLegalCitationParenthetical() {
		List<String> k = keys("대마(「마약류 관리에 관한 법률」제2조제4호에 해당되는 것)",
			"Cannabis sativa L", List.of());

		assertThat(k).contains("대마");
		assertThat(k).contains("cannabissatival");
		// 영문 첫 토큰도 별도 키 — 성분표에 학명만 적히는 경우가 있다.
		assertThat(k).contains("cannabis");
	}

	@Test
	@DisplayName("영문 첫 토큰은 6자 이상일 때만 별도 키가 된다")
	void englishFirstTokenOnlyWhenLongEnough() {
		assertThat(keys("마황", "Ephedra herb", List.of())).contains("ephedra");
		// "Kava kava"의 "kava"(4자)는 짧아 오탐 위험이 크므로 단독 키로 만들지 않는다.
		assertThat(keys("카바카바", "Kava kava", List.of())).doesNotContain("kava");
	}

	@Test
	@DisplayName("별칭도 정규화되어 키에 포함된다")
	void includesAliases() {
		List<String> k = keys("요힘빈", "Yohimbine", List.of("요힘베", "Yohimbe bark"));

		assertThat(k).contains("요힘빈", "yohimbine", "요힘베", "yohimbebark");
	}

	@Test
	@DisplayName("1글자 키는 버린다 — 아무 성분에나 걸린다")
	void dropsSingleCharKeys() {
		assertThat(keys("차", "T", List.of())).isEmpty();
	}

	@Test
	@DisplayName("2글자 키는 살리되 약한 키로 표시한다(BLOCKED가 아니라 REVIEW 대상)")
	void twoCharKeysAreWeak() {
		assertThat(keys("대마", "Cannabis", List.of())).contains("대마");
		assertThat(BannedIngredient.isWeakKey("대마")).isTrue();
		assertThat(BannedIngredient.isWeakKey("요힘빈")).isFalse();
	}

	@Test
	@DisplayName("정규화는 공백·하이픈·기호를 지우고 소문자화한다")
	void normalizeStripsSeparators() {
		assertThat(BannedIngredient.normalize("MK-7")).isEqualTo("mk7");
		assertThat(BannedIngredient.normalize("요힘빈 추출물")).isEqualTo("요힘빈추출물");
		assertThat(BannedIngredient.normalize("4-Aminoantipyrine")).isEqualTo("4aminoantipyrine");
	}
}
