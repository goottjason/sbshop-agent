package com.sbshop.agent.core.application.sourcing.customs;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 반입차단 성분의 <b>별칭 보강 시드</b>.
 *
 * <p>식약처 목록은 대표명 하나만 준다. 그런데 상품 성분표는 같은 성분을 다른 표기로 적는 일이
 * 흔하다 — iHerb 한글 성분표는 특히 음차 표기가 흔들린다("요힘빈" vs "요힘베" vs "Yohimbe").
 * 대표명만 대조하면 실제로 차단해야 할 상품을 상당수 놓친다.
 *
 * <p>여기 있는 것은 <b>같은 성분의 다른 이름</b>일 뿐, 새로운 규제 판단이 아니다.
 * 규제 대상 여부는 전적으로 식약처 목록이 정한다 — 이 시드는 목록에 이미 있는 항목의
 * 매칭률만 올린다({@link #aliasesFor}는 목록에 없는 이름에는 아무것도 돌려주지 않는다).
 */
public final class IngredientAliasSeed {

	/** 대표명(소문자, 공백제거) → 별칭 목록. */
	private static final Map<String, List<String>> ALIASES = Map.ofEntries(
		Map.entry("요힘빈", List.of("요힘베", "요힘비", "Yohimbe", "Yohimbe bark", "요힘베껍질")),
		Map.entry("멜라토닌", List.of("멜라토닌", "Melatonin", "N-아세틸-5-메톡시트립타민")),
		Map.entry("에페드린", List.of("에페드라", "Ephedra", "슈도에페드린", "Pseudoephedrine")),
		Map.entry("마황", List.of("에페드라", "Ephedra", "Ma Huang", "마황추출물")),
		Map.entry("카바카바", List.of("카바", "Kava", "Kava kava", "Piper methysticum")),
		Map.entry("크라톰", List.of("Kratom", "미트라지나 스페시오사", "Mitragyna speciosa")),
		Map.entry("시부트라민", List.of("Sibutramine", "시부트라민염산염")),
		Map.entry("대마", List.of("Cannabis", "칸나비디올", "Cannabidiol", "CBD", "헴프추출물")),
		Map.entry("테오브로민", List.of("Theobromine", "테오브로마")),
		Map.entry("갈란타민", List.of("Galantamine", "갈란타민브롬화수소산염")),
		Map.entry("이카리틴", List.of("Icaritin", "이카린", "Icariin")),
		Map.entry("에보디아민", List.of("Evodiamine", "에보디아", "Evodia")));

	private IngredientAliasSeed() {
	}

	/**
	 * 대표명에 대한 별칭. 시드에 없으면 빈 목록.
	 *
	 * <p>부분일치가 아니라 <b>정확 일치</b>로 찾는다 — "대마"가 "대마씨유"에 걸려 엉뚱한 별칭이
	 * 붙는 것을 막기 위함이다. (한글명 원문의 괄호 설명은 제거하고 비교한다.)
	 */
	public static List<String> aliasesFor(String nameKo) {
		if (nameKo == null)
			return List.of();
		String key = nameKo.replaceAll("[(\\[{（【].*", "")
			.replaceAll("\\s+", "")
			.toLowerCase(Locale.ROOT);
		return ALIASES.getOrDefault(key, List.of());
	}
}
