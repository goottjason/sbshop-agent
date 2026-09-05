package com.sbshop.agent.api.controller;

import static com.sbshop.agent.api.validation.NonNegativeValidator.requireNonNegative;

import com.sbshop.agent.api.dto.product.ImageUploadResponse;
import com.sbshop.agent.api.dto.product.MarketBadgeState;
import com.sbshop.agent.api.dto.product.PriceStockUpdateRequest;
import com.sbshop.agent.api.dto.product.ProductDetailResponse;
import com.sbshop.agent.api.dto.product.ProductListResponse;
import com.sbshop.agent.api.dto.product.ProductUpdateRequest;
import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.product.MarketRepublishResult;
import com.sbshop.agent.core.application.product.ProductDeleteResult;
import com.sbshop.agent.core.application.product.ProductManageUseCase;
import com.sbshop.agent.core.application.product.ProductSearchUseCase;
import com.sbshop.agent.core.application.product.port.ProductInfoCrawlerPort;
import com.sbshop.agent.core.application.sourcing.dto.ScrapedProductDto;
import com.sbshop.agent.core.domain.actionlog.ActionLogConstants;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.client.ImageDownloadClient;
import com.sbshop.agent.core.domain.product.client.dto.ImageProcessResult;
import com.sbshop.agent.core.domain.product.client.dto.ImageUploadFile;
import com.sbshop.agent.core.domain.product.dto.ProductSearchCondition;
import com.sbshop.agent.core.domain.product.enums.ProductCategory;
import com.sbshop.agent.core.domain.product.enums.SourceGoneFilter;
import com.sbshop.agent.core.domain.product.enums.StockStatus;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Slf4j
@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ProductController {
	static final int MAX_CRAWL_IMAGES = 30;
	private final ProductSearchUseCase productSearchUseCase;
	private final ProductManageUseCase productManageUseCase;
	private final ImageDownloadClient imageDownloadClient;
	private final ProductInfoCrawlerPort productInfoCrawlerPort;
	private final MarketRegistrationRepository marketRegistrationRepository;
	private final ActionLogService actionLogService;

	@GetMapping
	public ResponseEntity<Page<ProductListResponse>> getProducts(
		@RequestParam(required = false)
		String keyword,
		@RequestParam(required = false)
		String marketFilter,
		@RequestParam(required = false)
		List<ProductCategory> categories,
		@RequestParam(required = false)
		List<VendorType> vendors,
		@RequestParam(required = false)
		List<StockStatus> stockStatuses,
		@RequestParam(required = false)
		List<MarketType> markets,
		@RequestParam(defaultValue = "false")
		boolean inStockOnly,
		@RequestParam(defaultValue = "false")
		boolean includeUncategorized,
		@RequestParam(required = false)
		SourceGoneFilter sourceGone,
		@PageableDefault(size = 50)
		Pageable pageable) {
		ProductSearchCondition condition = buildSearchCondition(keyword, marketFilter, categories,
			vendors, stockStatuses, markets, inStockOnly, includeUncategorized, sourceGone);
		return searchResponse(condition, pageable);
	}

	/** 많은 SB코드와 쉼표를 포함한 브랜드명을 URL 길이/파라미터 분할 문제 없이 검색한다. */
	@PostMapping("/search")
	public ResponseEntity<Page<ProductListResponse>> searchProducts(
		@RequestBody
		ProductSearchCondition condition,
		@PageableDefault(size = 50)
		Pageable pageable) {
		return searchResponse(condition, pageable);
	}

	private ResponseEntity<Page<ProductListResponse>> searchResponse(ProductSearchCondition condition,
		Pageable pageable) {
		Page<Product> products = productSearchUseCase.searchProducts(condition, pageable);
		Map<Long, List<MarketRegistration>> registrationsByProduct = loadRegistrations(products.getContent());
		return ResponseEntity.ok(products.map(
			p -> ProductListResponse.from(p,
				buildMarketMap(registrationsByProduct.getOrDefault(p.getId(), List.of())))));
	}

	@GetMapping("/categories")
	public ResponseEntity<List<String>> getCategories() {
		return ResponseEntity.ok(productSearchUseCase.getCategoryNames());
	}

	@GetMapping("/brands")
	public ResponseEntity<List<String>> getBrands() {
		return ResponseEntity.ok(productSearchUseCase.getBrandNames());
	}

	@GetMapping("/{id}")
	public ResponseEntity<ProductDetailResponse> getProduct(@PathVariable
	Long id) {
		Product product = productSearchUseCase.getProductDetail(id);
		return ResponseEntity.ok(ProductDetailResponse.from(product));
	}

	@PutMapping("/{id}")
	public ResponseEntity<Void> updateProduct(
		@PathVariable
		Long id,
		@RequestBody
		ProductUpdateRequest request) {
		validateNonNegative(request);
		try {
			productManageUseCase.updateProduct(id, request.toCommand());
			actionLogService.record(ActionLogConstants.PRODUCT_UPDATE, null,
				ActionStatus.SUCCESS, "상품정보 수정 성공 (상품 " + id + ")");
			return ResponseEntity.ok().build();
		} catch (Exception e) {
			actionLogService.record(ActionLogConstants.PRODUCT_UPDATE, null,
				ActionStatus.FAILED, "상품정보 수정 실패 (상품 " + id + "): " + e.getMessage());
			throw e;
		}
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<ProductDeleteResult> deleteProduct(@PathVariable
	Long id) {
		try {
			ProductDeleteResult result = productManageUseCase.deleteProduct(id);
			if (!result.disposed()) {
				actionLogService.record(ActionLogConstants.PRODUCT_DELETE, null, ActionStatus.FAILED,
					"상품 폐기 보류 (상품 " + id + ") — 마켓에 리스팅이 남아 있습니다: 실패="
						+ result.failed().keySet() + ", 수동=" + result.manual().keySet());
				return ResponseEntity.status(org.springframework.http.HttpStatus.CONFLICT).body(result);
			}
			return ResponseEntity.ok(result);
		} catch (Exception e) {
			actionLogService.record(ActionLogConstants.PRODUCT_DELETE, null,
				ActionStatus.FAILED, "상품 삭제 실패 (상품 " + id + "): " + e.getMessage());
			throw e;
		}
	}

	@PutMapping("/{id}/price-stock")
	public ResponseEntity<MarketRepublishResult> updatePriceStock(
		@PathVariable
		Long id,
		@RequestBody
		PriceStockUpdateRequest request) {
		if (request.price() != null && request.price().signum() < 0) {
			throw new IllegalArgumentException("가격은 0 이상이어야 합니다: " + request.price());
		}
		try {
			MarketRepublishResult result = productManageUseCase.updatePriceStock(id, request.price(),
				request.soldOut());
			actionLogService.record(ActionLogConstants.PRODUCT_PRICE_STOCK_UPDATE, null,
				ActionStatus.SUCCESS, buildMarketResultMessage(id, "DB 저장 완료", result));
			return ResponseEntity.ok(result);
		} catch (Exception e) {
			actionLogService.record(ActionLogConstants.PRODUCT_PRICE_STOCK_UPDATE, null,
				ActionStatus.FAILED, "가격/재고 수정 실패 (상품 " + id + "): " + e.getMessage());
			throw e;
		}
	}

	@PutMapping("/{id}/images")
	public ResponseEntity<ImageUploadResponse> uploadImages(
		@PathVariable
		Long id,
		@RequestPart("images")
		List<MultipartFile> images) {
		ImageProcessResult prepared = prepareImageFiles(images);
		return uploadPreparedImages(id, prepared,
			ActionLogConstants.PRODUCT_IMAGE_UPDATE, "이미지 저장 완료", "이미지 수정 실패");
	}

	@PutMapping("/{id}/images/by-url")
	public ResponseEntity<ImageUploadResponse> uploadImagesByUrl(
		@PathVariable
		Long id,
		@RequestBody
		List<String> imageUrls) {
		if (imageUrls == null || imageUrls.isEmpty()) {
			throw new IllegalArgumentException("등록할 이미지 URL이 최소 1개 필요합니다.");
		}
		ImageProcessResult downloaded = imageDownloadClient.downloadAndConvertDetailed(imageUrls);
		return uploadPreparedImages(id, downloaded,
			ActionLogConstants.PRODUCT_IMAGE_UPDATE, "이미지 저장 완료", "이미지 수정 실패");
	}

	@GetMapping("/{id}/images/crawl")
	public ResponseEntity<List<String>> crawlSourceImages(@PathVariable
	Long id) {
		try {
			CrawlResult crawl = crawlSourceImageUrls(id);
			if (crawl.noSourcingUrl()) {
				actionLogService.record(ActionLogConstants.SOURCE_IMAGE_CRAWL, null,
					ActionStatus.SUCCESS, "소스이미지 없음 — 소싱 URL 미등록 (상품 " + id + ")");
				return ResponseEntity.ok(List.of());
			}
			List<String> images = crawl.images();
			actionLogService.record(ActionLogConstants.SOURCE_IMAGE_CRAWL, null,
				ActionStatus.SUCCESS,
				"소스이미지 크롤 " + images.size() + "개 수집 (상품 " + id + ")");
			return ResponseEntity.ok(images);
		} catch (Exception e) {
			actionLogService.record(ActionLogConstants.SOURCE_IMAGE_CRAWL, null,
				ActionStatus.FAILED, "소스이미지 크롤 실패 (상품 " + id + "): " + e.getMessage());
			throw e;
		}
	}

	@PostMapping("/{id}/images/crawl-and-upload")
	public ResponseEntity<ImageUploadResponse> crawlAndUpload(@PathVariable
	Long id) {
		String failedMsgPrefix = "소스이미지 크롤·업로드 실패";
		ImageProcessResult downloaded;
		int imageCount;
		try {
			CrawlResult crawl = crawlSourceImageUrls(id);
			if (crawl.noSourcingUrl()) {
				actionLogService.record(ActionLogConstants.SOURCE_IMAGE_CRAWL, null,
					ActionStatus.SUCCESS, "소스이미지 없음 — 소싱 URL 미등록 (상품 " + id + ")");
				return ResponseEntity.ok(ImageUploadResponse.from(
					new MarketRepublishResult(List.of(), List.of(), Map.of())));
			}
			List<String> images = crawl.images();
			if (images.isEmpty()) {
				actionLogService.record(ActionLogConstants.SOURCE_IMAGE_CRAWL, null,
					ActionStatus.SUCCESS, "소스이미지 0개 — 크롤 결과 없음 (상품 " + id + ")");
				return ResponseEntity.ok(ImageUploadResponse.from(
					new MarketRepublishResult(List.of(), List.of(), Map.of())));
			}
			imageCount = images.size();
			downloaded = imageDownloadClient.downloadAndConvertDetailed(images);
		} catch (Exception e) {
			actionLogService.record(ActionLogConstants.SOURCE_IMAGE_CRAWL, null,
				ActionStatus.FAILED, failedMsgPrefix + " (상품 " + id + "): " + e.getMessage());
			throw e;
		}
		return uploadPreparedImages(id, downloaded, ActionLogConstants.SOURCE_IMAGE_CRAWL,
			"소스이미지 " + imageCount + "개 크롤·업로드", failedMsgPrefix);
	}

	private ProductSearchCondition buildSearchCondition(
		String keyword, String marketFilter, List<ProductCategory> categories,
		List<VendorType> vendors, List<StockStatus> stockStatuses, List<MarketType> markets,
		boolean inStockOnly, boolean includeUncategorized, SourceGoneFilter sourceGone) {
		MarketType marketFilterType = null;
		boolean registered = false;
		if (marketFilter != null && !marketFilter.isBlank()) {
			registered = !marketFilter.startsWith("!");
			String marketName = registered ? marketFilter : marketFilter.substring(1);
			marketFilterType = MarketType.valueOf(marketName.toUpperCase());
		}
		return ProductSearchCondition.builder()
			.keyword(keyword)
			.marketFilterType(marketFilterType)
			.marketFilterRegistered(registered)
			.categories(categories)
			.vendors(vendors)
			.stockStatuses(stockStatuses)
			.markets(markets)
			.inStockOnly(inStockOnly)
			.includeUncategorized(includeUncategorized)
			.sourceGone(sourceGone)
			.build();
	}

	private Map<Long, List<MarketRegistration>> loadRegistrations(List<Product> products) {
		List<Long> productIds = products.stream().map(Product::getId).toList();
		if (productIds.isEmpty()) {
			return Collections.emptyMap();
		}
		return marketRegistrationRepository.findByProductIdIn(productIds).stream()
			.collect(Collectors.groupingBy(MarketRegistration::getProductId));
	}

	private Map<String, MarketBadgeState> buildMarketMap(List<MarketRegistration> registrations) {
		if (registrations.isEmpty()) {
			return Collections.emptyMap();
		}
		Map<String, MarketBadgeState> marketMap = new HashMap<>();
		for (MarketRegistration reg : registrations) {
			boolean hasIdentifiers = reg.hasIdentifiers();
			boolean isSynced = Boolean.TRUE.equals(reg.getIsSynced());
			switch (reg.getMarketType()) {
				case COUPANG:
				case SMART_STORE:
				case ELEVEN_STREET: {
					marketMap.put(reg.getMarketType().name(), MarketBadgeState.of(hasIdentifiers, isSynced,
						reg.getUnsyncReason(), reg.getLastSyncError(), reg.getLastSyncErrorAt(), reg.buildMarketUrl()));
					break;
				}
				case CAFE24: {
					marketMap.put("CAFE24", MarketBadgeState.of(hasIdentifiers, isSynced,
						reg.getUnsyncReason(), reg.getLastSyncError(), reg.getLastSyncErrorAt(), null));
					String gUrl = reg.buildGmarketUrl();
					if (gUrl != null) {
						marketMap.put("GMARKET", MarketBadgeState.of(true, gUrl));
					}
					String aUrl = reg.buildAuctionUrl();
					if (aUrl != null) {
						marketMap.put("AUCTION", MarketBadgeState.of(true, aUrl));
					}
					break;
				}
				default:
					break;
			}
		}
		return marketMap;
	}

	private void validateNonNegative(ProductUpdateRequest request) {
		requireNonNegative("원가(costPrice)", request.costPrice());
		requireNonNegative("환율(exchangeRate)", request.exchangeRate());
		requireNonNegative("배송비(deliveryFee)", request.deliveryFee());
		requireNonNegative("마진율(marginRate)", request.marginRate());
		requireNonNegative("판매가(salePrice)", request.salePrice());
		requireNonNegative("무게(weight)", request.weight());
		requireNonNegative("용량(capacity)", request.capacity());
		requireNonNegative("재고(stock)", request.stock());
		requireNonNegative("묶음수량(bundleQuantity)", request.bundleQuantity());
	}

	private ImageProcessResult prepareImageFiles(List<MultipartFile> images) {
		List<ImageUploadFile> uploadFiles = new ArrayList<>();
		List<ImageProcessResult.ImageFailure> failures = new ArrayList<>();
		for (MultipartFile mf : images) {
			try {
				byte[] rawBytes = mf.getBytes();
				ByteArrayOutputStream os = new ByteArrayOutputStream();
				Thumbnails.of(new ByteArrayInputStream(rawBytes))
					.size(1000, 1000)
					.outputFormat("jpg")
					.outputQuality(0.8)
					.toOutputStream(os);
				byte[] optimizedBytes = os.toByteArray();
				InputStream stream = new ByteArrayInputStream(optimizedBytes);
				uploadFiles.add(new ImageUploadFile(
					mf.getOriginalFilename(),
					"image/jpeg",
					stream,
					optimizedBytes.length));
			} catch (Exception e) {
				log.error("이미지 리사이즈 실패: {}", mf.getOriginalFilename(), e);
				String reason = (e.getMessage() == null || e.getMessage().isBlank())
					? e.getClass().getSimpleName() : e.getMessage();
				failures.add(new ImageProcessResult.ImageFailure(mf.getOriginalFilename(), reason));
			}
		}
		return ImageProcessResult.of(uploadFiles, failures);
	}

	private ResponseEntity<ImageUploadResponse> uploadPreparedImages(
		Long id, ImageProcessResult prepared, String logType,
		String succeededMsgPrefix, String failedMsgPrefix) {
		List<ImageUploadFile> uploadFiles = prepared.succeeded();
		try {
			MarketRepublishResult result = productManageUseCase.updateImagesAndHtml(id, uploadFiles);
			actionLogService.record(logType, null,
				ActionStatus.SUCCESS, buildImageResultMessage(id, succeededMsgPrefix, result, prepared));
			return ResponseEntity.ok(ImageUploadResponse.from(result, prepared, uploadFiles.size()));
		} catch (Exception e) {
			actionLogService.record(logType, null,
				ActionStatus.FAILED, failedMsgPrefix + " (상품 " + id + "): " + e.getMessage());
			throw e;
		}
	}

	private String buildImageResultMessage(
		Long productId, String prefix, MarketRepublishResult result, ImageProcessResult images) {
		int failed = images.failed().size();
		if (failed == 0) {
			return buildMarketResultMessage(productId, prefix, result);
		}
		String imgPrefix = prefix + " (이미지 " + images.succeeded().size() + "장 성공, " + failed + "장 실패)";
		return buildMarketResultMessage(productId, imgPrefix, result);
	}

	private String buildMarketResultMessage(Long productId, String prefix, MarketRepublishResult result) {
		Map<MarketType, String> codes = new HashMap<>();
		for (MarketRegistration reg : marketRegistrationRepository.findByProductId(productId)) {
			String code = reg.extractMarketCode();
			if (code != null && !code.isEmpty()) {
				codes.put(reg.getMarketType(), code);
			}
		}
		List<String> parts = new ArrayList<>();
		for (MarketType m : result.synced()) {
			parts.add(marketLabelWithCode(m, codes) + " 성공");
		}
		for (Map.Entry<MarketType, String> e : result.failed().entrySet()) {
			parts.add(marketLabelWithCode(e.getKey(), codes) + " 실패(" + truncateReason(e.getValue()) + ")");
		}
		for (MarketType m : result.skipped()) {
			parts.add(marketLabelWithCode(m, codes) + " 스킵");
		}
		if (parts.isEmpty()) {
			return prefix + " (연동 마켓 없음, 상품 " + productId + ")";
		}
		return prefix + " | " + String.join(", ", parts);
	}

	private String marketLabelWithCode(MarketType market, Map<MarketType, String> codes) {
		String code = codes.get(market);
		return (code == null || code.isEmpty()) ? market.getLabel() : market.getLabel() + " " + code;
	}

	private String truncateReason(String reason) {
		if (reason == null) {
			return "";
		}
		return reason.length() > 50 ? reason.substring(0, 50) : reason;
	}

	private CrawlResult crawlSourceImageUrls(Long id) {
		Product product = productSearchUseCase.getProductDetail(id);
		String sourcingUrl = product.getSourcingUrl();
		if (sourcingUrl == null || sourcingUrl.isEmpty()) {
			return new CrawlResult(true, List.of());
		}
		ScrapedProductDto scraped = productInfoCrawlerPort.crawlProductInfoAsDto(sourcingUrl);
		List<String> rawImages = (scraped == null || scraped.sourceImages() == null)
			? List.of() : scraped.sourceImages();
		return new CrawlResult(false, sanitizeCrawledImageUrls(id, rawImages));
	}

	private List<String> sanitizeCrawledImageUrls(Long id, List<String> rawImages) {
		LinkedHashSet<String> valid = new LinkedHashSet<>();
		for (String url : rawImages) {
			if (url == null) {
				continue;
			}
			String trimmed = url.trim();
			if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
				valid.add(trimmed);
			}
		}
		List<String> result = new ArrayList<>(valid);
		if (result.size() > MAX_CRAWL_IMAGES) {
			log.warn("소스이미지 크롤 개수 상한({}) 초과 — {}개 중 {}개로 절단 (상품 {})",
				MAX_CRAWL_IMAGES, result.size(), MAX_CRAWL_IMAGES, id);
			return new ArrayList<>(result.subList(0, MAX_CRAWL_IMAGES));
		}
		return result;
	}

	private record CrawlResult(boolean noSourcingUrl, List<String> images) {
	}
}
