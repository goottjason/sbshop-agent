package com.sbshop.agent.infrastructure.client.coupang;

import com.sbshop.agent.core.application.product.port.BrandLookupOutcome;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.infrastructure.client.coupang.client.CoupangRestClient;
import com.sbshop.agent.infrastructure.client.coupang.component.CoupangBrandLookupService;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CoupangBrandLookupServiceTest {

	@Mock
	private CoupangRestClient restClient;

	private CoupangBrandLookupService service;

	@BeforeEach
	void setUp() {
		service = new CoupangBrandLookupService(restClient, new ObjectMapper());
	}

	@Test
	@DisplayName("D-261: 검색어와 정규화 후 정확히 같은 brandName만 매칭으로 인정한다")
	void exactMatch_isAccepted() {
		stubSearch("{\"code\":\"SUCCESS\",\"data\":{\"totalCount\":1,\"items\":["
			+ "{\"brandId\":\"KR-1\",\"brandName\":\"Comvita\"}]}}");

		BrandLookupOutcome result = service.findOfficialBrandName("Comvita");

		assertThat(result.officialBrandName()).isEqualTo("Comvita");
	}

	@Test
	@DisplayName("D-261: 쿠팡이 관련도 1위로 준 후보를 쓴다 — 교차언어 매칭은 쿠팡이 이미 해준다")
	void partialMatch_isRejected() {
		stubSearch("{\"code\":\"SUCCESS\",\"data\":{\"totalCount\":3,\"items\":["
			+ "{\"brandId\":\"KR-2\",\"brandName\":\"NOW Foods\"},"
			+ "{\"brandId\":\"KR-3\",\"brandName\":\"Nowon\"}]}}");

		BrandLookupOutcome result = service.findOfficialBrandName("NOW Foods");

		assertThat(result.officialBrandName()).isEqualTo("NOW Foods");
		assertThat(result.candidates()).containsExactly("NOW Foods", "Nowon");
	}

	@Test
	@DisplayName("D-261: 정확일치가 2위에 있어도 1위를 쓴다 — 쿠팡 순위가 우리 문자열 비교보다 정확하다")
	void rankBeatsExactStringEquality() {
		stubSearch("{\"code\":\"SUCCESS\",\"data\":{\"totalCount\":2,\"items\":["
			+ "{\"brandId\":\"KR-1\",\"brandName\":\"네이쳐스웨이\"},"
			+ "{\"brandId\":\"KR-2\",\"brandName\":\"네이처스웨이\"}]}}");

		BrandLookupOutcome result = service.findOfficialBrandName("네이처스웨이");

		assertThat(result.officialBrandName()).isEqualTo("네이쳐스웨이");
	}

	@Test
	@DisplayName("D-261: 정규화 비교 — 대소문자·공백·아포스트로피 차이는 매칭으로 인정한다")
	void normalizedMatch_ignoresCaseSpaceAndPunctuation() {
		stubSearch("{\"code\":\"SUCCESS\",\"data\":{\"totalCount\":1,\"items\":["
			+ "{\"brandId\":\"KR-4\",\"brandName\":\"nature's way\"}]}}");

		BrandLookupOutcome result = service.findOfficialBrandName("Nature's Way");

		assertThat(result.officialBrandName()).isEqualTo("nature's way");
	}

	@Test
	@DisplayName("D-181: code 가 SUCCESS 가 아니면 200 이어도 매칭으로 쓰지 않는다")
	void nonSuccessCode_isNotTreatedAsMatch() {
		stubSearch("{\"code\":\"ERROR\",\"data\":{\"totalCount\":1,\"items\":["
			+ "{\"brandId\":\"KR-1\",\"brandName\":\"Comvita\"}]}}");

		BrandLookupOutcome result = service.findOfficialBrandName("Comvita");

		assertThat(result.isMatched()).isFalse();
	}

	@Test
	@DisplayName("D-261: 같은 브랜드를 두 번 조회해도 API는 1회만 호출한다 — 브랜드 단위 캐시")
	void sameKeyword_callsApiOnce() {
		stubSearch("{\"code\":\"SUCCESS\",\"data\":{\"totalCount\":1,\"items\":["
			+ "{\"brandId\":\"KR-1\",\"brandName\":\"Comvita\"}]}}");

		BrandLookupOutcome first = service.findOfficialBrandName("Comvita");
		BrandLookupOutcome second = service.findOfficialBrandName("Comvita");

		assertThat(first.officialBrandName()).isEqualTo("Comvita");
		assertThat(second.officialBrandName()).isEqualTo("Comvita");
		verify(restClient, times(1)).requestWithBody(eq("POST"), anyString(), any());
	}

	@Test
	@DisplayName("D-261: 매칭 실패도 캐시한다 — 같은 미등록 브랜드를 반복 조회해도 API는 1회만 호출한다")
	void sameUnmatchedKeyword_callsApiOnce() {
		stubSearch("{\"code\":\"SUCCESS\",\"data\":{\"totalCount\":0,\"items\":[]}}");

		service.findOfficialBrandName("Four");
		service.findOfficialBrandName("Four");

		verify(restClient, times(1)).requestWithBody(eq("POST"), anyString(), any());
	}

	@Test
	@DisplayName("D-261: API 호출이 예외를 던지면 빈 값을 반환한다 — 대량 백필을 막지 않는다")
	void apiException_returnsEmpty() {
		when(restClient.requestWithBody(eq("POST"), anyString(), any()))
			.thenThrow(new RuntimeException("timeout"));

		BrandLookupOutcome result = service.findOfficialBrandName("Comvita");

		assertThat(result.isMatched()).isFalse();
	}

	@Test
	@DisplayName("D-261: 검색어가 비어있으면 API를 호출하지 않는다")
	void blankKeyword_doesNotCallApi() {
		BrandLookupOutcome result = service.findOfficialBrandName("  ");

		assertThat(result.isMatched()).isFalse();
		verify(restClient, times(0)).requestWithBody(anyString(), anyString(), any());
	}

	@Test
	@DisplayName("D-261: 검색 요청은 문서화된 경로와 파라미터 형태로 보낸다")
	void search_usesDocumentedPathAndParams() {
		stubSearch("{\"code\":\"SUCCESS\",\"data\":{\"totalCount\":0,\"items\":[]}}");

		service.findOfficialBrandName("Comvita");

		verify(restClient).requestWithBody(eq("POST"),
			eq("/v2/providers/seller_api/apis/api/v1/marketplace/brands/search"),
			eq(Map.of("brandName", "Comvita", "countPerPage", 10, "page", 1)));
	}

	private void stubSearch(String json) {
		when(restClient.requestWithBody(eq("POST"), anyString(), any())).thenReturn(json);
	}

	@Test
	@DisplayName("D-261: 조회 자체가 실패하면 캐시하지 않는다 — 429 한 번이 그 브랜드를 배치 내내 미등록으로 만들면 안 된다")
	void lookupFailure_isNotCached() {
		when(restClient.requestWithBody(eq("POST"), anyString(), any()))
			.thenThrow(new RuntimeException("429 Too Many Requests"));

		BrandLookupOutcome first = service.findOfficialBrandName("Comvita");
		BrandLookupOutcome second = service.findOfficialBrandName("Comvita");

		assertThat(first.status()).isEqualTo(BrandLookupOutcome.Status.LOOKUP_FAILED);
		assertThat(second.status()).isEqualTo(BrandLookupOutcome.Status.LOOKUP_FAILED);
		verify(restClient, times(2)).requestWithBody(eq("POST"), anyString(), any());
	}

	@Test
	@DisplayName("D-261: code 가 SUCCESS 가 아닌 것도 조회 실패다 — 없음이 아니라 모름이다")
	void nonSuccessCode_isLookupFailureNotAbsence() {
		when(restClient.requestWithBody(eq("POST"), anyString(), any()))
			.thenReturn("{\"code\":\"ERROR\",\"message\":\"quota exceeded\"}");

		BrandLookupOutcome outcome = service.findOfficialBrandName("Comvita");

		assertThat(outcome.status()).isEqualTo(BrandLookupOutcome.Status.LOOKUP_FAILED);
	}

	@Test
	@DisplayName("D-261: 조회는 됐는데 일치가 없으면 미등록이다 — 이건 캐시해도 된다")
	void noMatch_isNotRegistered() {
		when(restClient.requestWithBody(eq("POST"), anyString(), any()))
			.thenReturn("{\"code\":\"SUCCESS\",\"data\":{\"items\":[]}}");

		BrandLookupOutcome outcome = service.findOfficialBrandName("Four");

		assertThat(outcome.status()).isEqualTo(BrandLookupOutcome.Status.NOT_REGISTERED);
	}
}
