package com.sbshop.agent.infrastructure.client.cafe24;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.infrastructure.client.cafe24.client.Cafe24RestClient;
import com.sbshop.agent.infrastructure.client.cafe24.component.Cafe24BrandCodeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class Cafe24BrandCodeResolverTest {

	@Mock
	private Cafe24RestClient cafe24RestClient;

	private Cafe24BrandCodeResolver resolver;

	@BeforeEach
	void setUp() {
		resolver = new Cafe24BrandCodeResolver(cafe24RestClient, new ObjectMapper());
	}

	@Test
	@DisplayName("D-294: 기존 브랜드가 있으면 조회로 찾은 brand_code 를 반환한다")
	void resolvesExistingBrand() {
		when(cafe24RestClient.get("/admin/brands?brand_name=아이허브"))
			.thenReturn("{\"brands\":[{\"brand_code\":\"B0000001\",\"brand_name\":\"아이허브\"}]}");

		String code = resolver.resolve("아이허브");

		assertThat(code).isEqualTo("B0000001");
		verify(cafe24RestClient, never()).post(any(), any());
	}

	@Test
	@DisplayName("D-294: 조회 결과 중 이름이 정확히 일치하는 항목만 선택한다 — 부분일치는 무시")
	void selectsExactNameMatchOnly() {
		when(cafe24RestClient.get("/admin/brands?brand_name=허브"))
			.thenReturn("{\"brands\":["
				+ "{\"brand_code\":\"B0000002\",\"brand_name\":\"아이허브\"},"
				+ "{\"brand_code\":\"B0000003\",\"brand_name\":\"허브\"}]}");

		String code = resolver.resolve("허브");

		assertThat(code).isEqualTo("B0000003");
		verify(cafe24RestClient, never()).post(any(), any());
	}

	@Test
	@DisplayName("D-294: 브랜드가 없으면 등록한 뒤 새 brand_code 를 반환한다")
	void registersMissingBrand() {
		when(cafe24RestClient.get("/admin/brands?brand_name=신규브랜드"))
			.thenReturn("{\"brands\":[]}");
		when(cafe24RestClient.post(eq("/admin/brands"), any()))
			.thenReturn("{\"brand\":{\"brand_code\":\"B0000099\",\"brand_name\":\"신규브랜드\"}}");

		String code = resolver.resolve("신규브랜드");

		assertThat(code).isEqualTo("B0000099");
	}

	@Test
	@DisplayName("D-294: 같은 브랜드명은 조회 API를 한 번만 호출한다 — 캐시")
	void cachesResolvedBrand() {
		when(cafe24RestClient.get("/admin/brands?brand_name=아이허브"))
			.thenReturn("{\"brands\":[{\"brand_code\":\"B0000001\",\"brand_name\":\"아이허브\"}]}");

		resolver.resolve("아이허브");
		resolver.resolve("아이허브");

		verify(cafe24RestClient, times(1)).get(any());
	}

	@Test
	@DisplayName("D-294: 조회 실패는 조용히 넘어가지 않고 예외로 알린다")
	void failsLoudlyOnLookupError() {
		when(cafe24RestClient.get("/admin/brands?brand_name=아이허브"))
			.thenThrow(new RuntimeException("네트워크 오류"));

		assertThatThrownBy(() -> resolver.resolve("아이허브"))
			.isInstanceOf(IllegalStateException.class);

		verify(cafe24RestClient, never()).post(any(), any());
	}
}
