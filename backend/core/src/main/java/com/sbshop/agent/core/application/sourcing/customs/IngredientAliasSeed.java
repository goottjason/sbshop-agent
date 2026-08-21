package com.sbshop.agent.core.application.sourcing.customs;

import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class IngredientAliasSeed {
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

	private IngredientAliasSeed() {}

	public static List<String> aliasesFor(String nameKo) {
		if (nameKo == null)
			return List.of();
		String key = nameKo.replaceAll("[(\\[{（【].*", "")
			.replaceAll("\\s+", "")
			.toLowerCase(Locale.ROOT);
		return ALIASES.getOrDefault(key, List.of());
	}
}
