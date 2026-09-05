package com.sbshop.agent.infrastructure.client.cafe24.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.infrastructure.client.cafe24.client.Cafe24RestClient;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class Cafe24OriginResolver {

	public record Origin(String classification, int placeNo, String placeValue) {
	}

	private static final int OTHER_PLACE_NO = 1800;
	private static final String OTHER_CLASSIFICATION = "E";
	private static final int MAX_OTHER_VALUE_LENGTH = 30;

	private final Cafe24RestClient cafe24RestClient;
	private final ObjectMapper objectMapper;

	private final Map<String, Origin> cache = new ConcurrentHashMap<>();

	public Origin resolve(String originText) {
		String name = originText.trim();
		Origin cached = cache.get(name);
		if (cached != null) {
			return cached;
		}
		Optional<Origin> matched;
		try {
			matched = findExisting(name);
		} catch (RuntimeException e) {
			log.warn("[카페24] 원산지 조회 실패 — 기타(1800)로 등록한다: origin={} 사유={}", name, e.getMessage());
			return other(name);
		}
		Origin resolved = matched.orElseGet(() -> {
			log.info("[카페24] 원산지 목록에 없음 — 기타(1800)로 등록한다: origin={}", name);
			return other(name);
		});
		cache.put(name, resolved);
		return resolved;
	}

	private Optional<Origin> findExisting(String name) {
		JsonNode root;
		try {
			root = objectMapper.readTree(
				cafe24RestClient.get("/admin/origin?origin_place_name=" + name + "&limit=100"));
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new IllegalStateException("[카페24] 원산지 응답 파싱 실패: " + name, e);
		}
		JsonNode origins = root.path("origins");
		if (!origins.isArray()) {
			origins = root.path("origin");
		}
		for (JsonNode origin : origins) {
			if (!name.equals(placeName(origin.path("origin_place_name")))) {
				continue;
			}
			String classification = "T".equalsIgnoreCase(origin.path("foreign").asText("")) ? "T" : "F";
			return Optional.of(new Origin(classification, origin.path("origin_place_no").asInt(), null));
		}
		return Optional.empty();
	}

	private static String placeName(JsonNode node) {
		if (node.isArray()) {
			return node.size() == 0 ? "" : node.get(node.size() - 1).asText("");
		}
		return node.asText("");
	}

	private Origin other(String name) {
		String value = name.length() > MAX_OTHER_VALUE_LENGTH
			? name.substring(0, MAX_OTHER_VALUE_LENGTH) : name;
		return new Origin(OTHER_CLASSIFICATION, OTHER_PLACE_NO, value);
	}
}
