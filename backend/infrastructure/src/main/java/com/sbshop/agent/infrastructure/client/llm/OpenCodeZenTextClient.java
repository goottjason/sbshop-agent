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

@Slf4j
@Component
public class OpenCodeZenTextClient implements ProductTextGenerationPort {

	private static final int KEYWORD_TARGET = 20;
	private static final int MAX_BASE_NAME_LENGTH = 40;
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
			content = message.path("reasoning_content").asText("");
		}
		return content;
	}

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
