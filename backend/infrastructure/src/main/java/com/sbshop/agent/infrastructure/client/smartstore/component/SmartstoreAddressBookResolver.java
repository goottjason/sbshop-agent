package com.sbshop.agent.infrastructure.client.smartstore.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.sourcing.port.MarketAccountResourcePort;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.infrastructure.client.smartstore.client.SmartstoreRestClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 스마트스토어 출고지·반품지 주소록 ID 자동 조회.
 *
 * <p>커머스API {@code originProduct.deliveryInfo.claimDeliveryInfo}는
 * {@code shippingAddressId}(출고지)와 {@code returnAddressId}(반품지)를 요구한다. 이 값은
 * 판매자 계정 주소록의 일련번호라 계정마다 다르고, 스마트스토어 화면에서 찾아 옮겨 적기 번거롭다.
 * 주소록 API로 한 번 읽어 캐시한다 — 주소록은 거의 바뀌지 않는다.
 *
 * <p>조회 경로는 커머스API 버전에 따라 둘 중 하나다. 순서대로 시도한다:
 * <pre>
 *   GET /v1/seller/addressbooks-for-page?page=1&size=100
 *   GET /v1/seller/addressbooks
 * </pre>
 *
 * <p>주소 유형 코드도 표기가 흔들려서({@code RELEASE}/{@code REFUND}/{@code REFUND_OR_EXCHANGE})
 * 정확히 일치시키지 않고 <b>접두 매칭</b>으로 고른다. 유형을 못 찾으면 대표 주소나 첫 항목으로 폴백한다 —
 * 값이 아예 없는 것보다는 낫고, 틀렸다면 검수 화면에서 사용자가 고칠 수 있다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SmartstoreAddressBookResolver implements MarketAccountResourcePort {

	private static final List<String> PATHS = List.of(
		"/v1/seller/addressbooks-for-page?page=1&size=100",
		"/v1/seller/addressbooks");

	/** 출고지 유형 접두. */
	private static final String RELEASE_PREFIX = "RELEASE";
	/** 반품/교환지 유형 접두. */
	private static final String REFUND_PREFIX = "REFUND";

	private final SmartstoreRestClient restClient;
	private final ObjectMapper objectMapper;

	private final AtomicReference<Map<String, String>> cache = new AtomicReference<>();

	/** 설정으로 직접 지정하면 조회하지 않는다(자동 조회가 틀렸을 때의 탈출구). */
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

		// 둘 다 못 찾았으면 캐시하지 않는다 — 일시적 API 장애를 영구 결측으로 굳히면 안 된다.
		if (resolved.containsKey("shippingAddressId") || resolved.containsKey("returnAddressId")) {
			cache.set(Map.copyOf(resolved));
		}
		return resolved;
	}

	@Override
	public void invalidate() {
		cache.set(null);
	}

	private Map<String, String> fetchFromApi(java.util.Set<String> alreadyResolved) {
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
					// 반품지가 따로 없으면 출고지를 쓴다(국내 반품지를 별도로 두지 않는 계정이 있다).
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
