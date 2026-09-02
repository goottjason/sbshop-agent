package com.sbshop.agent.infrastructure.repository.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sbshop.agent.core.domain.order.enums.ClaimType;

class OrderRepositoryImplClaimTypeFilterTest {

	private final OrderRepositoryImpl repo = new OrderRepositoryImpl(null, null, null);

	@Test
	@DisplayName("claimTypes가 null이면 필터 없음(null)")
	void nullClaimTypes_noFilter() {
		assertThat(repo.claimTypeIn(null)).isNull();
	}

	@Test
	@DisplayName("claimTypes가 빈 리스트면 필터 없음(null)")
	void emptyClaimTypes_noFilter() {
		assertThat(repo.claimTypeIn(List.of())).isNull();
	}

	@Test
	@DisplayName("claimTypes가 있으면 claimData.claimType IN 필터가 적용된다(null 아님)")
	void presentClaimTypes_filterApplied() {
		assertThat(repo.claimTypeIn(List.of(ClaimType.CANCEL, ClaimType.RETURN))).isNotNull();
	}
}
