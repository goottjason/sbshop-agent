package com.sbshop.agent.core.domain.sourcing;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.domain.sourcing.component.SearchKeywordDeriver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SearchKeywordDeriverTest {
	@Test
	@DisplayName("브랜드 블록과 말미 수량을 떼고 성분 핵심부만 남긴다")
	void stripsBrandBlockAndQuantity() {
		String keyword = SearchKeywordDeriver.derive(
			"California Gold Nutrition (캘리포니아골드뉴트리션), 비타민D3 + K2(MK-7), 베지 캡슐 180정",
			"California Gold Nutrition");

		assertThat(keyword).doesNotContain("California Gold Nutrition");
		assertThat(keyword).doesNotContain("180");
		assertThat(keyword).contains("비타민D3");
		assertThat(keyword).contains("K2");
	}

	@Test
	@DisplayName("브랜드 인자가 없어도 괄호 한글 브랜드 블록을 인식해 떼어낸다")
	void stripsBrandBlockWithoutBrandHint() {
		String keyword = SearchKeywordDeriver.derive(
			"Ritual (리추얼), Synbiotic+®, 민트, 베지 캡슐 30정", null);

		assertThat(keyword).doesNotContain("리추얼");
		assertThat(keyword).doesNotContain("®");
		assertThat(keyword).contains("Synbiotic");
	}

	@Test
	@DisplayName("소프트젤 수량 표기도 떼어낸다")
	void stripsSoftgelQuantity() {
		String keyword = SearchKeywordDeriver.derive(
			"California Gold Nutrition (캘리포니아골드뉴트리션), 오메가3 프리미엄 피쉬 오일, "
				+ "피쉬 젤라틴 소프트젤 100정(소프트젤당 1,100mg)",
			"California Gold Nutrition");

		assertThat(keyword).contains("오메가3");
		assertThat(keyword).doesNotContain("캘리포니아골드뉴트리션");
	}

	@Test
	@DisplayName("한글 브랜드를 따로 뽑을 수 있다")
	void extractsKoreanBrand() {
		assertThat(SearchKeywordDeriver.extractKoreanBrand(
			"California Gold Nutrition (캘리포니아골드뉴트리션), 비타민D3"))
			.isEqualTo("캘리포니아골드뉴트리션");

		assertThat(SearchKeywordDeriver.extractKoreanBrand("Now Foods (NOW), Vitamin C")).isNull();
	}

	@Test
	@DisplayName("핵심부 추출에 실패하면 원본으로 폴백한다 — 신호 0보다 부정확한 신호가 낫다")
	void fallsBackToOriginalWhenCoreEmpty() {
		String keyword = SearchKeywordDeriver.derive("루테인", "루테인");

		assertThat(keyword).isNotBlank();
		assertThat(keyword).contains("루테인");
	}

	@Test
	@DisplayName("null/빈 이름은 브랜드로 대체하고, 둘 다 없으면 빈 문자열")
	void handlesNulls() {
		assertThat(SearchKeywordDeriver.derive(null, "Now Foods")).isEqualTo("Now Foods");
		assertThat(SearchKeywordDeriver.derive(null, null)).isEmpty();
	}

	@Test
	@DisplayName("키워드 후보는 대표어와 축약형을 포함하고 2자 미만은 버린다")
	void buildsCandidateList() {
		var candidates = SearchKeywordDeriver.deriveCandidates(
			"California Gold Nutrition (캘리포니아골드뉴트리션), 비타민D3 + K2(MK-7), 베지 캡슐 180정",
			"California Gold Nutrition");

		assertThat(candidates).isNotEmpty();
		assertThat(candidates).allMatch(s -> s.length() >= 2);
		assertThat(candidates).anyMatch(s -> s.contains("캘리포니아골드뉴트리션"));
	}
}
