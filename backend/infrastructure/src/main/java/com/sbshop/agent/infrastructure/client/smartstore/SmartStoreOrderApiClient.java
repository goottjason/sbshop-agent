package com.sbshop.agent.infrastructure.client.smartstore;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.order.port.SmartStoreOrderApiPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

// 클라이언트 로깅 설정
@Slf4j
// 스프링 컴포넌트 빈 선언
@Component
// 필수 인자 생성자 자동 주입
@RequiredArgsConstructor
public class SmartStoreOrderApiClient implements SmartStoreOrderApiPort {

	// HTTP 통신용 레스트 클라이언트 인스턴스 생성
	private final RestClient restClient = RestClient.create();
	// JSON 처리용 오브젝트 매퍼 의존성
	private final ObjectMapper objectMapper;
	// 네이버 커머스 API 기본 도메인 상수
	private static final String API_DOMAIN = "https://api.commerce.naver.com";

	// 스마트스토어 주문 내역 조회 인터페이스 구현
	@Override
	public JsonNode fetchOrders(String clientId, String secretKey, String fromDate, String toDate) {
		// 1. 엑세스 토큰 발급 호출
		String accessToken = getAccessToken(clientId, secretKey);
		// 2. 발급 실패 여부 확인
		if (accessToken == null) {
			// 토큰 발급 실패 시 예외 던지기
			throw new RuntimeException("스마트스토어 엑세스 토큰 획득 실패. 클라이언트 ID/시크릿 키를 확인하세요.");
		}

		try {
			// 3. 변경된 주문 상태 조회 (Step 1)
			// UriComponentsBuilder를 사용하여 안전하게 URL을 조합하고 파라미터를 인코딩
			URI statusUri = UriComponentsBuilder.fromHttpUrl(API_DOMAIN)
				.path("/external/v1/pay-order/seller/product-orders/last-changed-statuses")
				.queryParam("lastChangedFrom", fromDate)
				.queryParam("lastChangedTo", toDate)
				.build()
				.encode()
				.toUri();

			// 상태 조회 GET 요청 전송
			String statusResponse = restClient.get()
				.uri(statusUri)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
				.accept(MediaType.APPLICATION_JSON)
				.retrieve()
				.body(String.class);

			// 응답 JSON 문자열 파싱
			JsonNode statusNode = objectMapper.readTree(statusResponse);
			// 변경된 상태 목록 배열 노드 추출
			JsonNode statuses = statusNode.path("data").path("lastChangeStatuses");

			// 4. 상태 목록 누락 또는 빈 배열 검증
			if (statuses.isMissingNode() || statuses.size() == 0) {
				// 빈 JSON 배열 노드 반환
				return objectMapper.createArrayNode();
			}

			// 5. 상품 주문 번호 배열 추출 및 상세 조회 요청 본문(JSON) 생성 (Step 2)
			// 요청 본문 스트링 빌더 초기화
			StringBuilder jsonBodyBuilder = new StringBuilder("{\"productOrderIds\":[");
			// 상태 목록 순회 반복문
			for (int i = 0; i < statuses.size(); i++) {
				// 배열 요소 구분자 콤마 추가 분기
				if (i > 0)
					jsonBodyBuilder.append(",");
				// 개별 상품 주문 번호 문자열 추가
				jsonBodyBuilder.append("\"").append(statuses.get(i).path("productOrderId").asText()).append("\"");
			}
			// 요청 본문 JSON 배열 닫기
			jsonBodyBuilder.append("]}");

			// 6. 주문 상세 내역 일괄 조회
			// 상세 조회 API URL 조합
			String detailUrl = API_DOMAIN + "/external/v1/pay-order/seller/product-orders/query";
			// 상세 조회 POST 요청 전송
			String detailResponse = restClient.post()
				.uri(URI.create(detailUrl))
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON)
				.body(jsonBodyBuilder.toString())
				.retrieve()
				.body(String.class);

			// 상세 조회 응답 JSON 문자열 파싱
			JsonNode detailNode = objectMapper.readTree(detailResponse);
			// 파싱된 주문 상세 내역 배열 반환
			return detailNode.path("data");

		} catch (RestClientResponseException re) {
			// D-043: HTTP 오류를 삼켜 빈 반환("성공 0건")하지 말고 상태코드 담아 전파 → 어댑터 전량 실패 감지.
			log.error("스마트스토어 주문 내역 조회 실패 (HTTP 상태: {}, 바디: {})", re.getStatusCode(), re.getResponseBodyAsString(), re);
			throw new RuntimeException("스마트스토어 주문 조회 HTTP 오류: " + re.getStatusCode(), re);
		} catch (Exception e) {
			// D-043: 조회 실패(파싱/네트워크)를 삼키지 말고 전파.
			log.error("스마트스토어 주문 내역 조회 실패: {}", e.getMessage(), e);
			throw new RuntimeException("스마트스토어 주문 조회 실패: " + e.getMessage(), e);
		}
	}

	// 스마트스토어 주문 발송 처리 인터페이스 구현
	@Override
	public void shipOrder(String clientId, String secretKey, String productOrderId, String trackingNo,
		String deliveryCompanyCode) {
		// 1. 발송 처리용 엑세스 토큰 발급 호출
		String accessToken = getAccessToken(clientId, secretKey);
		// 2. 발급 실패 여부 확인
		if (accessToken == null) {
			// 발송 토큰 실패 런타임 예외 발생
			throw new RuntimeException("스마트스토어 발송 처리용 엑세스 토큰 획득 실패.");
		}

		try {
			// 3. 발송 처리 API URL 조합
			String url = API_DOMAIN + "/external/v1/pay-order/seller/product-orders/dispatch";

			// 4. 발송 처리 요청 본문(JSON) 페이로드 문자열 포맷팅
			// 스마트스토어 API: 밀리초 3자리 필수 (yyyy-MM-dd'T'HH:mm:ss.SSS+09:00)
			String dispatchDate = ZonedDateTime.now(ZoneId.of("Asia/Seoul"))
				.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS+09:00"));
			String payload = String.format(
				"{\"dispatchProductOrders\":[{\"productOrderId\":\"%s\",\"deliveryMethod\":\"DELIVERY\",\"deliveryCompanyCode\":\"%s\",\"trackingNumber\":\"%s\",\"dispatchDate\":\"%s\"}]}",
				productOrderId, deliveryCompanyCode, trackingNo, dispatchDate);

			log.info("스마트스토어 발송 API 요청 payload: {}", payload);

			// 5. 발송 처리 POST 요청 전송
			String response = restClient.post()
				.uri(URI.create(url))
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON)
				.body(payload)
				.retrieve()
				.body(String.class);

			log.info("스마트스토어 발송 API 응답: {}", response);

			// 6. 응답 JSON 문자열 파싱
			JsonNode rootNode = objectMapper.readTree(response);
			// 7. D-145: 이 마켓은 HTTP 200 본문 안에 실패를 담아 준다(data.failProductOrderInfos).
			//    최상위 code만 보면 거짓 성공이 되고, 그 거짓이 마켓 미반영을 반영됨으로 기록한다.
			SmartStoreDispatchResult.verifyAccepted(rootNode, productOrderId);

		} catch (Exception e) {
			// 발송 처리 실패 에러 로그 출력
			log.error("스마트스토어 주문 발송 실패: {}", e.getMessage(), e);
			// 발송 실패 런타임 예외 래핑 및 발생
			throw new RuntimeException("스마트스토어 주문 발송(shipOrder) 실패", e);
		}
	}

	@Override
	public void confirmOrders(String clientId, String secretKey, List<String> productOrderIds) {
		// 1. 엑세스 토큰 발급
		String accessToken = getAccessToken(clientId, secretKey);
		if (accessToken == null) {
			throw new RuntimeException("스마트스토어 발주 확인용 엑세스 토큰 획득 실패.");
		}

		try {
			// 2. 발주확인 API URL 조합
			String url = API_DOMAIN + "/external/v1/pay-order/seller/product-orders/confirm";

			// 3. 요청 본문 JSON 생성
			String jsonBody = buildProductOrderIdsBody(productOrderIds);

			// 4. 발주확인 POST 요청 전송
			String response = restClient.post()
				.uri(URI.create(url))
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON)
				.body(jsonBody)
				.retrieve()
				.body(String.class);

			// 5. 응답 파싱 및 실패 건 확인
			JsonNode rootNode = objectMapper.readTree(response);
			JsonNode detail = rootNode.path("detail");
			if (detail.isArray()) {
				for (JsonNode item : detail) {
					if (!item.path("confirm").asBoolean()) {
						log.warn("스마트스토어 발주확인 실패 건: productOrderId={}, response={}",
							item.path("productOrderId").asText(), rootNode.toPrettyString());
					}
				}
			}
		} catch (Exception e) {
			log.error("스마트스토어 발주 확인 실패: {}", e.getMessage(), e);
			throw new RuntimeException("스마트스토어 발주 확인(confirmOrders) 실패", e);
		}
	}

	@Override
	public void cancelOrders(String clientId, String secretKey, List<String> productOrderIds) {
		// 1. 엑세스 토큰 발급
		String accessToken = getAccessToken(clientId, secretKey);
		if (accessToken == null) {
			throw new RuntimeException("스마트스토어 주문 취소용 엑세스 토큰 획득 실패.");
		}

		try {
			// 2. 주문취소 API URL 조합
			String url = API_DOMAIN + "/external/v1/pay-order/seller/product-orders/cancel";

			// 3. 요청 본문 JSON 생성
			String jsonBody = buildProductOrderIdsBody(productOrderIds);

			// 4. 주문취소 POST 요청 전송
			String response = restClient.post()
				.uri(URI.create(url))
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON)
				.body(jsonBody)
				.retrieve()
				.body(String.class);

			// 5. 응답 파싱 및 실패 건 확인
			JsonNode rootNode = objectMapper.readTree(response);
			JsonNode detail = rootNode.path("detail");
			if (detail.isArray()) {
				for (JsonNode item : detail) {
					if (!item.path("cancel").asBoolean()) {
						log.warn("스마트스토어 주문취소 실패 건: productOrderId={}, response={}",
							item.path("productOrderId").asText(), rootNode.toPrettyString());
					}
				}
			}
		} catch (Exception e) {
			log.error("스마트스토어 주문 취소 실패: {}", e.getMessage(), e);
			throw new RuntimeException("스마트스토어 주문 취소(cancelOrders) 실패", e);
		}
	}

	/** productOrderIds 배열 요청 본문 JSON 생성 */
	private String buildProductOrderIdsBody(List<String> productOrderIds) {
		StringBuilder jsonBody = new StringBuilder("{\"productOrderIds\":[");
		for (int i = 0; i < productOrderIds.size(); i++) {
			if (i > 0)
				jsonBody.append(",");
			jsonBody.append("\"").append(productOrderIds.get(i)).append("\"");
		}
		jsonBody.append("]}");
		return jsonBody.toString();
	}

	// 스마트스토어 엑세스 토큰 발급 비공개 메서드
	private String getAccessToken(String clientId, String secretKey) {
		try {
			// 1. 현재 밀리초 타임스탬프 획득
			long timestamp = System.currentTimeMillis();
			// 2. 클라이언트 아이디와 타임스탬프 결합 패스워드 조합
			String pwd = clientId + "_" + timestamp;
			// 3. BCrypt 라이브러리 기반 해싱 처리
			String hashed = BCrypt.hashpw(pwd, secretKey);
			// 4. 해싱 결과 Base64 인코딩 시그니처 생성
			String signature = Base64.getEncoder().encodeToString(hashed.getBytes(StandardCharsets.UTF_8));

			// 5. 토큰 발급 요청 폼 데이터 맵 객체 생성
			MultiValueMap<String, String> tokenBody = new LinkedMultiValueMap<>();
			// 클라이언트 아이디 파라미터 추가
			tokenBody.add("client_id", clientId);
			// 타임스탬프 파라미터 추가
			tokenBody.add("timestamp", String.valueOf(timestamp));
			// 시그니처 파라미터 추가
			tokenBody.add("client_secret_sign", signature);
			// 그랜트 타입 파라미터 추가
			tokenBody.add("grant_type", "client_credentials");
			// 타입 파라미터 추가
			tokenBody.add("type", "SELF");

			// 6. 엑세스 토큰 발급 POST 요청 전송
			String tokenResponse = restClient.post()
				.uri("https://api.commerce.naver.com/external/v1/oauth2/token")
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.body(tokenBody)
				.retrieve()
				.body(String.class);

			// 7. 응답 JSON 파싱 및 엑세스 토큰 반환
			JsonNode tokenNode = objectMapper.readTree(tokenResponse);
			return tokenNode.path("access_token").asText();
		} catch (Exception e) {
			// 토큰 발급 과정 실패 에러 로그 출력
			log.error("스마트스토어 엑세스 토큰 획득 실패", e);
			// 널 반환
			return null;
		}
	}
}
