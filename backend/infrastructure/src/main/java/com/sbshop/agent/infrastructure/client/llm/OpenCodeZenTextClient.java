package com.sbshop.agent.infrastructure.client.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.sourcing.dto.GeneratedProductText;
import com.sbshop.agent.core.application.sourcing.dto.ProductTextRequest;
import com.sbshop.agent.core.application.sourcing.port.ProductTextGenerationPort;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * OpenCode Zen 무료 모델로 한글 상품명·검색 키워드를 생성한다.
 *
 * <p>{@code POST https://opencode.ai/zen/v1/chat/completions} (OpenAI 호환).
 * 유료 API를 쓰지 않는다는 제약에 맞춰 무료 모델만 사용한다.
 *
 * <p>모델 선택은 실측 기반이다(동일 프롬프트, 한글 상품명 + 키워드 10개 JSON 강제):
 * <pre>
 *   nemotron-3-ultra-free   완전한 JSON · completion 185토큰   ← 주력
 *   ling-3.0-flash-free     완전한 JSON · 1,108토큰(추론 747)  ← 폴백
 *   deepseek-v4-flash-free  빈 content                        미사용
 *   big-pickle              2,000토큰 초과, 미완성             미사용
 * </pre>
 *
 * <p>앞 모델이 실패하면 다음 모델로 넘어가고, 전부 실패하면 빈 Optional을 돌려준다 —
 * 호출측이 규칙 기반으로 폴백해 파이프라인은 멈추지 않는다.
 */
@Slf4j
@Component
public class OpenCodeZenTextClient implements ProductTextGenerationPort {

	private static final int KEYWORD_TARGET = 20;
	private static final int MAX_BASE_NAME_LENGTH = 40;
	/** 무료 모델은 추론 토큰을 많이 쓴다 — 잘려서 JSON이 깨지지 않도록 넉넉히 준다. */
	private static final int MAX_TOKENS = 1500;

	private static final String SYSTEM_PROMPT = """
		너는 한국 오픈마켓(쿠팡·스마트스토어·11번가) 상품 등록 전문가다.
		해외직구 건강기능식품/식품의 한국어 상품명과 검색 키워드를 만든다.
		반드시 JSON 객체 하나만 출력하고 다른 말은 하지 마라.""";

	private final HttpClient httpClient = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(15))
		.build();
	private final ObjectMapper objectMapper;
	private final String baseUrl;
	private final String apiKey;
	private final List<String> models;

	public OpenCodeZenTextClient(ObjectMapper objectMapper,
		@Value("${zen.base-url:https://opencode.ai/zen/v1}")
		String baseUrl,
		@Value("${zen.api-key:}")
		String apiKey,
		@Value("${zen.models:nemotron-3-ultra-free,ling-3.0-flash-free}")
		String models) {
		this.objectMapper = objectMapper;
		this.baseUrl = baseUrl;
		this.apiKey = apiKey;
		this.models = Arrays.stream(models.split(","))
			.map(String::trim).filter(s -> !s.isEmpty()).toList();
	}

	@Override
	public boolean isEnabled() {
		return apiKey != null && !apiKey.isBlank() && !models.isEmpty();
	}

	@Override
	public Optional<GeneratedProductText> generate(ProductTextRequest request) {
		if (!isEnabled())
			return Optional.empty();

		String prompt = buildPrompt(request);
		for (String model : models) {
			try {
				String content = chat(model, prompt);
				GeneratedProductText parsed = parse(content, model);
				if (parsed != null) {
					return Optional.of(parsed);
				}
				log.warn("[상품텍스트] 모델 {} 응답을 해석할 수 없어 다음 모델로 넘어갑니다", model);
			} catch (Exception e) {
				log.warn("[상품텍스트] 모델 {} 호출 실패 — 다음 모델 시도: {}", model, e.getMessage());
			}
		}
		log.warn("[상품텍스트] 모든 모델 실패 — 규칙 기반으로 폴백합니다: {}", request.originalNameKo());
		return Optional.empty();
	}

	// --- 프롬프트 ---

	private String buildPrompt(ProductTextRequest r) {
		StringBuilder sb = new StringBuilder();
		sb.append("다음 해외직구 상품의 한국 오픈마켓용 정보를 만들어라.\n\n");
		sb.append("원본 상품명: ").append(nz(r.originalNameKo())).append('\n');
		if (notBlank(r.brandKo()))
			sb.append("브랜드(한글): ").append(r.brandKo()).append('\n');
		else if (notBlank(r.brand()))
			sb.append("브랜드: ").append(r.brand()).append('\n');
		if (notBlank(r.rootCategory()))
			sb.append("카테고리: ").append(r.rootCategory()).append('\n');
		if (r.packageQuantity() != null)
			sb.append("수량: ").append(r.packageQuantity())
				.append(nz(r.measureUnitDesc())).append('\n');
		if (notBlank(r.ingredientsSummary()))
			sb.append("주요 성분: ").append(trim(r.ingredientsSummary(), 200)).append('\n');

		sb.append("""

			규칙:
			- baseName: 한국 소비자가 검색할 법한 핵심 상품명. 브랜드명과 수량은 제외한다.
			  %d자 이내. 영어 성분명은 한국에서 통용되는 한글 표기로 바꾼다.
			- keywords: 쿠팡·네이버에서 실제로 검색되는 말 %d개. 성분명, 효능 연관어, 제형,
			  '해외직구'류 일반어를 섞는다. 각 키워드는 20자 이내이고 중복되지 않는다.
			- categoryHint: "대분류 > 소분류" 한 줄.
			- 의학적 효능·효과를 단정하는 표현(치료, 완치, 예방)은 쓰지 마라.

			JSON만 출력:
			{"baseName":"","keywords":[],"categoryHint":""}"""
			.formatted(MAX_BASE_NAME_LENGTH, KEYWORD_TARGET));
		return sb.toString();
	}

	// --- HTTP ---

	private String chat(String model, String prompt) throws Exception {
		Map<String, Object> body = Map.of(
			"model", model,
			"messages", List.of(
				Map.of("role", "system", "content", SYSTEM_PROMPT),
				Map.of("role", "user", "content", prompt)),
			"max_tokens", MAX_TOKENS,
			"temperature", 0.3);

		HttpRequest httpRequest = HttpRequest.newBuilder()
			.uri(URI.create(baseUrl + "/chat/completions"))
			.timeout(Duration.ofSeconds(120))
			.header("Authorization", "Bearer " + apiKey)
			.header("Content-Type", "application/json")
			// UA를 명시한다. Zen 앞단이 일부 클라이언트 UA를 403으로 막는다(python-urllib 실측).
			// JDK 기본 UA는 현재 통과하지만, 기본값이 바뀌면 조용히 전건 실패로 돌아선다.
			.header("User-Agent", "sbshop-agent/1.0")
			.POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
			.build();

		HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) {
			throw new IllegalStateException("Zen HTTP " + response.statusCode());
		}
		JsonNode root = objectMapper.readTree(response.body());
		JsonNode message = root.path("choices").path(0).path("message");
		String content = message.path("content").asText("");
		if (content.isBlank()) {
			// 일부 모델은 본문을 reasoning_content로만 내보낸다.
			content = message.path("reasoning_content").asText("");
		}
		return content;
	}

	// --- 파싱 ---

	/**
	 * 응답에서 JSON 객체를 뽑아 파싱한다.
	 *
	 * <p>무료 모델은 코드펜스(```json)나 앞뒤 설명을 붙이는 일이 잦아 문자열 전체를 그대로
	 * 파싱하면 실패한다. 첫 '{'부터 마지막 '}'까지 잘라 시도한다.
	 */
	private GeneratedProductText parse(String content, String model) {
		String json = extractJsonObject(content);
		if (json == null)
			return null;
		try {
			JsonNode node = objectMapper.readTree(json);
			String baseName = node.path("baseName").asText("").trim();
			if (baseName.isEmpty())
				return null;
			if (baseName.length() > MAX_BASE_NAME_LENGTH)
				baseName = baseName.substring(0, MAX_BASE_NAME_LENGTH).trim();

			Set<String> keywords = new LinkedHashSet<>();
			for (JsonNode k : node.path("keywords")) {
				String kw = k.asText("").trim();
				if (!kw.isEmpty() && kw.length() <= 20)
					keywords.add(kw);
			}
			String categoryHint = node.path("categoryHint").asText("").trim();
			return new GeneratedProductText(baseName, new ArrayList<>(keywords),
				categoryHint.isEmpty() ? null : categoryHint, model);
		} catch (Exception e) {
			return null;
		}
	}

	private String extractJsonObject(String content) {
		if (content == null || content.isBlank())
			return null;
		int start = content.indexOf('{');
		int end = content.lastIndexOf('}');
		if (start < 0 || end <= start)
			return null;
		return content.substring(start, end + 1);
	}

	private static boolean notBlank(String s) {
		return s != null && !s.isBlank();
	}

	private static String nz(String s) {
		return s == null ? "" : s;
	}

	private static String trim(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max);
	}
}
