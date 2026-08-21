package com.sbshop.agent.infrastructure.client.smartstore.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.sourcing.port.MarketAccountResourcePort;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.infrastructure.client.smartstore.client.SmartstoreRestClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SmartstoreAddressBookResolver implements MarketAccountResourcePort {

	private static final List<String> PATHS = List.of(
		"/v1/seller/addressbooks-for-page?page=1&size=100",
		"/v1/seller/addressbooks");

	private static final String RELEASE_PREFIX = "RELEASE";
	private static final String REFUND_PREFIX = "REFUND";

	private final SmartstoreRestClient restClient;
	private final ObjectMapper objectMapper;

	private final AtomicReference<Map<String, String>> cache = new AtomicReference<>();

	@Value("${market.smartstore.shipping-address-id:}")
	private String configuredShippingAddressId;

	@Value("${market.smartstore.return-address-id:}")
	private String configuredReturnAddressId;

	@Override
	public MarketType market() {
		return MarketType.SMART_STORE;
	}

	@Override
	public Map<String, String> resolve() {
		Map<String, String> cached = cache.get();
		if (cached != null)
			return cached;

		Map<String, String> resolved = new LinkedHashMap<>();
		if (notBlank(configuredShippingAddressId))
			resolved.put("shippingAddressId", configuredShippingAddressId.trim());
		if (notBlank(configuredReturnAddressId))
			resolved.put("returnAddressId", configuredReturnAddressId.trim());

		if (resolved.size() < 2) {
			resolved.putAll(fetchFromApi(resolved.keySet()));
		}

		if (resolved.containsKey("shippingAddressId") || resolved.containsKey("returnAddressId")) {
			cache.set(Map.copyOf(resolved));
		}
		return resolved;
	}

	@Override
	public void invalidate() {
		cache.set(null);
	}

	private Map<String, String> fetchFromApi(Set<String> alreadyResolved) {
		Map<String, String> out = new LinkedHashMap<>();
		for (String path : PATHS) {
			try {
				JsonNode root = objectMapper.readTree(restClient.get(path));
				JsonNode list = root.isArray() ? root
					: root.has("contents") ? root.path("contents") : root.path("content");
				if (!list.isArray() || list.isEmpty())
					continue;

				String release = pickByTypePrefix(list, RELEASE_PREFIX);
				String refund = pickByTypePrefix(list, REFUND_PREFIX);
				String fallback = addressNo(list.get(0));

				if (!alreadyResolved.contains("shippingAddressId")) {
					String v = release != null ? release : fallback;
					if (v != null)
						out.put("shippingAddressId", v);
				}
				if (!alreadyResolved.contains("returnAddressId")) {
					String v = refund != null ? refund : (release != null ? release : fallback);
					if (v != null)
						out.put("returnAddressId", v);
				}
				log.info("[스토어주소록] 자동 조회 성공 — {} (path={})", out, path);
				return out;
			} catch (Exception e) {
				log.warn("[스토어주소록] 조회 실패 path={}: {}", path, e.getMessage());
			}
		}
		log.warn("[스토어주소록] 자동 조회에 실패했습니다 — 검수 화면에 '출고지/반품지 주소ID 미충족'으로 표시됩니다.");
		return out;
	}

	private String pickByTypePrefix(JsonNode list, String prefix) {
		for (JsonNode node : list) {
			String type = firstText(node, "addressType", "addressBookType", "bookType");
			if (type != null && type.toUpperCase().startsWith(prefix)) {
				String no = addressNo(node);
				if (no != null)
					return no;
			}
		}
		return null;
	}

	private String addressNo(JsonNode node) {
		return firstText(node, "addressBookNo", "addressBookNumber", "id", "no");
	}

	private String firstText(JsonNode node, String... fields) {
		for (String f : fields) {
			JsonNode v = node.path(f);
			if (!v.isMissingNode() && !v.isNull()) {
				String s = v.asText().trim();
				if (!s.isEmpty())
					return s;
			}
		}
		return null;
	}

	private static boolean notBlank(String s) {
		return s != null && !s.isBlank();
	}
}
