package com.sbshop.agent.core.domain.sourcing.component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * iHerb 한글 상품명에서 <b>국내 검색 키워드</b>를 뽑는다.
 *
 * <p>iHerb 상품명은 이런 모양이다:
 * <pre>
 *   California Gold Nutrition (캘리포니아골드뉴트리션), 비타민D3 + K2(MK-7), 베지 캡슐 180정
 * </pre>
 * 이걸 그대로 네이버에 넣으면 검색량 0이 나온다. 사람이 실제로 검색하는 건 "비타민D3 K2" 같은
 * 성분·제형 부분이다. 그래서:
 * <ol>
 *   <li>선두 브랜드 블록(영문명 + 괄호 한글명)을 떼고</li>
 *   <li>말미 수량·용량 토큰("베지 캡슐 180정")을 떼고</li>
 *   <li>남은 핵심부를 정리한다</li>
 * </ol>
 *
 * <p>키워드 추출이 실패하면 검색량 신호가 0이 되어 좋은 상품이 조용히 낮은 점수를 받는다.
 * 그래서 핵심부가 비면 원본 상품명 전체로 폴백한다(0점보다 부정확한 값이 낫다).
 */
public final class SearchKeywordDeriver {

	/** 말미에서 떼어낼 제형·수량 표현. */
	private static final List<String> TRAILING_FORM_TOKENS = List.of(
		"베지 캡슐", "베지캡슐", "식물성 캡슐", "소프트젤", "정제", "타블렛", "캡슐", "정",
		"구미", "젤리", "파우더", "분말", "액상", "티백", "포", "개입", "개");

	/** 검색어에서 의미 없는 기호. */
	private static final String NOISE = "[®™©·•]";

	private SearchKeywordDeriver() {
	}

	/** 검색량·경쟁도 조회에 쓸 대표 키워드 1개. */
	public static String derive(String nameKo, String brand) {
		if (nameKo == null || nameKo.isBlank())
			return brand != null ? brand : "";

		String core = stripBrandPrefix(nameKo, brand);
		core = stripQuantitySuffix(core);
		core = core.replaceAll(NOISE, " ")
			.replaceAll("[,+]", " ")
			.replaceAll("\\s+", " ")
			.trim();

		// 괄호 안 규격(MK-7 등)은 검색어로는 노이즈에 가깝지만, 떼면 동음이의가 생기는 경우가 있어
		// 괄호만 없애고 내용은 남긴다.
		core = core.replaceAll("[()\\[\\]]", " ").replaceAll("\\s+", " ").trim();

		if (core.length() < 2) {
			// 핵심부 추출 실패 — 원본으로 폴백(신호 0보다 부정확한 신호가 낫다).
			return nameKo.replaceAll(NOISE, " ").replaceAll("\\s+", " ").trim();
		}
		return core;
	}

	/**
	 * 마켓 검색 키워드 후보들. 대표 키워드 + 브랜드 결합형 + 토큰 조합.
	 * LLM 생성 키워드와 합쳐 최종 키워드 목록을 만든다.
	 */
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

	/** "California Gold Nutrition (캘리포니아골드뉴트리션)" → "캘리포니아골드뉴트리션" */
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
		// "브랜드영문 (브랜드한글)," 블록 제거 — 첫 쉼표까지가 브랜드 블록인 경우가 대부분이다.
		if (brand != null && !brand.isBlank() && s.regionMatches(true, 0, brand, 0, brand.length())) {
			s = s.substring(brand.length());
			// 뒤따르는 "(한글브랜드)" 제거
			s = s.replaceFirst("^\\s*\\([^)]*\\)", "");
			s = s.replaceFirst("^\\s*,", "");
		} else {
			int comma = s.indexOf(',');
			// 첫 쉼표 앞에 괄호 한글 브랜드가 있으면 브랜드 블록으로 보고 제거
			if (comma > 0 && s.substring(0, comma).contains("("))
				s = s.substring(comma + 1);
		}
		return s.trim();
	}

	private static String stripQuantitySuffix(String core) {
		// (1) 말미 괄호 규격을 먼저 통째로 제거한다.
		//     "…소프트젤 100정(소프트젤당 1,100mg)"의 괄호 안 쉼표가 남아 있으면 아래 세그먼트 분리가
		//     "1"과 "100mg)"로 쪼개져, 수량 꼬리가 아니라 숫자 파편이 키워드에 남는다.
		String s = core.replaceAll("\\([^)]*\\)\\s*$", "").trim();

		// (2) 쉼표 세그먼트 단위로 말미의 제형·수량 조각을 반복 제거한다
		//     ("콜라겐 펩타이드, 무맛, 907g" → 907g만 떨어져 나간다).
		List<String> segments = new ArrayList<>(List.of(s.split(",")));
		while (segments.size() > 1 && looksLikeQuantity(segments.get(segments.size() - 1).trim())) {
			segments.remove(segments.size() - 1);
		}
		s = String.join(" ", segments);

		// (3) 세그먼트로 안 떨어진 말미 숫자+단위 제거 ("… 180정")
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
