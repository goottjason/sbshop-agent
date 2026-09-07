package com.sbshop.agent.infrastructure.client.cafe24.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.sourcing.dto.MarketCategory;
import com.sbshop.agent.core.domain.market.client.MarketClient;
import com.sbshop.agent.core.domain.market.client.dto.MarketCatalogEntry;
import com.sbshop.agent.core.domain.market.client.dto.MarketEditField;
import com.sbshop.agent.core.domain.market.client.dto.MarketItemInfo;
import com.sbshop.agent.core.domain.market.client.dto.MarketPublishContext;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.enums.ProductCategory;
import com.sbshop.agent.infrastructure.client.cafe24.client.Cafe24RestClient;
import com.sbshop.agent.infrastructure.client.cafe24.component.Cafe24BrandCodeResolver;
import com.sbshop.agent.infrastructure.client.cafe24.component.Cafe24CategoryResolver;
import com.sbshop.agent.infrastructure.client.cafe24.component.Cafe24OriginResolver;
import com.sbshop.agent.infrastructure.client.common.util.HtmlImageExtractor;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class Cafe24MarketClient implements MarketClient {
	@org.springframework.beans.factory.annotation.Autowired
	private Cafe24ReviewedPublication reviewedPublication;

	@Override
	public com.sbshop.agent.core.domain.market.client.dto.PreparedMarketPublication preparePublication(Product product,
		BigDecimal price) {
		try {
			return reviewedPublication.prepare(product, price);
		} catch (Exception e) {
			throw publicationFailure(e);
		}
	}

	@Override
	public Map<String, String> submitPreparedPublication(Product product, String operationId, String payload,
		Runnable beforeWrite) {
		return reviewedPublication.submit(product, operationId, payload, beforeWrite);
	}

	@Override
	public com.sbshop.agent.core.domain.market.client.dto.VerifiedMarketPublication readPreparedPublication(String id,
		String sbCode, String payload) {
		try {
			return reviewedPublication.readPublication(id, sbCode, payload);
		} catch (Exception e) {
			throw publicationFailure(e);
		}
	}

	@Override
	public void finalizePreparedPublication(String id, String sbCode, String payload, Runnable beforeWrite) {
		var abort = new java.util.concurrent.atomic.AtomicReference<RuntimeException>();
		try {
			reviewedPublication.finalizePublication(id, sbCode, payload, () -> {
				try {
					beforeWrite.run();
				} catch (RuntimeException e) {
					abort.set(e);
					throw e;
				}
			});
		} catch (Exception e) {
			if (e == abort.get())
				throw abort.get();
			throw publicationFailure(e);
		}
	}

	private RuntimeException publicationFailure(Exception e) {
		return e instanceof com.sbshop.agent.core.domain.market.sync.MarketTransferFailure failure ? failure
			: com.sbshop.agent.infrastructure.client.common.MarketApiEvidence.transferFailure(e);
	}

	private Cafe24ReviewedFields reviewedFields() {
		return new Cafe24ReviewedFields(cafe24RestClient, objectMapper);
	}

	@Override
	public com.sbshop.agent.core.domain.market.client.dto.PreparedMarketFields prepareProductFields(Product product,
		String listingId, String optionId, Set<String> fields) {
		try {
			return reviewedFields().prepare(product, listingId, optionId, fields);
		} catch (Exception e) {
			if (e instanceof UnsupportedOperationException unsupported)
				throw unsupported;
			if (e instanceof IllegalArgumentException invalid)
				throw invalid;
			throw com.sbshop.agent.infrastructure.client.common.MarketApiEvidence.transferFailure(e);
		}
	}

	@Override
	public com.sbshop.agent.core.domain.market.client.dto.MarketFieldsRead readProductFields(String listingId,
		String optionId, String expectedSbCode, Set<String> fields) {
		try {
			return reviewedFields().read(listingId, optionId, expectedSbCode, fields);
		} catch (Exception e) {
			if (e instanceof UnsupportedOperationException unsupported)
				throw unsupported;
			if (e instanceof IllegalArgumentException invalid)
				throw invalid;
			throw com.sbshop.agent.infrastructure.client.common.MarketApiEvidence.transferFailure(e);
		}
	}

	@Override
	public void writePreparedProductFields(String listingId, String optionId, String expectedSbCode,
		com.sbshop.agent.core.domain.market.client.dto.PreparedMarketFields prepared, Runnable beforeWrite) {
		var guardFailure = new java.util.concurrent.atomic.AtomicReference<RuntimeException>();
		try {
			reviewedFields().write(listingId, optionId, expectedSbCode, prepared, () -> {
				try {
					beforeWrite.run();
				} catch (RuntimeException abort) {
					guardFailure.set(abort);
					throw abort;
				}
			});
		} catch (Exception e) {
			if (e == guardFailure.get())
				throw guardFailure.get();
			if (e instanceof UnsupportedOperationException unsupported)
				throw unsupported;
			if (e instanceof IllegalArgumentException invalid)
				throw invalid;
			throw com.sbshop.agent.infrastructure.client.common.MarketApiEvidence.transferFailure(e);
		}
	}

	@Override
	public com.sbshop.agent.core.domain.market.client.dto.MarketPriceRead readSalePrice(String id, String optionId) {
		requirePriceId(id);
		String account = inspectionAccountReference();
		try {
			JsonNode root = objectMapper.readTree(cafe24RestClient.get("/admin/products/" + id + "?shop_no=1"));
			JsonNode p = root.path("product");
			if (root.has("error") || !id.equals(p.path("product_no").asText())
				|| !"1".equals(p.path("shop_no").asText()))
				throw new IllegalStateException("카페24 상품·쇼핑몰 식별자를 확인할 수 없습니다.");
			java.math.BigDecimal price;
			try {
				price = new java.math.BigDecimal(p.path("price").asText());
			} catch (NumberFormatException e) {
				throw new IllegalStateException("조회 응답에 유효한 판매가가 없습니다.");
			}
			if (price.signum() <= 0)
				throw new IllegalStateException("조회 응답에 유효한 판매가가 없습니다.");
			String blocked = !"T".equals(p.path("selling").asText()) || !"F".equals(p.path("market_sync").asText())
				? "판매 설정 또는 마켓플러스 자동 전달 범위의 확인이 필요합니다." : priceFieldBlock(p);
			if (account == null || !account.equals(inspectionAccountReference()))
				throw new IllegalStateException("조회 계정이 변경되었습니다.");
			return new com.sbshop.agent.core.domain.market.client.dto.MarketPriceRead(price, blocked == null,
				blocked == null ? "카페24 본상품 가격·세금 계산 설정 확인" : blocked, account);
		} catch (Exception e) {
			throw com.sbshop.agent.infrastructure.client.common.MarketApiEvidence.transferFailure(e);
		}
	}

	/** Official product update contract: manual tax + basis B requires price_excluding_tax. */
	private String priceFieldBlock(JsonNode product) throws com.fasterxml.jackson.core.JsonProcessingException {
		String calculation = product.path("tax_calculation").asText();
		if ("A".equals(calculation))
			return null;
		if (!"M".equals(calculation))
			return "카페24 세금 계산 유형을 확인할 수 없어 가격 전송을 보류합니다.";
		JsonNode response = objectMapper.readTree(cafe24RestClient.get("/admin/products/setting?shop_no=1"));
		JsonNode settings = response.path("product");
		if (response.has("error") || !"1".equals(settings.path("shop_no").asText()))
			return "카페24 상품 설정의 쇼핑몰 번호·응답 확인이 필요합니다.";
		return switch (settings.path("calculate_price_based_on").asText()) {
			case "S", "A", "P" -> null;
			case "B" -> "카페24 수동 세금·상품가 기준(B)은 세금 제외 가격(price_excluding_tax) 전송이 필요합니다. "
				+ "세금 포함 목표 판매가를 임의로 변환하지 않고 보류합니다.";
			default -> "카페24 판매가 계산 기준을 확인할 수 없어 가격 전송을 보류합니다.";
		};
	}

	@Override
	public void writeSalePrice(String id, String optionId, java.math.BigDecimal price) {
		try {
			requirePriceId(id);
			if (price == null || price.signum() <= 0)
				throw new IllegalArgumentException("카페24 판매가는 양의 정수여야 합니다.");
			cafe24RestClient.put("/admin/products/" + id,
				Map.of("shop_no", 1, "request", Map.of("price", price.intValueExact())));
		} catch (Exception e) {
			throw com.sbshop.agent.infrastructure.client.common.MarketApiEvidence.transferFailure(e);
		}
	}

	@Override
	public com.sbshop.agent.core.domain.market.client.dto.MarketStockRead readStockQuantity(String id,
		String optionId, String expectedSbCode) {
		String account = inspectionAccountReference();
		try {
			var access = new Cafe24VerifiedProductAccess(objectMapper, cafe24RestClient, id);
			JsonNode product = access.product();
			if (expectedSbCode == null || expectedSbCode.isBlank()
				|| !expectedSbCode.equals(product.path("custom_product_code").asText()))
				throw new IllegalStateException("카페24 상품의 SB코드가 일치하지 않습니다.");
			access.requireNativeWrite(product);
			String code = access.singleVariant(product);
			if (optionId != null && !optionId.equals(code))
				throw new IllegalStateException("카페24 품목 연결이 변경되었습니다. 다시 검토하세요.");
			JsonNode variant = access.variant(code), inventory = access.inventory(code);
			int quantity = access.quantity(inventory);
			if (quantity < 0 || !List.of("T", "F").contains(variant.path("selling").asText()))
				throw new IllegalStateException("카페24 유효한 수량·품목 판매 상태를 확인할 수 없습니다.");
			if (account == null || !account.equals(inspectionAccountReference()))
				throw new IllegalStateException("카페24 조회 계정이 변경되었습니다.");
			boolean writable = "T".equals(product.path("selling").asText())
				&& "T".equals(variant.path("selling").asText())
				&& "T".equals(inventory.path("use_inventory").asText())
				&& "T".equals(inventory.path("display_soldout").asText());
			return new com.sbshop.agent.core.domain.market.client.dto.MarketStockRead(quantity, writable,
				writable ? "카페24 본상품 단일 품목 재고수량 확인" : "카페24 판매·재고 관리·품절 표시 설정을 확인하세요. 판매 재개나 설정 변경은 자동 수행하지 않습니다.",
				account, code);
		} catch (UnsupportedOperationException blocked) {
			throw blocked;
		} catch (Exception e) {
			throw com.sbshop.agent.infrastructure.client.common.MarketApiEvidence.transferFailure(e);
		}
	}

	@Override
	public void writeStockQuantity(String id, String optionId, String expectedSbCode, int quantity,
		String expectedAccountReference, Runnable beforeWrite) {
		if (quantity < 0 || optionId == null || !optionId.matches("P[A-Z0-9]{11}"))
			throw new IllegalArgumentException("카페24 정확한 품목과 0 이상의 판매용 수량이 필요합니다.");
		if (expectedAccountReference == null || !expectedAccountReference.equals(inspectionAccountReference()))
			throw new UnsupportedOperationException("카페24 연동 계정이 변경되었습니다.");
		var current = readStockQuantity(id, optionId, expectedSbCode);
		if (!current.writable() || !expectedAccountReference.equals(current.accountReference())
			|| !expectedAccountReference.equals(inspectionAccountReference()))
			throw new UnsupportedOperationException("카페24 계정·판매·재고 설정이 변경되어 수량 전송을 보류합니다.");
		var access = new Cafe24VerifiedProductAccess(objectMapper, cafe24RestClient, id);
		beforeWrite.run();
		try {
			access.put(access.path() + "/variants/" + optionId + "/inventories", Map.of("quantity", quantity));
			if (!expectedAccountReference.equals(inspectionAccountReference()))
				throw new IllegalStateException("수량 전송 중 카페24 계정이 변경되었습니다. 결과 재확인이 필요합니다.");
		} catch (Exception e) {
			throw com.sbshop.agent.infrastructure.client.common.MarketApiEvidence.transferFailure(e);
		}
	}

	private static void requirePriceId(String id) {
		if (id == null || !id.matches("[1-9][0-9]{0,17}"))
			throw new IllegalArgumentException("카페24 상품 번호가 올바르지 않습니다.");
	}

	private final ObjectMapper objectMapper;
	private final Cafe24RestClient cafe24RestClient;
	private final HtmlImageExtractor imageExtractor;
	private final Cafe24CategoryResolver categoryResolver;
	private final Cafe24BrandCodeResolver brandCodeResolver;
	private final Cafe24OriginResolver originResolver;

	private static final int CATALOG_LIMIT = 100;
	private static final int CATALOG_OFFSET_CAP = 5000;
	private static final int CATALOG_MAX_PAGES = 1000;
	private static final int CATALOG_RATE_LIMIT_RETRIES = 3;
	private static final long CATALOG_BACKOFF_MS = 1000L;
	private static final BigDecimal MAX_PRODUCT_WEIGHT_KG = new BigDecimal("999999.99");
	private static final int MAX_PRODUCT_TAGS = 100;
	private static final String CATALOG_FIELDS = "product_no,product_code,custom_product_code,display,selling";

	@Override
	public MarketType getSupportedMarket() {
		return MarketType.CAFE24;
	}

	@Override
	public Map<String, String> publish(Product product) {
		return publish(product, MarketPublishContext.empty());
	}

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
			BigDecimal costPrice = product.getCostPrice();
			int supplyPrice = costPrice != null && costPrice.signum() > 0
				? costPrice.setScale(0, RoundingMode.FLOOR).intValue()
				: salePrice;
			productData.put("supply_price", String.valueOf(supplyPrice));
			productData.put("supply_quantity", product.getStock() != null
				? String.valueOf(product.getStock()) : "0");
			productData.put("display", "T");
			productData.put("selling", "T");
			productData.put("product_condition", "N");
			String brandCode = resolveBrandCodeQuietly(product);
			if (brandCode != null)
				productData.put("brand_code", brandCode);
			String productWeight = formatWeight(product);
			if (productWeight != null)
				productData.put("product_weight", productWeight);
			if (product.getDetailHtml() != null)
				productData.put("description", product.getDetailHtml());

			String origin = context.extraString("originPlace");
			if (origin != null && !origin.isBlank()) {
				Cafe24OriginResolver.Origin resolved = originResolver.resolve(origin);
				productData.put("origin_classification", resolved.classification());
				productData.put("origin_place_no", resolved.placeNo());
				if (resolved.placeValue() != null)
					productData.put("origin_place_value", resolved.placeValue());
			}

			String categoryNo = context.hasCategory() ? context.categoryId() : resolveCategoryOrThrow(product);
			Map<String, Object> category = new HashMap<>();
			category.put("category_no", parseCategoryNo(categoryNo));
			category.put("recommend", "F");
			category.put("new", "T");
			productData.put("add_category_no", List.of(category));

			List<String> hostedImages = product.getHostedImages();
			if (!hostedImages.isEmpty()) {
				productData.put("use_external_image", "T");
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

			registerTagsQuietly(productNo, product.getSearchKeywords());

			if (!hostedImages.isEmpty()) {
				try {
					uploadMainImage(productNo, hostedImages.get(0));
				} catch (RuntimeException e) {
					throw new RuntimeException(
						"[카페24] 상품 등록은 성공했으나 이미지 업로드에 실패했습니다 — 마켓에 이미지 없는 상품이 남았으니"
							+ " 재시도 전에 정리하세요(product_no=" + productNo + ", product_code=" + productCode
							+ "): " + e.getMessage(),
						e);
				}
			}
			return identifiers;
		} catch (RuntimeException e) {
			log.error("[카페24] 상품 등록 실패: {}", e.getMessage());
			throw e;
		} catch (Exception e) {
			log.error("[카페24] 상품 등록 실패: {}", e.getMessage());
			throw new RuntimeException("카페24 상품 등록 오류", e);
		}
	}

	private String resolveBrandCodeQuietly(Product product) {
		String brand = product.getBrand();
		if (brand == null || brand.isBlank()) {
			return null;
		}
		try {
			String code = brandCodeResolver.resolve(brand);
			return code == null || code.isBlank() ? null : code;
		} catch (RuntimeException e) {
			log.warn("[카페24] 브랜드 코드 해석 실패 — 브랜드 없이 등록한다: sbCode={} brand={} 사유={}",
				product.getSbCode(), brand, e.getMessage());
			return null;
		}
	}

	private String formatWeight(Product product) {
		BigDecimal weight = product.getLogisticsInfo() == null ? null : product.getLogisticsInfo().getWeight();
		if (weight == null || weight.signum() <= 0 || weight.compareTo(MAX_PRODUCT_WEIGHT_KG) > 0) {
			return null;
		}
		return weight.setScale(2, RoundingMode.HALF_UP).toPlainString();
	}

	private void registerTagsQuietly(String productNo, String searchKeywords) {
		if (searchKeywords == null || searchKeywords.isBlank()) {
			return;
		}
		List<String> tags = Arrays.stream(searchKeywords.split(","))
			.map(String::trim)
			.filter(tag -> !tag.isEmpty())
			.distinct()
			.limit(MAX_PRODUCT_TAGS)
			.toList();
		if (tags.isEmpty()) {
			return;
		}
		Map<String, Object> request = new HashMap<>();
		request.put("shop_no", 1);
		request.put("tags", tags);
		try {
			cafe24RestClient.post("/admin/products/" + productNo + "/tags",
				Map.of("request", request));
			log.info("[카페24] 상품 태그 등록 완료: product_no={} tags={}", productNo, tags);
		} catch (RuntimeException e) {
			log.warn("[카페24] 상품 태그 등록 실패 — 상품 등록은 유지한다: product_no={} 사유={}",
				productNo, e.getMessage());
		}
	}

	@Override
	public boolean syncBarcode(Product product, String marketItemId, Map<String, Object> currentRawData) {
		String barcode = product.getProductSpec() == null ? null : product.getProductSpec().getBarcode();
		if (barcode == null || barcode.isBlank())
			return false;
		if (!barcode.matches("[0-9]{1,14}"))
			throw new IllegalArgumentException("카페24 GTIN은 최대 14자리 숫자 문자열이어야 합니다.");
		try {
			var access = new Cafe24VerifiedProductAccess(objectMapper, cafe24RestClient, marketItemId);
			JsonNode p = access.product();
			if (product.getSbCode() == null || !product.getSbCode().equals(p.path("custom_product_code").asText()))
				throw new IllegalStateException("카페24 상품의 SB코드가 시스템상품과 일치하지 않습니다.");
			access.requireNativeWrite(p);
			if (!"T".equals(p.path("has_option").asText()))
				throw new UnsupportedOperationException("카페24 무옵션 단일 상품의 GTIN 수정은 최신 버전 쓰기 검증이 필요해 보류합니다.");
			if (!"T".equals(p.path("selling").asText()))
				throw new UnsupportedOperationException("카페24 판매 중지 상품의 바코드 전송을 보류합니다.");
			String code = access.singleVariant(p);
			JsonNode before = access.variant(code);
			if (!"T".equals(before.path("selling").asText()))
				throw new UnsupportedOperationException("카페24 판매 중지 품목의 바코드 전송을 보류합니다.");
			boolean written = !barcode.equals(before.path("gtin").asText());
			if (written)
				access.put(access.path() + "/variants/" + code, Map.of("gtin", barcode));
			JsonNode verified = written ? access.variant(code) : before;
			if (!barcode.equals(verified.path("gtin").asText()))
				throw new IllegalStateException("카페24 GTIN 반영을 재조회로 확인하지 못했습니다. 성공으로 처리하지 않으며 재시도 시 먼저 조회합니다.");
			Map<String, Object> snapshot = access.snapshot(currentRawData, null, verified, null, List.of("gtin"));
			if (currentRawData != null)
				currentRawData.putAll(snapshot);
			return written;
		} catch (UnsupportedOperationException e) {
			throw e;
		} catch (Exception e) {
			throw com.sbshop.agent.infrastructure.client.common.MarketApiEvidence.transferFailure(e);
		}
	}

	@Override
	public String inspectionAccountReference() {
		return cafe24RestClient.accountReference();
	}

	@Override
	public com.sbshop.agent.core.domain.market.client.dto.MarketListingObservation inspectListing(String id) {
		String account = inspectionAccountReference();
		String path = "/admin/products/" + id + "?shop_no=1";
		if (id == null || !id.matches("[0-9]+") || account == null)
			return com.sbshop.agent.core.domain.market.client.dto.MarketListingObservation
				.unknown("조회 상품번호 또는 계정 확인 필요");
		try {
			JsonNode root = objectMapper.readTree(cafe24RestClient.get(path));
			JsonNode product = root.path("product");
			if (root.has("error") || !product.isObject() || !id.equals(product.path("product_no").asText())
				|| !"1".equals(product.path("shop_no").asText()) || !account.equals(inspectionAccountReference()))
				throw new IllegalStateException("상품번호·쇼핑몰·응답 확인 실패");
			String code = product.path("selling").asText("");
			var state = switch (code) {
				case "T" -> com.sbshop.agent.core.domain.market.client.dto.MarketListingObservation.State.PRESENT;
				case "F" -> com.sbshop.agent.core.domain.market.client.dto.MarketListingObservation.State.STOPPED;
				default -> com.sbshop.agent.core.domain.market.client.dto.MarketListingObservation.State.UNKNOWN;
			};
			return new com.sbshop.agent.core.domain.market.client.dto.MarketListingObservation(state, "SELLING_" + code,
				"카페24 본상품 판매 설정: " + code + " · G마켓·옥션 상태는 별도 확인이 필요합니다.", account, "GET " + path,
				java.time.Instant.now());
		} catch (Exception e) {
			return com.sbshop.agent.infrastructure.client.common.MarketApiEvidence.failure(e, account, "GET " + path);
		}
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
		return syncVerifiedPriceStock(marketItemId, currentRawData, price, quantity, soldOut, null);
	}

	@Override
	public Map<String, Object> syncPriceAndStock(String marketItemId, Map<String, Object> currentRawData,
		Integer price, int quantity, boolean soldOut, Product product) {
		if (product == null || product.getSbCode() == null || product.getSbCode().isBlank())
			throw new IllegalArgumentException("카페24 재고 반영 대상 시스템상품의 SB코드가 필요합니다.");
		return syncVerifiedPriceStock(marketItemId, currentRawData, price, quantity, soldOut, product.getSbCode());
	}

	private Map<String, Object> syncVerifiedPriceStock(String marketItemId, Map<String, Object> currentRawData,
		Integer price, int quantity, boolean soldOut, String expectedSbCode) {
		if (quantity < 0 || (price != null && price <= 0))
			throw new IllegalArgumentException("카페24 수량은 음수가 아닌 정수, 판매가는 양의 정수여야 합니다.");
		try {
			var access = new Cafe24VerifiedProductAccess(objectMapper, cafe24RestClient, marketItemId);
			JsonNode p = access.product();
			if (expectedSbCode != null && !expectedSbCode.equals(p.path("custom_product_code").asText()))
				throw new IllegalStateException("카페24 재고 반영 대상의 SB코드가 시스템상품과 일치하지 않습니다.");
			access.requireNativeWrite(p);
			if (!soldOut && !"T".equals(p.path("selling").asText()))
				throw new UnsupportedOperationException("카페24 판매 중지 원인이 확인되지 않아 자동 판매 재개를 보류합니다.");
			String priceBlock = price == null ? null : priceFieldBlock(p);
			if (priceBlock != null)
				throw new UnsupportedOperationException(priceBlock);
			String code = access.singleVariant(p);
			JsonNode variant = access.variant(code);
			JsonNode inventory = access.inventory(code);
			if (!soldOut && (!"T".equals(variant.path("selling").asText())
				|| !"T".equals(inventory.path("use_inventory").asText())
				|| !"T".equals(inventory.path("display_soldout").asText())))
				throw new UnsupportedOperationException("카페24 품목 판매·재고 관리·품절표시 설정 확인이 필요합니다. 설정을 임의로 켜지 않습니다.");
			Map<String, Object> fields = new LinkedHashMap<>();
			String selling = soldOut ? "F" : "T";
			if (!selling.equals(p.path("selling").asText()))
				fields.put("selling", selling);
			if (price != null) {
				BigDecimal before;
				try {
					before = new BigDecimal(p.path("price").asText());
				} catch (NumberFormatException e) {
					throw new IllegalStateException("카페24 현재 판매가를 확인하지 못했습니다.");
				}
				if (before.signum() <= 0)
					throw new IllegalStateException("카페24 현재 판매가를 확인하지 못했습니다.");
				if (before.compareTo(BigDecimal.valueOf(price)) != 0)
					fields.put("price", price);
			}
			// A source stockout stops sales while preserving the configured quantity for recovery.
			if (!soldOut && access.quantity(inventory) != quantity) {
				access.put(access.path() + "/variants/" + code + "/inventories", Map.of("quantity", quantity));
				inventory = access.inventory(code);
				requireInventoryMatch(access, inventory, quantity);
			}
			if (!fields.isEmpty())
				access.put(access.path(), fields);
			JsonNode verified = access.product();
			access.requireNativeWrite(verified);
			if (!p.path("product_code").equals(verified.path("product_code"))
				|| !p.path("custom_product_code").equals(verified.path("custom_product_code")))
				throw new IllegalStateException("카페24 재조회 중 상품코드·SB코드가 변경되어 결과를 확정하지 않습니다.");
			if (!selling.equals(verified.path("selling").asText())
				|| (price != null && !priceEquals(verified, price)))
				throw new IllegalStateException("카페24 판매가·판매 상태 반영을 재조회로 확인하지 못했습니다. 일부 필드 반영 가능성이 있어 재조회 후 재시도하세요.");
			inventory = access.inventory(code);
			if (!soldOut)
				requireInventoryMatch(access, inventory, quantity);
			List<String> confirmed = new ArrayList<>(List.of("selling"));
			if (!soldOut)
				confirmed.add("quantity");
			if (price != null)
				confirmed.add("price");
			return access.snapshot(currentRawData, verified, variant, inventory, confirmed);
		} catch (UnsupportedOperationException e) {
			throw e;
		} catch (Exception e) {
			throw com.sbshop.agent.infrastructure.client.common.MarketApiEvidence.transferFailure(e);
		}
	}

	private static boolean priceEquals(JsonNode product, int expected) {
		try {
			return new BigDecimal(product.path("price").asText()).compareTo(BigDecimal.valueOf(expected)) == 0;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	private static void requireInventoryMatch(Cafe24VerifiedProductAccess access, JsonNode inventory, int quantity) {
		if (access.quantity(inventory) != quantity || !"T".equals(inventory.path("use_inventory").asText())
			|| !"T".equals(inventory.path("display_soldout").asText()))
			throw new IllegalStateException("카페24 재고수량·설정 반영을 재조회로 확인하지 못했습니다. 일부 반영 가능성이 있어 재조회 후 재시도하세요.");
	}

	@Override
	public Map<String, Object> syncProductFields(Product product, String marketItemId,
		Map<String, Object> currentRawData, Set<MarketEditField> fields) {
		Set<MarketEditField> supported = new HashSet<>(fields);
		supported.remove(MarketEditField.MANUFACTURER);
		if (supported.isEmpty()) {
			throw new UnsupportedOperationException("[카페24] 필드 수정 미지원: " + fields);
		}

		Map<String, Object> productData = new HashMap<>();
		productData.put("shop_no", 1);
		if (supported.contains(MarketEditField.PRODUCT_NAME)) {
			String name = product.getProductName();
			if (name != null && !name.isBlank()) {
				productData.put("product_name", name);
			}
		}
		if (supported.contains(MarketEditField.BRAND)) {
			String brand = product.getBrand();
			if (brand != null && !brand.isBlank()) {
				productData.put("brand_code", brandCodeResolver.resolve(brand));
			}
		}

		Map<String, Object> requestBody = new HashMap<>();
		requestBody.put("request", productData);
		cafe24RestClient.put("/admin/products/" + marketItemId, requestBody);
		log.info("[카페24] 필드 동기화 완료: {}, fields={}", marketItemId, supported);

		if (currentRawData != null) {
			productData.forEach((key, value) -> {
				if (!"shop_no".equals(key)) {
					currentRawData.put(key, value);
				}
			});
		}
		return currentRawData;
	}

	@Override
	public Map<String, Object> syncImagesAndHtml(Product product,
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
			throw new RuntimeException(
				"[카페24] 상세설명 업데이트 실패 (ID: " + marketItemId + "): " + e.getMessage(), e);
		}

		if (hostedImages != null && !hostedImages.isEmpty()) {
			try {
				cafe24RestClient.delete("/admin/products/" + marketItemId + "/images");
			} catch (Exception e) {
				log.warn("[카페24] 기존 이미지 삭제 중 경고: {}", e.getMessage());
			}

			uploadMainImage(marketItemId, hostedImages.get(0));
		}

		if (currentRawData != null) {
			if (hostedImages != null && !hostedImages.isEmpty()) {
				currentRawData.put("detail_image", hostedImages.get(0));
			}
			currentRawData.put("description", newDetailHtml);
		}
		return currentRawData;
	}

	@Override
	public List<MarketCatalogEntry> fetchCatalog(long throttleMs) {
		List<MarketCatalogEntry> entries = new ArrayList<>();
		int offset = 0;
		long cursor = 0L;
		long maxProductNo = 0L;
		boolean reachedLastPage = false;
		for (int page = 0; page < CATALOG_MAX_PAGES; page++) {
			String path = cursor > 0L
				? "/admin/products?limit=" + CATALOG_LIMIT + "&since_product_no=" + cursor
					+ "&fields=" + CATALOG_FIELDS
				: "/admin/products?limit=" + CATALOG_LIMIT + "&offset=" + offset
					+ "&fields=" + CATALOG_FIELDS;
			JsonNode products = fetchCatalogPage(path, entries.size());
			int count = 0;
			for (JsonNode product : products) {
				MarketCatalogEntry entry = toCatalogEntry(product);
				if (entry != null) {
					entries.add(entry);
				}
				maxProductNo = Math.max(maxProductNo, product.path("product_no").asLong(0L));
				count++;
			}
			log.info("[카페24] 카탈로그 스캔 누적 {}건 (이번 페이지 {}건)", entries.size(), count);
			if (count < CATALOG_LIMIT) {
				reachedLastPage = true;
				break;
			}
			if (cursor > 0L) {
				cursor = maxProductNo;
			} else {
				offset += CATALOG_LIMIT;
				if (offset >= CATALOG_OFFSET_CAP) {
					cursor = maxProductNo;
				}
			}
			sleepQuietly(throttleMs);
		}
		if (!reachedLastPage) {
			throw new RuntimeException("[카페24] 전체 상품 조회 실패 — 페이지 상한(" + CATALOG_MAX_PAGES
				+ ")을 소진했는데 마지막 페이지에 닿지 못했다 (누적 " + entries.size()
				+ "건). 잘린 카탈로그는 대조에서 '마켓에 없는 상품'으로 오독되므로 반환하지 않는다.");
		}
		return entries;
	}

	private JsonNode fetchCatalogPage(String path, int collectedSoFar) {
		RuntimeException lastRateLimit = null;
		for (int attempt = 1; attempt <= CATALOG_RATE_LIMIT_RETRIES; attempt++) {
			try {
				String response = cafe24RestClient.get(path);
				return objectMapper.readTree(response).path("products");
			} catch (RuntimeException e) {
				if (!isRateLimited(e)) {
					throw new RuntimeException("[카페24] 전체 상품 조회 실패 (누적 " + collectedSoFar + "건, path="
						+ path + "): " + e.getMessage(), e);
				}
				lastRateLimit = e;
				log.warn("[카페24] 호출 한도 초과 — {}ms 후 재시도 {}/{}", CATALOG_BACKOFF_MS * attempt,
					attempt, CATALOG_RATE_LIMIT_RETRIES);
				sleepQuietly(CATALOG_BACKOFF_MS * attempt);
			} catch (Exception e) {
				throw new RuntimeException("[카페24] 전체 상품 조회 실패 — 응답 파싱 불가 (path=" + path + "): "
					+ e.getMessage(), e);
			}
		}
		throw new RuntimeException("[카페24] 전체 상품 조회 실패 — 호출 한도 초과가 "
			+ CATALOG_RATE_LIMIT_RETRIES + "회 재시도 후에도 계속됨 (누적 " + collectedSoFar + "건)", lastRateLimit);
	}

	private MarketCatalogEntry toCatalogEntry(JsonNode product) {
		String productNo = text(product, "product_no");
		if (productNo.isEmpty()) {
			return null;
		}
		Map<String, String> identifiers = new HashMap<>();
		identifiers.put("product_no", productNo);
		String productCode = text(product, "product_code");
		if (!productCode.isEmpty()) {
			identifiers.put("product_code", productCode);
		}
		String display = text(product, "display");
		String selling = text(product, "selling");
		String status = display.isEmpty() && selling.isEmpty()
			? null
			: "display=" + display + ",selling=" + selling;
		return new MarketCatalogEntry(blankToNull(text(product, "custom_product_code")), identifiers, status);
	}

	private static String text(JsonNode node, String field) {
		String value = node.path(field).asText("");
		return value == null ? "" : value.trim();
	}

	private static boolean isRateLimited(RuntimeException e) {
		for (Throwable t = e; t != null; t = t.getCause()) {
			String message = t.getMessage();
			if (message != null && (message.contains("429") || message.contains("Too many"))) {
				return true;
			}
			if (t.getCause() == t) {
				break;
			}
		}
		return false;
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}

	private void sleepQuietly(long millis) {
		if (millis <= 0) {
			return;
		}
		try {
			Thread.sleep(millis);
		} catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
			throw new RuntimeException("[카페24] 전체 상품 조회 실패 — 중단됨", ie);
		}
	}

	@Override
	public void deleteFromMarket(String marketItemId) {
		log.info("[카페24] 상품 삭제 시작: product_no={}", marketItemId);
		cafe24RestClient.delete("/admin/products/" + marketItemId);
		log.info("[카페24] 상품 삭제 성공: product_no={}", marketItemId);
	}

	private void uploadMainImage(String marketItemId, String mainImageUrl) {
		byte[] imageBytes = cafe24RestClient.getExternalImageBytes(mainImageUrl);
		if (imageBytes == null) {
			log.error("[카페24] 외부 이미지 다운로드 실패 (ID: {}): {}", marketItemId, mainImageUrl);
			throw new RuntimeException(
				"[카페24] 외부 이미지 다운로드 실패 (ID: " + marketItemId + "): " + mainImageUrl);
		}

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

		try {
			String imgResp = cafe24RestClient.post("/admin/products/" + marketItemId + "/images",
				imageRequestBody);
			log.info("[D092][카페24] 이미지 POST resp (len={}): {}", imgResp == null ? -1 : imgResp.length(),
				imgResp == null ? "null" : imgResp.substring(0, Math.min(imgResp.length(), 2000)));
			log.info("[카페24] 이미지 업로드 완료: {}", marketItemId);
		} catch (Exception e) {
			log.error("[카페24] 이미지 업데이트 실패 (ID: {}): {}", marketItemId, e.getMessage());
			throw new RuntimeException(
				"[카페24] 이미지 업로드 실패 (ID: " + marketItemId + "): " + e.getMessage(), e);
		}
	}

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

	private Integer parseCategoryNo(String raw) {
		try {
			return Integer.parseInt(raw.trim());
		} catch (NumberFormatException e) {
			throw new IllegalStateException("카페24 분류번호가 숫자가 아닙니다: " + raw);
		}
	}
}
