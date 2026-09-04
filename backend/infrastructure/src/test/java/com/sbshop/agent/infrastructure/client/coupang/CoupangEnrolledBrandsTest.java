package com.sbshop.agent.infrastructure.client.coupang;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.infrastructure.client.coupang.client.CoupangRestClient;
import com.sbshop.agent.infrastructure.client.coupang.component.CoupangBrandLookupService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CoupangEnrolledBrandsTest {

	@Mock
	private CoupangRestClient restClient;

	private CoupangBrandLookupService service;

	@BeforeEach
	void setUp() {
		service = new CoupangBrandLookupService(restClient, new ObjectMapper());
	}

	@Test
	@DisplayName("D-261: 등록 브랜드 목록을 읽는다 — 우리가 이미 등록한 브랜드는 추측이 필요 없다")
	void readsEnrolledBrands() {
		when(restClient.requestWithBody(eq("GET"), anyString(), any()))
			.thenReturn("{\"code\":\"SUCCESS\",\"data\":["
				+ "{\"brandId\":\"KR-1\",\"brandName\":\"네이쳐스웨이\"},"
				+ "{\"brandId\":\"KR-2\",\"brandName\":\"엔자이메디카\"}]}");

		List<String> enrolled = service.enrolledBrandNames();

		assertThat(enrolled).containsExactly("네이쳐스웨이", "엔자이메디카");
	}

	@Test
	@DisplayName("D-261: 등록 목록도 한 번만 부른다 — 배치마다 다시 부를 이유가 없다")
	void enrolledIsCached() {
		when(restClient.requestWithBody(eq("GET"), anyString(), any()))
			.thenReturn("{\"code\":\"SUCCESS\",\"data\":[{\"brandId\":\"KR-1\",\"brandName\":\"콤비타\"}]}");

		service.enrolledBrandNames();
		service.enrolledBrandNames();

		verify(restClient, times(1)).requestWithBody(eq("GET"), anyString(), any());
	}

	@Test
	@DisplayName("D-261: 등록 목록 조회가 실패하면 빈 목록이 아니라 실패로 다룬다 — 캐시하지 않는다")
	void enrolledFailure_isNotCached() {
		when(restClient.requestWithBody(eq("GET"), anyString(), any()))
			.thenThrow(new RuntimeException("429"));

		assertThat(service.enrolledBrandNames()).isEmpty();
		assertThat(service.enrolledBrandNames()).isEmpty();

		verify(restClient, times(2)).requestWithBody(eq("GET"), anyString(), any());
	}
}
