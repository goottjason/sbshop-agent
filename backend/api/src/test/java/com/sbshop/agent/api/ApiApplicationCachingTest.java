package com.sbshop.agent.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.EnableCaching;
import static org.assertj.core.api.Assertions.assertThat;

class ApiApplicationCachingTest {

	@Test
	@DisplayName("D-293: 캐싱이 켜져 있어야 한다 — @EnableCaching 없이는 @Cacheable 이 전부 무효다")
	void cachingIsEnabled() {
		assertThat(ApiApplication.class.isAnnotationPresent(EnableCaching.class))
			.as("@EnableCaching 이 빠지면 CoupangMetaService 의 카테고리 메타 캐시가 조용히 무효가 되어 "
				+ "발행마다 쿠팡 API 를 다시 부른다")
			.isTrue();
	}
}
