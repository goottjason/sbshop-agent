package com.sbshop.agent.infrastructure.client.smartstore.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.market.client.MarketClient;
import com.sbshop.agent.core.domain.market.client.dto.MarketItemInfo;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.infrastructure.client.smartstore.client.SmartstoreRestClient;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SmartstoreMarketClient implements MarketClient {

	private final SmartstoreRestClient restClient;
	private final ObjectMapper objectMapper;

	@Override
	public MarketType getSupportedMarket() {
		return MarketType.SMART_STORE;
	}

	@Override
	public Map<String, String> publish(Product product) {
		log.info("[Smartstore] 상품 등록 시작: {}", product.getSbCode());
		try {
			List<String> hostedImages = product.getHostedImages();

			Map<String, Object> originProduct = new HashMap<>();
			originProduct.put("productName", product.getProductName());
			originProduct.put("salePrice", product.getSalePrice() != null ? product.getSalePrice().intValue() : 0);
			originProduct.put("stockQuantity", product.getStock() != null ? product.getStock() : 0);
			originProduct.put("productCode", product.getSbCode());
			if (product.getDetailHtml() != null) {
				originProduct.put("detailContent", product.getDetailHtml().replace("\"", "\\\"").replace("\n", ""));
			}
			if (!hostedImages.isEmpty()) {
				// 커머스API 스키마: originProduct.images.representativeImage.url (오브젝트).
				// 최상위 문자열 representativeImage 는 Naver가 조용히 무시하므로 대표이미지가 바뀌지 않는다.
				applyImages(originProduct, hostedImages);
			}

			Map<String, Object> requestBody = new HashMap<>();
			requestBody.put("originProduct", originProduct);

			String response = restClient.post("/v2/products", requestBody);
			JsonNode node = objectMapper.readTree(response);
			String originProductNo = node.path("originProduct").path("originProductNo").asText("");

			log.info("[Smartstore] 상품 등록 성공: originProductNo={}", originProductNo);
			Map<String, String> identifiers = new HashMap<>();
			identifiers.put("originProductNo", originProductNo);
			return identifiers;
		} catch (Exception e) {
			log.error("[Smartstore] 상품 등록 실패: {}", e.getMessage());
			throw new RuntimeException("Smartstore 상품 등록 오류", e);
		}
	}

	@Override
	public MarketItemInfo extractMarketItem(String marketItemId) {
		String response = restClient.get("/v2/products/origin-products/" + marketItemId);
		try {
			JsonNode originNode = objectMapper.readTree(response).path("originProduct");
			return MarketItemInfo.builder()
				.isMasterData(true)
				.name(originNode.path("productName").asText(null))
				.mappingKey(originNode.path("productCode").asText(""))
				.salePrice(BigDecimal.valueOf(originNode.path("salePrice").asDouble(0)))
				.stock(originNode.path("stockQuantity").asInt(0))
				.rawData(objectMapper.convertValue(originNode, Map.class))
				.build();
		} catch (Exception e) {
			log.error("[Smartstore] 상품 정보 추출 실패 (ID: {}): {}", marketItemId, e.getMessage());
			throw new RuntimeException("Smartstore 데이터 추출 오류", e);
		}
	}

	@Override
	public void deleteFromMarket(String marketItemId) {
		// marketItemId 는 등록 시 저장한 originProductNo (publish → identifiers "originProductNo").
		// 원상품 삭제 API 호출. 주문이력 등으로 하드삭제가 거부되면 API 오류 → 예외 표면화(best-effort 수집 대상).
		log.info("[Smartstore] 상품 삭제 시작: originProductNo={}", marketItemId);
		try {
			restClient.delete("/v2/products/origin-products/" + marketItemId);
			log.info("[Smartstore] 상품 삭제 성공: originProductNo={}", marketItemId);
		} catch (RuntimeException e) {
			log.error("[Smartstore] 상품 삭제 실패 (originProductNo: {}): {}", marketItemId, e.getMessage());
			throw e; // 실패 표면화(SP-A/SP-C 원칙)
		}
	}

	@Override
	public MarketItemInfo parseLocalData(Map<String, Object> rawData) {
		if (rawData == null || rawData.isEmpty()) {
			return MarketItemInfo.builder().build();
		}
		return MarketItemInfo.builder()
			.isMasterData(true)
			.name(rawData.get("productName") != null ? String.valueOf(rawData.get("productName")) : null)
			.salePrice(
				rawData.get("salePrice") != null ? new BigDecimal(String.valueOf(rawData.get("salePrice"))) : null)
			.stock(rawData.get("stockQuantity") != null ? Integer.parseInt(String.valueOf(rawData.get("stockQuantity")))
				: null)
			.rawData(rawData)
			.build();
	}

	@Override
	public Map<String, Object> syncPriceAndStock(String marketItemId, Map<String, Object> currentRawData,
		Integer price, int quantity, boolean soldOut) {
		try {
			String response = restClient.get("/v2/products/origin-products/" + marketItemId);
			JsonNode originNode = objectMapper.readTree(response).path("originProduct");
			Map<String, Object> originProduct = objectMapper.convertValue(originNode, Map.class);

			if (price != null)
				originProduct.put("salePrice", price);
			if (soldOut) {
				// 스마트스토어: 재고 0이면 API가 자동으로 OUTOFSTOCK(품절) 처리(statusType 무시됨).
				// OUTOFSTOCK은 수정 API에서 직접 지정 불가라 재고 0으로 품절을 표현한다.
				originProduct.put("stockQuantity", 0);
			} else {
				originProduct.put("stockQuantity", quantity);
				// 판매중 복귀: 마켓 잠금상태(SUSPENSION/PROHIBITION)는 보존, 그 외에만 SALE.
				Object current = originProduct.get("statusType");
				if (current == null || "SALE".equals(current) || "OUTOFSTOCK".equals(current)) {
					originProduct.put("statusType", "SALE");
				}
			}

			Map<String, Object> requestBody = new HashMap<>();
			requestBody.put("originProduct", originProduct);
			restClient.put("/v2/products/origin-products/" + marketItemId, requestBody);

			log.info("[Smartstore] 가격/재고/판매상태 업데이트 완료: {}", marketItemId);
			if (currentRawData != null) {
				if (price != null)
					currentRawData.put("salePrice", price);
				currentRawData.put("stockQuantity", soldOut ? 0 : quantity);
			}
		} catch (RuntimeException e) {
			log.error("[Smartstore] 가격/재고 업데이트 실패: {}", e.getMessage());
			throw e; // 실패 표면화(SP-A 원칙)
		} catch (Exception e) {
			log.error("[Smartstore] 가격/재고 업데이트 실패: {}", e.getMessage());
			throw new RuntimeException("[Smartstore] 가격/재고 업데이트 실패", e); // 실패 표면화(SP-A 원칙)
		}
		return currentRawData;
	}

	@Override
	public Map<String, Object> syncImagesAndHtml(String marketItemId, Map<String, Object> currentRawData,
		List<String> hostedImages, String newDetailHtml) {
		try {
			String response = restClient.get("/v2/products/origin-products/" + marketItemId);
			JsonNode originNode = objectMapper.readTree(response).path("originProduct");
			Map<String, Object> originProduct = objectMapper.convertValue(originNode, Map.class);

			if (!hostedImages.isEmpty()) {
				// 커머스API 스키마: originProduct.images.representativeImage.url (오브젝트).
				// 최상위 문자열 representativeImage 는 Naver가 조용히 무시하므로 대표이미지가 바뀌지 않는다.
				// GET 응답의 기존 images 오브젝트를 보존하고 representative/optional 만 덮어쓴다.
				applyImages(originProduct, hostedImages);
			}
			if (newDetailHtml != null) {
				originProduct.put("detailContent", newDetailHtml.replace("\"", "\\\"").replace("\n", ""));
			}

			Map<String, Object> requestBody = new HashMap<>();
			requestBody.put("originProduct", originProduct);
			restClient.put("/v2/products/origin-products/" + marketItemId, requestBody);

			log.info("[Smartstore] 이미지/HTML 동기화 완료: {}", marketItemId);
			if (currentRawData != null) {
				if (!hostedImages.isEmpty())
					// currentRawData 미러도 스키마와 일관되게 {url} 오브젝트로 저장.
					currentRawData.put("representativeImage", Map.of("url", hostedImages.get(0)));
				if (newDetailHtml != null)
					currentRawData.put("detailContent", newDetailHtml);
			}
		} catch (RuntimeException e) {
			log.error("[Smartstore] 이미지/HTML 동기화 실패: {}", e.getMessage());
			throw e; // 실패 표면화(SP-A/SP-C 원칙)
		} catch (Exception e) {
			log.error("[Smartstore] 이미지/HTML 동기화 실패: {}", e.getMessage());
			throw new RuntimeException("[Smartstore] 이미지/HTML 동기화 실패: " + e.getMessage(), e); // 실패 표면화(SP-A/SP-C 원칙)
		}
		return currentRawData;
	}

	/** 백필용: sourceIdentifier(=originProductNo)로 링크식별자(channelProductNo)를 조회한다. */
	@Override
	public java.util.Optional<String> fetchLinkIdentifier(String sourceIdentifier) {
		return fetchChannelProductNo(sourceIdentifier);
	}

	/**
	 * 백필용 배치 조회: 여러 originProductNo를 상품검색 API 1회로 조회해 channelProductNo 맵을 반환한다.
	 * Naver search rate limit이 빡빡해 단건 반복은 429가 잦으므로, 배열 요청으로 요청 수를 크게 줄인다.
	 * 결과 맵 키=요청한 originProductNo(String), 값=STOREFARM channelProductNo(없으면 미포함).
	 */
	@Override
	public Map<String, String> fetchLinkIdentifiers(List<String> originProductNos) {
		Map<String, String> result = new HashMap<>();
		if (originProductNos == null || originProductNos.isEmpty()) {
			return result;
		}
		List<Long> nums = new java.util.ArrayList<>();
		for (String s : originProductNos) {
			if (s != null && !s.isBlank()) {
				try {
					nums.add(Long.parseLong(s.trim()));
				} catch (NumberFormatException ignore) {
					// 숫자 아닌 originProductNo는 스킵
				}
			}
		}
		if (nums.isEmpty()) {
			return result;
		}
		try {
			Map<String, Object> body = new HashMap<>();
			body.put("originProductNos", nums);
			body.put("page", 1);
			body.put("size", nums.size());

			String response = restClient.post("/v1/products/search", body);
			JsonNode root = objectMapper.readTree(response);
			// [임시 디버그] search 응답 구조 확인: 최상위 필드, contents 크기·첫 요소 키·originProductNo 반영 여부.
			java.util.List<String> topKeys = new java.util.ArrayList<>();
			root.fieldNames().forEachRemaining(topKeys::add);
			JsonNode contentsNode = root.path("contents");
			JsonNode first = contentsNode.isArray() && contentsNode.size() > 0 ? contentsNode.get(0) : null;
			java.util.List<String> firstKeys = new java.util.ArrayList<>();
			if (first != null) {
				first.fieldNames().forEachRemaining(firstKeys::add);
			}
			log.warn("[Smartstore DEBUG] 요청 {}건(예:{}) | 응답 topKeys={} total={} contentsSize={} firstKeys={} firstOriginNo={}",
				nums.size(), nums.get(0), topKeys, root.path("totalElements").asText("?"),
				contentsNode.isArray() ? contentsNode.size() : -1, firstKeys,
				first != null ? first.path("originProductNo").asText("?") : "n/a");
			for (JsonNode content : root.path("contents")) {
				String originNo = content.path("originProductNo").asText("");
				if (originNo.isBlank()) {
					continue;
				}
				String ch = pickChannelProductNo(content.path("channelProducts"));
				if (ch != null) {
					result.put(originNo, ch);
				}
			}
		} catch (Exception e) {
			log.warn("[Smartstore] channelProductNo 배치 조회 실패 ({}건): {}", nums.size(), e.getMessage());
		}
		return result;
	}

	/** 상품검색 API로 originProductNo → 스마트스토어 channelProductNo 를 조회한다(상품 링크용). 없으면 empty. */
	public java.util.Optional<String> fetchChannelProductNo(String originProductNo) {
		if (originProductNo == null || originProductNo.isBlank()) {
			return java.util.Optional.empty();
		}
		try {
			long originNo = Long.parseLong(originProductNo.trim());
			Map<String, Object> body = new HashMap<>();
			body.put("originProductNos", List.of(originNo));
			body.put("page", 1);
			body.put("size", 50);

			String response = restClient.post("/v1/products/search", body);
			JsonNode root = objectMapper.readTree(response);

			for (JsonNode content : root.path("contents")) {
				if (!originProductNo.trim().equals(content.path("originProductNo").asText())) {
					continue;
				}
				String ch = pickChannelProductNo(content.path("channelProducts"));
				if (ch != null) {
					return java.util.Optional.of(ch);
				}
			}
			return java.util.Optional.empty();
		} catch (Exception e) {
			log.warn("[Smartstore] channelProductNo 조회 실패: {}", e.getMessage());
			return java.util.Optional.empty();
		}
	}

	/** channelProducts 배열에서 STOREFARM 채널의 channelProductNo를 고른다(없으면 첫 채널, 그마저 없으면 null). */
	private String pickChannelProductNo(JsonNode channelProducts) {
		JsonNode chosen = null;
		for (JsonNode cp : channelProducts) {
			if ("STOREFARM".equals(cp.path("channelServiceType").asText())) {
				chosen = cp;
				break;
			}
			if (chosen == null) {
				chosen = cp;
			}
		}
		if (chosen != null) {
			String ch = chosen.path("channelProductNo").asText("");
			if (!ch.isBlank()) {
				return ch;
			}
		}
		return null;
	}

	/**
	 * 커머스API 이미지 스키마 적용: originProduct.images.representativeImage.url (오브젝트),
	 * originProduct.images.optionalImages = [{url}, ...].
	 * 최상위 문자열 representativeImage/optionalImages 는 Naver가 조용히 무시하므로 반드시 images 하위 오브젝트여야 한다.
	 * 기존 images 오브젝트가 있으면 그 안의 다른 필드는 보존하고 representative/optional 만 덮어쓴다.
	 */
	@SuppressWarnings("unchecked")
	private void applyImages(Map<String, Object> originProduct, List<String> hostedImages) {
		Object existing = originProduct.get("images");
		Map<String, Object> images = (existing instanceof Map)
			? (Map<String, Object>) existing
			: new HashMap<>();

		images.put("representativeImage", Map.of("url", hostedImages.get(0)));
		if (hostedImages.size() > 1) {
			List<Map<String, Object>> optionalImages = hostedImages.subList(1, hostedImages.size()).stream()
				.map(u -> Map.<String, Object>of("url", u))
				.toList();
			images.put("optionalImages", optionalImages);
		} else {
			// 단일 이미지: 이전에 남아있던 optionalImages 는 제거하여 대표만 남긴다.
			images.remove("optionalImages");
		}

		originProduct.put("images", images);
	}
}
