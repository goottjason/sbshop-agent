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
import java.util.ArrayList;
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
	public Map<String, Object> syncImagesAndHtml(com.sbshop.agent.core.domain.product.Product product,
		String marketItemId, Map<String, Object> currentRawData,
		List<String> hostedImages, String newDetailHtml) {
		try {
			String response = restClient.get("/v2/products/origin-products/" + marketItemId);
			log.info("[D092][스토어] origin-products GET (len={}): {}", response == null ? -1 : response.length(),
				response == null ? "null" : response.substring(0, Math.min(response.length(), 3000)));
			JsonNode originNode = objectMapper.readTree(response).path("originProduct");
			Map<String, Object> originProduct = objectMapper.convertValue(originNode, Map.class);

			if (!hostedImages.isEmpty()) {
				// D-092: 네이버는 외부 URL(R2)을 대표이미지로 거부("올바른 이미지 파일이 아닙니다") →
				// 네이버 이미지 서버에 직접 업로드하고 반환된 네이버 URL로 등록한다(buying-agent 검증).
				List<String> targetImages = uploadImagesToNaver(hostedImages).stream()
					.map(this::ensureImageExtension).toList();
				// 커머스API 스키마: originProduct.images.representativeImage.url. 기존 images 보존, representative/optional만 덮어쓴다.
				applyImages(originProduct, targetImages);
			}
			// D-092: 해외 상품 관부가세 필수 — customsTaxType 미설정 시 수정 거부되므로 INCLUDED로 채운다.
			applyCustomsTaxType(originProduct);
			if (newDetailHtml != null) {
				// D-092: 수동 이스케이프 제거 — Map 값은 Jackson이 직렬화 시 이스케이프하므로 이중 이스케이프(HTML 손상)였다.
				originProduct.put("detailContent", newDetailHtml);
			}

			Map<String, Object> requestBody = new HashMap<>();
			requestBody.put("originProduct", originProduct);
			try {
				log.info("[D092][스토어] PUT images={}, detailContent(len={})",
					objectMapper.writeValueAsString(originProduct.get("images")),
					newDetailHtml == null ? -1 : newDetailHtml.length());
			} catch (Exception ignore) { /* 로깅 실패 무시 */ }
			String putResp = restClient.put("/v2/products/origin-products/" + marketItemId, requestBody);
			log.info("[D092][스토어] PUT resp: {}", putResp);

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

	/** 백필용 전체 스캔 진입점(인터페이스): 스토어는 search 필터 미지원이라 전체 페이지 순회로 맵을 구축한다. */
	@Override
	public Map<String, String> fetchAllLinkIdentifiers(long throttleMs) {
		return fetchAllChannelProductNos(throttleMs);
	}

	/**
	 * 백필용 전체 스캔: 스토어 전 상품을 상품검색 API로 페이지네이션 순회해
	 * (originProductNo → STOREFARM channelProductNo) 맵을 통째로 구축한다.
	 * <p>주의: {@code /v1/products/search}는 originProductNos 필터를 무시하고 전체를 반환하므로
	 * (라이브 확인됨), 특정 번호 필터 대신 전체 페이지를 훑는다. total 수천 건이라도 size=500이면
	 * 수 페이지로 끝나 429 없이 완주한다. 페이지 간 지연은 {@code throttleMs}로 제어.
	 */
	public Map<String, String> fetchAllChannelProductNos(long throttleMs) {
		Map<String, String> all = new HashMap<>();
		int page = 1;
		int maxPages = 200; // 안전 상한(무한루프 방지)
		while (page <= maxPages) {
			try {
				Map<String, Object> body = new HashMap<>();
				body.put("page", page);
				body.put("size", 500);
				String response = restClient.post("/v1/products/search", body);
				JsonNode root = objectMapper.readTree(response);
				JsonNode contents = root.path("contents");
				for (JsonNode content : contents) {
					String originNo = content.path("originProductNo").asText("");
					if (originNo.isBlank()) {
						continue;
					}
					String ch = pickChannelProductNo(content.path("channelProducts"));
					if (ch != null) {
						all.put(originNo, ch);
					}
				}
				boolean last = root.path("last").asBoolean(true) || !contents.isArray() || contents.isEmpty();
				log.info("[Smartstore] 전체 채널상품 스캔 page={} 누적 {}건 (last={})", page, all.size(), last);
				if (last) {
					break;
				}
				page++;
				if (throttleMs > 0) {
					Thread.sleep(throttleMs);
				}
			} catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
				break;
			} catch (Exception e) {
				log.warn("[Smartstore] 전체 채널상품 스캔 실패 page={}: {}", page, e.getMessage());
				break;
			}
		}
		return all;
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
	 * D-092: 이미지를 네이버 커머스 이미지 서버에 업로드하고 반환 URL 목록을 돌려준다(buying-agent 이식).
	 * 실패 시 외부 URL(hostedImages)로 폴백한다.
	 */
	private List<String> uploadImagesToNaver(List<String> hostedImages) {
		try {
			org.springframework.util.MultiValueMap<String, Object> body =
				new org.springframework.util.LinkedMultiValueMap<>();
			for (String imageUrl : hostedImages) {
				byte[] bytes = downloadImage(imageUrl);
				if (bytes != null) {
					org.springframework.core.io.ByteArrayResource res =
						new org.springframework.core.io.ByteArrayResource(bytes) {
							@Override
							public String getFilename() {
								return "image.jpg";
							}
						};
					body.add("imageFiles", res);
				}
			}
			if (!body.isEmpty()) {
				JsonNode resp = restClient.uploadImages(body);
				if (resp != null && resp.has("images")) {
					List<String> naverUrls = new ArrayList<>();
					for (JsonNode img : resp.get("images")) {
						naverUrls.add(img.get("url").asText());
					}
					if (!naverUrls.isEmpty()) {
						log.info("[D092][스토어] 네이버 이미지 업로드 {}개 완료", naverUrls.size());
						return naverUrls;
					}
				}
			}
		} catch (Exception e) {
			log.warn("[D092][스토어] 네이버 이미지 업로드 실패 — 외부 URL 폴백: {}", e.getMessage());
		}
		return hostedImages;
	}

	private byte[] downloadImage(String imageUrl) {
		try (java.io.InputStream is = new java.net.URL(imageUrl).openStream()) {
			return is.readAllBytes();
		} catch (Exception e) {
			log.error("[D092][스토어] 이미지 다운로드 실패: {} - {}", imageUrl, e.getMessage());
			return null;
		}
	}

	/** 확장자 없으면 .jpg 힌트 추가(네이버 이미지 검증 통과용). */
	private String ensureImageExtension(String url) {
		if (url == null || url.isBlank()) {
			return url;
		}
		String low = url.toLowerCase();
		if (!low.contains(".jpg") && !low.contains(".jpeg") && !low.contains(".png") && !low.contains(".gif")) {
			return url + (url.contains("?") ? "&" : "?") + "f=.jpg";
		}
		return url;
	}

	/** D-092: 해외 상품 관부가세 필수 — originProduct.detailAttribute.customsTaxType=INCLUDED. */
	@SuppressWarnings("unchecked")
	private void applyCustomsTaxType(Map<String, Object> originProduct) {
		Object da = originProduct.get("detailAttribute");
		Map<String, Object> detailAttribute = (da instanceof Map)
			? (Map<String, Object>) da : new HashMap<>();
		detailAttribute.put("customsTaxType", "INCLUDED");
		originProduct.put("detailAttribute", detailAttribute);
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
