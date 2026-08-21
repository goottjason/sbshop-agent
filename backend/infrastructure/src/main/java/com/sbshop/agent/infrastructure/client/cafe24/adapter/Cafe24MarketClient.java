package com.sbshop.agent.infrastructure.client.cafe24.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.sourcing.dto.MarketCategory;
import com.sbshop.agent.core.domain.market.client.MarketClient;
import com.sbshop.agent.core.domain.market.client.dto.MarketItemInfo;
import com.sbshop.agent.core.domain.market.client.dto.MarketPublishContext;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.enums.ProductCategory;
import com.sbshop.agent.infrastructure.client.cafe24.client.Cafe24RestClient;
import com.sbshop.agent.infrastructure.client.cafe24.component.Cafe24CategoryResolver;
import com.sbshop.agent.infrastructure.client.common.util.HtmlImageExtractor;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class Cafe24MarketClient implements MarketClient {

	private final ObjectMapper objectMapper;
	private final Cafe24RestClient cafe24RestClient;
	private final HtmlImageExtractor imageExtractor;
	private final Cafe24CategoryResolver categoryResolver;

	@Override
	public MarketType getSupportedMarket() {
		return MarketType.CAFE24;
	}

	@Override
	public Map<String, String> publish(Product product) {
		return publish(product, MarketPublishContext.empty());
	}

	/**
	 * Cafe24 자사몰 등록.
	 *
	 * <p>기존 구현은 진열 분류가 없으면 {@code log.warn}만 남기고 그대로 등록했다 — 결과적으로
	 * <b>어느 진열에도 걸리지 않아 고객이 볼 수 없는 상품</b>이 조용히 만들어졌는데, 호출자에게는
	 * 등록 성공으로 보고됐다. 이제는 컨텍스트에 분류가 없으면 {@link Cafe24CategoryResolver}로
	 * 쇼핑몰 분류 목록에서 자동 매칭을 먼저 시도하고, 그래도 못 구하면 등록을 <b>거부</b>한다 —
	 * 유령 상품을 만드는 대신 실패를 표면화한다. "못 구했다"는 (1) 쇼핑몰 분류 목록 자체가 없거나
	 * API 조회가 실패한 경우, (2) 이름 매칭이 안 돼 최저번호 분류로 <b>저신뢰 폴백</b>한 경우
	 * ({@code confident()==false}) 둘 다를 포함한다 — 저신뢰 폴백을 성공으로 치면 "카테고리를
	 * 구할 수 없으면 거부한다"는 사용자 결정이 짐작으로 아무 곳에나 거는 것으로 무력화된다.
	 *
	 * <p>검수된 분류번호({@code categoryId})를 {@code add_category_no}로 넣고 전시·판매 플래그를 켠다.
	 *
	 * <p>Cafe24는 미지원 필드를 보내면 422로 거절하므로, 확신이 있는 필드만 채운다.
	 */
	@Override
	public Map<String, String> publish(Product product, MarketPublishContext context) {
		log.info("[카페24] 상품 등록 시작: {}", product.getSbCode());
		try {
			int salePrice = context.salePrice() != null
				? context.salePrice().intValue()
				: (product.getSalePrice() != null ? product.getSalePrice().intValue() : 0);

			Map<String, Object> productData = new HashMap<>();
			productData.put("shop_no", 1);
			productData.put("product_name", product.getProductName());
			productData.put("custom_product_code", product.getSbCode());
			productData.put("price", String.valueOf(salePrice));
			productData.put("supply_quantity", product.getStock() != null
				? String.valueOf(product.getStock()) : "0");
			// 전시·판매를 켜지 않으면 등록만 되고 쇼핑몰에 보이지 않는다.
			productData.put("display", "T");
			productData.put("selling", "T");
			productData.put("product_condition", "N");
			if (product.getBrand() != null)
				productData.put("brand", product.getBrand());
			if (product.getDetailHtml() != null)
				productData.put("description", product.getDetailHtml());

			String origin = context.extraString("originPlace");
			if (origin != null && !origin.isBlank())
				productData.put("origin_place_value", origin);

			// 진열 분류 — 컨텍스트에 없으면 자동 해석을 먼저 시도하고, 그래도 못 구하면 등록을 거부한다.
			String categoryNo = context.hasCategory() ? context.categoryId() : resolveCategoryOrThrow(product);
			Map<String, Object> category = new HashMap<>();
			category.put("category_no", parseCategoryNo(categoryNo));
			category.put("recommend", "F");
			category.put("new", "T");
			productData.put("add_category_no", List.of(category));

			List<String> hostedImages = product.getHostedImages();
			if (!hostedImages.isEmpty()) {
				productData.put("use_external_image", "T");
				productData.put("list_image", hostedImages.get(0));
				productData.put("detail_image", hostedImages.get(0));
			}

			Map<String, Object> requestBody = new HashMap<>();
			requestBody.put("request", productData);

			String responseJson = cafe24RestClient.post("/admin/products", requestBody);
			JsonNode responseNode = objectMapper.readTree(responseJson);
			JsonNode productNode = responseNode.path("product");
			String productNo = productNode.path("product_no").asText("");
			String productCode = productNode.path("product_code").asText("");
			if (productNo.isEmpty()) {
				throw new RuntimeException("카페24 등록 실패(product_no 없음): "
					+ responseJson.substring(0, Math.min(responseJson.length(), 300)));
			}

			log.info("[카페24] 상품 등록 성공: product_no={}, product_code={}", productNo, productCode);
			Map<String, String> identifiers = new HashMap<>();
			identifiers.put("product_no", productNo);
			identifiers.put("product_code", productCode);
			return identifiers;
		} catch (RuntimeException e) {
			log.error("[카페24] 상품 등록 실패: {}", e.getMessage());
			throw e;
		} catch (Exception e) {
			log.error("[카페24] 상품 등록 실패: {}", e.getMessage());
			throw new RuntimeException("카페24 상품 등록 오류", e);
		}
	}

	private Integer parseCategoryNo(String raw) {
		try {
			return Integer.parseInt(raw.trim());
		} catch (NumberFormatException e) {
			throw new IllegalStateException("카페24 분류번호가 숫자가 아닙니다: " + raw);
		}
	}

	/**
	 * 컨텍스트에 분류가 없을 때 {@link Cafe24CategoryResolver}로 자동 해석한다.
	 *
	 * <p>수정 라운드 1(리뷰 Important): 매칭 실패(용어 불일치) 시 리졸버는 <b>가장 낮은 번호의 분류로
	 * 폴백</b>하며 {@code isResolved()}는 여전히 true, {@code confident()}만 false다. 리졸버 자신의
	 * 주석대로 최저 번호는 "전체상품" 같은 포괄적 루트 분류일 가능성이 높다 — 이걸 성공으로 치면
	 * "카테고리를 구할 수 없으면 거부한다"는 사용자 결정을, 상품이 짐작으로 아무 곳에나 걸리는 것으로
	 * 이름만 바꿔 무력화하는 셈이다. 같은 신호({@code confident()==false})를 소싱 초안 경로
	 * ({@code MarketDraftBuilder})는 이미 차단 사유로 다루고 있으므로, 이 경로도 맞춘다.
	 * {@code isResolved() && confident()} 둘 다일 때만 "구했다"로 인정한다.
	 */
	private String resolveCategoryOrThrow(Product product) {
		MarketCategory resolved = categoryResolver.resolve(
			categoryHint(product), product.getProductName(), product.getBrand());
		if (!resolved.isResolved()) {
			throw new IllegalStateException(
				"[카페24] 진열 분류를 확보하지 못해 등록을 거부합니다: sbCode=" + product.getSbCode()
					+ " — 쇼핑몰 분류 목록 조회 실패(분류 0개 또는 API 오류)로 자동 매칭도 폴백도 불가능합니다. "
					+ "카페24 관리자에서 분류를 확인하거나 market.cafe24.default-category-no 설정으로 고정하세요.");
		}
		if (!resolved.confident()) {
			throw new IllegalStateException(
				"[카페24] 진열 분류 자동 매칭이 확신을 얻지 못해 등록을 거부합니다: sbCode=" + product.getSbCode()
					+ " — 이름 매칭이 되지 않아 가장 낮은 번호의 분류(" + resolved.categoryPath()
					+ ")로 폴백했는데, 그건 보통 포괄적인 루트 분류라 상품이 엉뚱한 곳에 걸립니다. "
					+ "초안 검수 화면에서 이 마켓의 카테고리를 직접 지정한 뒤 등록하세요.");
		}
		log.info("[카페24] 진열 분류 자동 해석: {} (confident=true)", resolved.categoryPath());
		return resolved.categoryId();
	}

	/** 상품의 대분류(ProductCategory)를 Cafe24 분류명 매칭 힌트로 변환한다. */
	private String categoryHint(Product product) {
		ProductCategory category = product.getCategory();
		if (category == null) {
			return null;
		}
		return switch (category) {
			case SUPPLEMENT -> "건강기능식품";
			case FOOD -> "식품";
			case COSMETICS -> "화장품";
			default -> null;
		};
	}

	@Override
	public MarketItemInfo extractMarketItem(String marketItemId) {
		String path = "/admin/products/" + marketItemId + "?embed=variants";
		String responseJson = cafe24RestClient.get(path);
		try {
			JsonNode productNode = objectMapper.readTree(responseJson).path("product");
			String detailHtml = productNode.path("description").asText("");
			String sku = productNode.path("custom_product_code").asText("");
			return MarketItemInfo.builder()
				.isMasterData(true)
				.mappingKey(productNode.path("product_code").asText(""))
				.name(productNode.path("product_name").asText(null))
				.detailHtml(detailHtml)
				.images(imageExtractor.extractSkuImages(detailHtml, sku))
				.rawData(objectMapper.convertValue(productNode, Map.class))
				.build();
		} catch (Exception e) {
			log.error("카페24 상품 정보 추출 실패 (ID: {}): {}", marketItemId, e.getMessage());
			throw new RuntimeException("카페24 데이터 추출 오류", e);
		}
	}

	@Override
	public MarketItemInfo parseLocalData(Map<String, Object> rawData) {
		if (rawData == null || rawData.isEmpty()) {
			return MarketItemInfo.builder().build();
		}
		return MarketItemInfo.builder()
			.isMasterData(true)
			.name(rawData.get("product_name") != null ? String.valueOf(rawData.get("product_name")) : null)
			.mappingKey(
				rawData.get("custom_product_code") != null ? String.valueOf(rawData.get("custom_product_code")) : "")
			.rawData(rawData)
			.build();
	}

	@Override
	public Map<String, Object> syncPriceAndStock(String marketItemId, Map<String, Object> currentRawData,
		Integer price, int quantity, boolean soldOut) {
		// publish()의 price/supply_quantity 필드 + syncImagesAndHtml()의 PUT /admin/products/{id} 패턴을 그대로 사용.
		Map<String, Object> productData = new HashMap<>();
		productData.put("shop_no", 1);
		if (price != null) {
			productData.put("price", price + ".00");
		}
		productData.put("supply_quantity", String.valueOf(quantity)); // 항상 ≥1
		productData.put("selling", soldOut ? "F" : "T"); // 신규, 라이브 검증 필요
		Map<String, Object> requestBody = new HashMap<>();
		requestBody.put("request", productData);
		// 실패 시 예외 전파 → 상위에서 '실패 마켓'으로 표면화.
		cafe24RestClient.put("/admin/products/" + marketItemId, requestBody);
		log.info("[카페24] 가격/재고/판매상태 동기화 완료: {}, price={}, qty={}, soldOut={}", marketItemId, price, quantity, soldOut);

		if (currentRawData != null) {
			if (price != null) {
				currentRawData.put("price", price + ".00");
			}
			if (currentRawData.containsKey("variants")) {
				@SuppressWarnings("unchecked") List<Map<String, Object>> variants = (List<Map<String, Object>>)currentRawData
					.get("variants");
				if (variants != null && !variants.isEmpty()) {
					variants.get(0).put("quantity", quantity);
				}
			}
		}
		return currentRawData;
	}

	@Override
	public void deleteFromMarket(String marketItemId) {
		// marketItemId = Cafe24 product_no. 실패 시 예외 전파 → 상위에서 '실패 마켓'으로 수집(best-effort).
		log.info("[카페24] 상품 삭제 시작: product_no={}", marketItemId);
		cafe24RestClient.delete("/admin/products/" + marketItemId);
		log.info("[카페24] 상품 삭제 성공: product_no={}", marketItemId);
	}

	@Override
	public Map<String, Object> syncImagesAndHtml(com.sbshop.agent.core.domain.product.Product product,
		String marketItemId, Map<String, Object> currentRawData,
		List<String> hostedImages, String newDetailHtml) {
		Map<String, Object> descriptionRequestBody = new HashMap<>();
		Map<String, Object> descriptionData = new HashMap<>();
		descriptionData.put("shop_no", 1);
		descriptionData.put("description", newDetailHtml);
		if (hostedImages != null && !hostedImages.isEmpty()) {
			descriptionData.put("use_external_image", "T");
		}
		descriptionRequestBody.put("request", descriptionData);

		try {
			String descResp = cafe24RestClient.put("/admin/products/" + marketItemId, descriptionRequestBody);
			log.info("[D092][카페24] 상세설명 PUT resp (len={}): {}", descResp == null ? -1 : descResp.length(),
				descResp == null ? "null" : descResp.substring(0, Math.min(descResp.length(), 2000)));
			log.info("[카페24] 상세설명 업데이트 완료: {}", marketItemId);
		} catch (Exception e) {
			log.error("[카페24] 상세설명 업데이트 실패 (ID: {}): {}", marketItemId, e.getMessage());
		}

		if (hostedImages != null && !hostedImages.isEmpty()) {
			try {
				try {
					cafe24RestClient.delete("/admin/products/" + marketItemId + "/images");
				} catch (Exception e) {
					log.warn("[카페24] 기존 이미지 삭제 중 경고: {}", e.getMessage());
				}

				String mainImageUrl = hostedImages.get(0);
				byte[] imageBytes = cafe24RestClient.getExternalImageBytes(mainImageUrl);
				if (imageBytes != null) {
					String base64Content = Base64.getEncoder().encodeToString(imageBytes);
					String dataUri = "data:image/jpeg;base64," + base64Content;

					Map<String, Object> imageRequestBody = new HashMap<>();
					Map<String, Object> imageData = new HashMap<>();
					imageData.put("shop_no", 1);
					imageData.put("image_upload_type", "B");
					imageData.put("detail_image", dataUri);
					imageData.put("list_image", dataUri);
					imageData.put("tiny_image", dataUri);
					imageData.put("small_image", dataUri);
					imageRequestBody.put("request", imageData);

					String imgResp = cafe24RestClient.post("/admin/products/" + marketItemId + "/images",
						imageRequestBody);
					log.info("[D092][카페24] 이미지 POST resp (len={}): {}", imgResp == null ? -1 : imgResp.length(),
						imgResp == null ? "null" : imgResp.substring(0, Math.min(imgResp.length(), 2000)));
					log.info("[카페24] 이미지 업로드 완료: {}", marketItemId);
				}
			} catch (Exception e) {
				log.error("[카페24] 이미지 업데이트 실패 (ID: {}): {}", marketItemId, e.getMessage());
			}
		}

		if (currentRawData != null) {
			if (hostedImages != null && !hostedImages.isEmpty()) {
				currentRawData.put("detail_image", hostedImages.get(0));
			}
			currentRawData.put("description", newDetailHtml);
		}
		return currentRawData;
	}
}
