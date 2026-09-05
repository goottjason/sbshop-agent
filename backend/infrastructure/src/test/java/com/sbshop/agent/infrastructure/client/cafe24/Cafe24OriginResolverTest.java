package com.sbshop.agent.infrastructure.client.cafe24;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.infrastructure.client.cafe24.client.Cafe24RestClient;
import com.sbshop.agent.infrastructure.client.cafe24.component.Cafe24OriginResolver;
import com.sbshop.agent.infrastructure.client.cafe24.component.Cafe24OriginResolver.Origin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class Cafe24OriginResolverTest {

	@Mock
	private Cafe24RestClient cafe24RestClient;

	private Cafe24OriginResolver resolver;

	@BeforeEach
	void setUp() {
		resolver = new Cafe24OriginResolver(cafe24RestClient, new ObjectMapper());
	}

	@Test
	@DisplayName("D-295② 라이브 실측: origin_place_name 이 [지역,국가] 배열이어도 마지막 요소로 완전일치한다")
	void resolvesWhenPlaceNameIsArray() {
		when(cafe24RestClient.get("/admin/origin?origin_place_name=미국&limit=100"))
			.thenReturn("{\"origin\":[{\"origin_place_no\":358,\"origin_place_name\":[\"아메리카\",\"미국\"],\"foreign\":\"T\"},"
				+ "{\"origin_place_no\":2613,\"origin_place_name\":[\"아메리카\",\"미국령 소군도\"],\"foreign\":\"T\"}]}");

		Origin origin = resolver.resolve("미국");

		assertThat(origin.classification()).isEqualTo("T");
		assertThat(origin.placeNo()).isEqualTo(358);
		assertThat(origin.placeValue()).isNull();
	}

	@Test
	@DisplayName("D-295②: 해외 원산지는 구분 T 와 원산지 번호로 해석한다 — 기타정보는 비운다")
	void resolvesForeignOrigin() {
		when(cafe24RestClient.get("/admin/origin?origin_place_name=미국&limit=100"))
			.thenReturn("{\"origins\":[{\"origin_place_no\":1799,\"origin_place_name\":\"미국\","
				+ "\"foreign\":\"T\"}]}");

		Origin origin = resolver.resolve("미국");

		assertThat(origin.classification()).isEqualTo("T");
		assertThat(origin.placeNo()).isEqualTo(1799);
		assertThat(origin.placeValue()).isNull();
	}

	@Test
	@DisplayName("D-295②: 국내 원산지는 구분 F 로 해석한다")
	void resolvesDomesticOrigin() {
		when(cafe24RestClient.get("/admin/origin?origin_place_name=국내산&limit=100"))
			.thenReturn("{\"origins\":[{\"origin_place_no\":1234,\"origin_place_name\":\"국내산\","
				+ "\"foreign\":\"F\"}]}");

		Origin origin = resolver.resolve("국내산");

		assertThat(origin.classification()).isEqualTo("F");
		assertThat(origin.placeNo()).isEqualTo(1234);
	}

	@Test
	@DisplayName("D-295②: 이름이 정확히 일치하는 원산지가 없으면 기타(1800)로 떨어진다")
	void fallsBackToOtherWhenNoExactMatch() {
		when(cafe24RestClient.get("/admin/origin?origin_place_name=상세설명 참조&limit=100"))
			.thenReturn("{\"origins\":[{\"origin_place_no\":1799,\"origin_place_name\":\"미국\","
				+ "\"foreign\":\"T\"}]}");

		Origin origin = resolver.resolve("상세설명 참조");

		assertThat(origin.classification()).isEqualTo("E");
		assertThat(origin.placeNo()).isEqualTo(1800);
		assertThat(origin.placeValue()).isEqualTo("상세설명 참조");
	}

	@Test
	@DisplayName("D-295②: 기타 원산지 정보는 30자로 자른다")
	void truncatesOtherOriginValueTo30Characters() {
		String longText = "가".repeat(45);
		when(cafe24RestClient.get("/admin/origin?origin_place_name=" + longText + "&limit=100"))
			.thenReturn("{\"origins\":[]}");

		Origin origin = resolver.resolve(longText);

		assertThat(origin.placeValue()).hasSize(30);
	}

	@Test
	@DisplayName("D-295②: 조회가 실패해도 기타로 떨어지고 실패는 캐시하지 않는다")
	void fallsBackAndDoesNotCacheLookupFailure() {
		when(cafe24RestClient.get("/admin/origin?origin_place_name=미국&limit=100"))
			.thenThrow(new RuntimeException("Cafe24 API 호출 실패(401)"))
			.thenReturn("{\"origins\":[{\"origin_place_no\":1799,\"origin_place_name\":\"미국\","
				+ "\"foreign\":\"T\"}]}");

		Origin first = resolver.resolve("미국");
		Origin second = resolver.resolve("미국");

		assertThat(first.placeNo()).isEqualTo(1800);
		assertThat(second.placeNo()).isEqualTo(1799);
		verify(cafe24RestClient, times(2)).get("/admin/origin?origin_place_name=미국&limit=100");
	}

	@Test
	@DisplayName("D-295②: 같은 원산지는 조회 API 를 한 번만 호출한다 — 캐시")
	void cachesResolvedOrigin() {
		when(cafe24RestClient.get("/admin/origin?origin_place_name=미국&limit=100"))
			.thenReturn("{\"origins\":[{\"origin_place_no\":1799,\"origin_place_name\":\"미국\","
				+ "\"foreign\":\"T\"}]}");

		resolver.resolve("미국");
		resolver.resolve("미국");

		verify(cafe24RestClient, times(1)).get("/admin/origin?origin_place_name=미국&limit=100");
	}

	@Test
	@DisplayName("D-295②: 응답이 origin 키로 와도 같은 결과를 낸다")
	void readsOriginKeyResponseShape() {
		when(cafe24RestClient.get("/admin/origin?origin_place_name=미국&limit=100"))
			.thenReturn("{\"origin\":[{\"origin_place_no\":1799,\"origin_place_name\":\"미국\","
				+ "\"foreign\":\"T\"}]}");

		Origin origin = resolver.resolve("미국");

		assertThat(origin.placeNo()).isEqualTo(1799);
	}
}
