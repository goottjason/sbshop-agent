package com.sbshop.agent.core.domain.sourcing.component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class SearchKeywordDeriver {
	private static final List<String> TRAILING_FORM_TOKENS = List.of(
		"베지 캡슐", "베지캡슐", "식물성 캡슐", "소프트젤", "정제", "타블렛", "캡슐", "정",
		"구미", "젤리", "파우더", "분말", "액상", "티백", "포", "개입", "개");

	private static final String NOISE = "[®™©·•]";

	private SearchKeywordDeriver() {}

	public static String derive(String nameKo, String brand) {
		if (nameKo == null || nameKo.isBlank())
			return brand != null ? brand : "";

		String core = stripBrandPrefix(nameKo, brand);
		core = stripQuantitySuffix(core);
		core = core.replaceAll(NOISE, " ")
			.replaceAll("[,+]", " ")
			.replaceAll("\\s+", " ")
			.trim();

		core = core.replaceAll("[()\\[\\]]", " ").replaceAll("\\s+", " ").trim();

		if (core.length() < 2) {
			return nameKo.replaceAll(NOISE, " ").replaceAll("\\s+", " ").trim();
		}
		return core;
	}

	public static List<String> deriveCandidates(String nameKo, String brand) {
		Set<String> out = new LinkedHashSet<>();
		String core = derive(nameKo, brand);
		if (!core.isBlank())
			out.add(core);

		String brandKo = extractKoreanBrand(nameKo);
		if (brandKo != null && !core.isBlank())
			out.add(brandKo + " " + firstTokens(core, 2));

		if (!core.isBlank()) {
			out.add(firstTokens(core, 1));
			out.add(firstTokens(core, 2));
		}
		List<String> result = new ArrayList<>();
		for (String s : out) {
			String t = s.trim();
			if (t.length() >= 2)
				result.add(t);
		}
		return result;
	}

	public static String deriveSpecific(String nameKo, String brand) {
		String core = derive(nameKo, brand);
		String brandKo = extractKoreanBrand(nameKo);
		if (brandKo != null && !core.isBlank())
			return brandKo + " " + core;
		return core;
	}

	public static String extractKoreanBrand(String nameKo) {
		if (nameKo == null)
			return null;
		int open = nameKo.indexOf('(');
		int close = nameKo.indexOf(')');
		if (open < 0 || close <= open)
			return null;
		String inner = nameKo.substring(open + 1, close).trim();
		return inner.matches(".*[가-힣].*") ? inner : null;
	}

	private static String stripBrandPrefix(String nameKo, String brand) {
		String s = nameKo;

		if (brand != null && !brand.isBlank() && s.regionMatches(true, 0, brand, 0, brand.length())) {
			s = s.substring(brand.length());

			s = s.replaceFirst("^\\s*\\([^)]*\\)", "");
			s = s.replaceFirst("^\\s*,", "");
		} else {
			int comma = s.indexOf(',');

			if (comma > 0 && s.substring(0, comma).contains("("))
				s = s.substring(comma + 1);
		}
		return s.trim();
	}

	private static String stripQuantitySuffix(String core) {
		String s = core.replaceAll("\\([^)]*\\)\\s*$", "").trim();

		List<String> segments = new ArrayList<>(List.of(s.split(",")));
		while (segments.size() > 1 && looksLikeQuantity(segments.get(segments.size() - 1).trim())) {
			segments.remove(segments.size() - 1);
		}
		s = String.join(" ", segments);

		s = s.replaceAll("\\s*[0-9][0-9,.]*\\s*(정|개|캡슐|소프트젤|포|정제|g|mg|ml|oz)\\s*$", "");
		return s.replaceAll("\\s+", " ").trim();
	}

	private static boolean looksLikeQuantity(String tail) {
		if (tail.matches(".*\\d.*"))
			return true;
		for (String token : TRAILING_FORM_TOKENS) {
			if (tail.contains(token))
				return true;
		}
		return false;
	}

	private static String firstTokens(String s, int n) {
		String[] parts = s.split("\\s+");
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < Math.min(n, parts.length); i++) {
			if (i > 0)
				sb.append(' ');
			sb.append(parts[i]);
		}
		return sb.toString();
	}
}
