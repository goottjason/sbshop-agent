package com.sbshop.agent.infrastructure.client.coupang;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.market.client.dto.MarketApprovalOutcome;
import com.sbshop.agent.core.domain.market.client.dto.MarketApprovalResult;
import com.sbshop.agent.infrastructure.client.coupang.adapter.CoupangMarketClient;
import com.sbshop.agent.infrastructure.client.coupang.client.CoupangRestClient;
import com.sbshop.agent.infrastructure.client.coupang.component.CoupangAttributeValueResolver;
import com.sbshop.agent.infrastructure.client.coupang.component.CoupangCategoryPredictor;
import com.sbshop.agent.infrastructure.client.coupang.component.CoupangMetaService;
import com.sbshop.agent.infrastructure.client.coupang.component.CoupangSearchTagGenerator;
import com.sbshop.agent.infrastructure.client.coupang.config.CoupangProperties;
import com.sbshop.agent.infrastructure.client.coupang.mapper.CoupangDataMapper;
import com.sbshop.agent.infrastructure.client.coupang.parser.CoupangProductParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CoupangMarketClientApprovalTest {

	@Mock
	private CoupangProperties properties;
	@Mock
	private CoupangRestClient restClient;
	@Mock
	private CoupangCategoryPredictor categoryPredictor;
	@Mock
	private CoupangProductParser productParser;
	@Mock
	private CoupangSearchTagGenerator searchTagGenerator;
	@Mock
	private CoupangDataMapper dataMapper;
	@Mock
	private CoupangMetaService metaService;

	private CoupangMarketClient client;

	private static final String BASE_PATH = "/v2/providers/seller_api/apis/api/v1/marketplace/seller-products";
	private static final String GET_PATH = BASE_PATH + "/14300000001";
	private static final String APPROVAL_PATH = GET_PATH + "/approvals";
	private static final String SUCCESS_ENVELOPE = "{\"code\":\"SUCCESS\",\"message\":\"성공\"}";

	@BeforeEach
	void setUp() {
		client = new CoupangMarketClient(properties, new ObjectMapper(), restClient, categoryPredictor,
			productParser, searchTagGenerator, dataMapper, metaService, new CoupangAttributeValueResolver());
	}

	@Test
	@DisplayName("D-215: 쿠팡은 승인 요청을 지원한다")
	void supportsApprovalRequest() {
		assertThat(client.supportsApprovalRequest()).isTrue();
	}

	@Test
	@DisplayName("D-215: 임시저장이면 본문 없는 PUT /approvals 를 호출하고 성공으로 판정한다")
	void draftStatus_callsApprovalsWithoutBody() {
		stubStatus("임시저장");
		when(restClient.requestWithBody(eq("PUT"), eq(APPROVAL_PATH), eq(null))).thenReturn(SUCCESS_ENVELOPE);

		MarketApprovalResult result = client.requestApproval("14300000001");

		verify(restClient).requestWithBody(eq("PUT"), eq(APPROVAL_PATH), eq(null));
		assertThat(result.outcome()).isEqualTo(MarketApprovalOutcome.REQUESTED);
		assertThat(result.called()).isTrue();
		assertThat(result.priorStatus()).isEqualTo("임시저장");
		assertThat(result.responseCode()).isEqualTo("SUCCESS");
	}

	@ParameterizedTest
	@ValueSource(strings = {"임시저장", "승인반려", "부분승인완료"})
	@DisplayName("D-215: 적용 대상 상태(임시저장·승인반려·부분승인완료)에서만 호출한다")
	void eligibleStatuses_areCalled(String statusName) {
		stubStatus(statusName);
		when(restClient.requestWithBody(eq("PUT"), eq(APPROVAL_PATH), eq(null))).thenReturn(SUCCESS_ENVELOPE);

		MarketApprovalResult result = client.requestApproval("14300000001");

		assertThat(result.outcome()).isEqualTo(MarketApprovalOutcome.REQUESTED);
	}

	@ParameterizedTest
	@ValueSource(strings = {"승인완료", "승인대기중", "판매중지", "상품삭제"})
	@DisplayName("D-215: 대상 아닌 상태면 호출하지 않고 사유를 남기고 건너뛴다(D-092 거부 재발 방지)")
	void ineligibleStatuses_areSkippedWithoutCall(String statusName) {
		stubStatus(statusName);

		MarketApprovalResult result = client.requestApproval("14300000001");

		verify(restClient, never()).requestWithBody(anyString(), eq(APPROVAL_PATH), any());
		verify(restClient, never()).put(eq(APPROVAL_PATH), any());
		assertThat(result.outcome()).isEqualTo(MarketApprovalOutcome.SKIPPED);
		assertThat(result.called()).isFalse();
		assertThat(result.priorStatus()).isEqualTo(statusName);
		assertThat(result.note()).contains(statusName);
	}

	@Test
	@DisplayName("D-215: statusName 을 읽지 못하면 호출하지 않고 건너뛴다")
	void unknownStatus_isSkippedWithoutCall() {
		when(restClient.get(eq(GET_PATH))).thenReturn("{\"code\":\"SUCCESS\",\"data\":{}}");

		MarketApprovalResult result = client.requestApproval("14300000001");

		verify(restClient, never()).requestWithBody(anyString(), eq(APPROVAL_PATH), any());
		assertThat(result.outcome()).isEqualTo(MarketApprovalOutcome.SKIPPED);
		assertThat(result.note()).contains("상태를 확인하지 못했");
	}

	@Test
	@DisplayName("D-215: 봉투 code=ERROR 를 성공으로 삼키지 않는다")
	void errorEnvelope_isFailure() {
		stubStatus("임시저장");
		when(restClient.requestWithBody(eq("PUT"), eq(APPROVAL_PATH), eq(null)))
			.thenReturn("{\"code\":\"ERROR\",\"message\":\"'임시저장' 상태의 상품만 승인 요청 가능합니다\"}");

		MarketApprovalResult result = client.requestApproval("14300000001");

		assertThat(result.outcome()).isEqualTo(MarketApprovalOutcome.FAILED);
		assertThat(result.called()).isTrue();
		assertThat(result.responseCode()).isEqualTo("ERROR");
		assertThat(result.responseMessage()).contains("승인 요청 가능합니다");
	}

	@Test
	@DisplayName("D-215: 봉투에 code 가 없으면 성공이 아니다(화이트리스트)")
	void missingCode_isFailure() {
		stubStatus("임시저장");
		when(restClient.requestWithBody(eq("PUT"), eq(APPROVAL_PATH), eq(null)))
			.thenReturn("{\"message\":\"OK\"}");

		assertThat(client.requestApproval("14300000001").outcome()).isEqualTo(MarketApprovalOutcome.FAILED);
	}

	@Test
	@DisplayName("D-215: 봉투가 JSON 이 아니면 성공이 아니다")
	void unparsableEnvelope_isFailure() {
		stubStatus("임시저장");
		when(restClient.requestWithBody(eq("PUT"), eq(APPROVAL_PATH), eq(null)))
			.thenReturn("<html>gateway error</html>");

		assertThat(client.requestApproval("14300000001").outcome()).isEqualTo(MarketApprovalOutcome.FAILED);
	}

	@Test
	@DisplayName("D-215: 응답 본문이 없으면 성공이 아니다")
	void nullEnvelope_isFailure() {
		stubStatus("임시저장");
		when(restClient.requestWithBody(eq("PUT"), eq(APPROVAL_PATH), eq(null))).thenReturn(null);

		assertThat(client.requestApproval("14300000001").outcome()).isEqualTo(MarketApprovalOutcome.FAILED);
	}

	@Test
	@DisplayName("D-215: 숫자 code=200 도 성공으로 인정한다")
	void numericSuccessCode_isRequested() {
		stubStatus("임시저장");
		when(restClient.requestWithBody(eq("PUT"), eq(APPROVAL_PATH), eq(null)))
			.thenReturn("{\"code\":200,\"message\":\"OK\"}");

		assertThat(client.requestApproval("14300000001").outcome()).isEqualTo(MarketApprovalOutcome.REQUESTED);
	}

	@Test
	@DisplayName("D-215: HTTP 400 '등록/수정 중' 은 재시도 가능으로 분류한다(자동 재시도 없음)")
	void inProgressHttp400_isRetryable() {
		stubStatus("임시저장");
		when(restClient.requestWithBody(eq("PUT"), eq(APPROVAL_PATH), eq(null)))
			.thenThrow(new RuntimeException("Coupang API 호출 실패", new IllegalStateException(
				"400 Bad Request: \"{\"code\":\"ERROR\",\"message\":\"상품 정보를 등록/수정 중입니다.\"}\"")));

		MarketApprovalResult result = client.requestApproval("14300000001");

		assertThat(result.outcome()).isEqualTo(MarketApprovalOutcome.RETRYABLE);
		assertThat(result.called()).isTrue();
		assertThat(result.note()).contains("10분");
		verify(restClient).requestWithBody(eq("PUT"), eq(APPROVAL_PATH), eq(null));
	}

	@Test
	@DisplayName("D-215: 200 봉투로 온 '등록/수정 중' 도 재시도 가능으로 분류한다")
	void inProgressEnvelope_isRetryable() {
		stubStatus("임시저장");
		when(restClient.requestWithBody(eq("PUT"), eq(APPROVAL_PATH), eq(null)))
			.thenReturn("{\"code\":\"ERROR\",\"message\":\"상품 정보를 등록/수정 중입니다.\"}");

		assertThat(client.requestApproval("14300000001").outcome()).isEqualTo(MarketApprovalOutcome.RETRYABLE);
	}

	@Test
	@DisplayName("D-215: 그 밖의 HTTP 오류는 실패로 기록한다")
	void otherHttpError_isFailure() {
		stubStatus("임시저장");
		when(restClient.requestWithBody(eq("PUT"), eq(APPROVAL_PATH), eq(null)))
			.thenThrow(new RuntimeException("Coupang API 호출 실패",
				new IllegalStateException("500 Internal Server Error")));

		MarketApprovalResult result = client.requestApproval("14300000001");

		assertThat(result.outcome()).isEqualTo(MarketApprovalOutcome.FAILED);
		assertThat(result.responseMessage()).contains("500");
	}

	@Test
	@DisplayName("D-215: 쿠팡에 없는 상품(404)은 호출하지 않고 건너뛴다")
	void notFoundProduct_isSkipped() {
		when(restClient.get(eq(GET_PATH)))
			.thenThrow(new RuntimeException("Coupang API 호출 실패", new IllegalStateException("404 Not Found")));

		MarketApprovalResult result = client.requestApproval("14300000001");

		verify(restClient, never()).requestWithBody(anyString(), eq(APPROVAL_PATH), any());
		assertThat(result.outcome()).isEqualTo(MarketApprovalOutcome.SKIPPED);
		assertThat(result.note()).contains("쿠팡에 없");
	}

	@Test
	@DisplayName("D-215: 사전 상태 조회가 실패하면 호출하지 않고 실패로 남긴다")
	void statusLookupFailure_isFailedWithoutCall() {
		when(restClient.get(eq(GET_PATH)))
			.thenThrow(new RuntimeException("Coupang API 호출 실패", new IllegalStateException("503")));

		MarketApprovalResult result = client.requestApproval("14300000001");

		verify(restClient, never()).requestWithBody(anyString(), eq(APPROVAL_PATH), any());
		assertThat(result.outcome()).isEqualTo(MarketApprovalOutcome.FAILED);
		assertThat(result.called()).isFalse();
	}

	@Test
	@DisplayName("D-215: 사전 조회 봉투가 실패면 호출하지 않는다")
	void statusEnvelopeFailure_isFailedWithoutCall() {
		when(restClient.get(eq(GET_PATH))).thenReturn("{\"code\":\"ERROR\",\"message\":\"조회 실패\"}");

		MarketApprovalResult result = client.requestApproval("14300000001");

		verify(restClient, never()).requestWithBody(anyString(), eq(APPROVAL_PATH), any());
		assertThat(result.outcome()).isEqualTo(MarketApprovalOutcome.FAILED);
	}

	@Test
	@DisplayName("D-215: sellerProductId 가 비면 아무 호출도 하지 않는다")
	void blankId_callsNothing() {
		MarketApprovalResult result = client.requestApproval("  ");

		verify(restClient, never()).get(anyString());
		verify(restClient, never()).requestWithBody(anyString(), anyString(), any());
		assertThat(result.outcome()).isEqualTo(MarketApprovalOutcome.FAILED);
		assertThat(result.called()).isFalse();
	}

	private void stubStatus(String statusName) {
		when(restClient.get(eq(GET_PATH)))
			.thenReturn("{\"code\":\"SUCCESS\",\"data\":{\"sellerProductId\":14300000001,\"statusName\":\""
				+ statusName + "\"}}");
	}
}
