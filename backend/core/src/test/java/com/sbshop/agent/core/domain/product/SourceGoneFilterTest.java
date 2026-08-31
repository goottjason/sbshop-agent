package com.sbshop.agent.core.domain.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.domain.product.dto.ProductSearchCondition;
import com.sbshop.agent.core.domain.product.enums.SourceGoneFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 폐기 후보(원본 소멸)를 걸러 보기 위한 검색 조건.
 * 191건이 3,000건 속에 섞여 있어 관리자가 찾을 수 없었다.
 */
class SourceGoneFilterTest {

	@Test
	@DisplayName("지정하지 않으면 전체를 본다 — 기존 화면 동작이 바뀌지 않는다")
	void defaultsToAll() {
		assertThat(ProductSearchCondition.none().sourceGone()).isEqualTo(SourceGoneFilter.ALL);
	}

	@Test
	@DisplayName("null 을 넘겨도 전체로 본다 — 프론트가 값을 안 보내는 경우")
	void nullBecomesAll() {
		assertThat(ProductSearchCondition.builder().sourceGone(null).build().sourceGone())
			.isEqualTo(SourceGoneFilter.ALL);
	}

	@Test
	@DisplayName("폐기 후보만 / 정상만 을 구분해 담는다")
	void keepsExplicitChoice() {
		assertThat(ProductSearchCondition.builder().sourceGone(SourceGoneFilter.GONE_ONLY).build()
			.sourceGone()).isEqualTo(SourceGoneFilter.GONE_ONLY);
		assertThat(ProductSearchCondition.builder().sourceGone(SourceGoneFilter.ALIVE_ONLY).build()
			.sourceGone()).isEqualTo(SourceGoneFilter.ALIVE_ONLY);
	}
}
