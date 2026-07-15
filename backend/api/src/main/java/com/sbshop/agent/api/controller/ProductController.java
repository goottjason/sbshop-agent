package com.sbshop.agent.api.controller;

import com.sbshop.agent.api.dto.product.ImageUploadResponse;
import com.sbshop.agent.api.dto.product.PriceStockUpdateRequest;
import com.sbshop.agent.api.dto.product.ProductDetailResponse;
import com.sbshop.agent.api.dto.product.ProductListResponse;
import com.sbshop.agent.api.dto.product.ProductUpdateRequest;
import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.domain.actionlog.ActionLogConstants;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
import com.sbshop.agent.core.application.product.ProductManageUseCase;
import com.sbshop.agent.core.application.product.MarketRepublishResult;
import com.sbshop.agent.core.application.product.ProductSearchUseCase;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.client.ImageDownloadClient;
import com.sbshop.agent.core.domain.product.client.dto.ImageProcessResult;
import com.sbshop.agent.core.domain.product.client.dto.ImageUploadFile;
import com.sbshop.agent.core.application.product.port.ProductInfoCrawlerPort;
import com.sbshop.agent.core.application.sourcing.dto.ScrapedProductDto;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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

	private final ProductSearchUseCase productSearchUseCase;
	private final ProductManageUseCase productManageUseCase;
	private final ImageDownloadClient imageDownloadClient;
	private final ProductInfoCrawlerPort productInfoCrawlerPort;
	private final MarketRegistrationRepository marketRegistrationRepository;
	// D-076: 사용자 액션 활동로그 기록 서비스
	private final ActionLogService actionLogService;

	@GetMapping
	public ResponseEntity<Page<ProductListResponse>> getProducts(
		@RequestParam(required = false)
		String keyword,
		@RequestParam(required = false)
		String marketFilter,
		@PageableDefault(size = 50)
		Pageable pageable) {
		Page<Product> products;
		if (marketFilter != null && !marketFilter.isBlank()) {
			boolean registered = !marketFilter.startsWith("!");
			String marketName = registered ? marketFilter : marketFilter.substring(1);
			MarketType marketType = MarketType.valueOf(marketName.toUpperCase());
			products = productSearchUseCase.searchByMarket(marketType, registered, pageable);
		} else {
			products = productSearchUseCase.searchProducts(keyword, pageable);
		}
		// D-047: 페이지 상품 전체의 마켓 등록정보를 한 번에 배치 조회(N+1 제거).
		Map<Long, List<MarketRegistration>> registrationsByProduct = loadRegistrations(products.getContent());
		return ResponseEntity.ok(products.map(
			p -> ProductListResponse.from(p, buildMarketMap(registrationsByProduct.getOrDefault(p.getId(), List.of())))));
	}

	@GetMapping("/{id}")
	public ResponseEntity<ProductDetailResponse> getProduct(@PathVariable
	Long id) {
		Product product = productSearchUseCase.getProductDetail(id);
		return ResponseEntity.ok(ProductDetailResponse.from(product));
	}

	@PutMapping("/{id}/price-stock")
	public ResponseEntity<MarketRepublishResult> updatePriceStock(
		@PathVariable
		Long id,
		@RequestBody
		PriceStockUpdateRequest request) {
		// D-060: 자사 DB 갱신 + 연동 마켓 가격/재고 반영 결과(성공/스킵/실패 마켓) 반환.
		// D-076: 가격/재고 수정 — 결과만 기록(다마켓 동시 반영이므로 marketType null).
		try {
			MarketRepublishResult result =
				productManageUseCase.updatePriceStock(id, request.price(), Boolean.TRUE.equals(request.soldOut()));
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
		// F-PROD-12: 개별 이미지 리사이즈 실패를 조용히 드롭하지 않고 집계해 응답에 표면화.
		ImageProcessResult prepared = prepareImageFiles(images);
		List<ImageUploadFile> uploadFiles = prepared.succeeded();
		// D-076: 이미지/HTML 수정 — 결과만 기록.
		try {
			MarketRepublishResult result = productManageUseCase.updateImagesAndHtml(id, uploadFiles);
			actionLogService.record(ActionLogConstants.PRODUCT_IMAGE_UPDATE, null,
				ActionStatus.SUCCESS, buildImageResultMessage(id, "이미지 저장 완료", result, prepared));
			return ResponseEntity.ok(ImageUploadResponse.from(result, prepared, uploadFiles.size()));
		} catch (Exception e) {
			actionLogService.record(ActionLogConstants.PRODUCT_IMAGE_UPDATE, null,
				ActionStatus.FAILED, "이미지 수정 실패 (상품 " + id + "): " + e.getMessage());
			throw e;
		}
	}

	@PutMapping("/{id}/images/by-url")
	public ResponseEntity<ImageUploadResponse> uploadImagesByUrl(
		@PathVariable
		Long id,
		@RequestBody
		List<String> imageUrls) {
		// F-PROD-16: 개별 URL 다운로드 실패를 조용히 드롭하지 않고 집계해 응답에 표면화.
		ImageProcessResult downloaded = imageDownloadClient.downloadAndConvertDetailed(imageUrls);
		List<ImageUploadFile> downloadFiles = downloaded.succeeded();
		// D-076: 이미지(URL) 수정 — 결과만 기록.
		try {
			MarketRepublishResult result = productManageUseCase.updateImagesAndHtml(id, downloadFiles);
			actionLogService.record(ActionLogConstants.PRODUCT_IMAGE_UPDATE, null,
				ActionStatus.SUCCESS, buildImageResultMessage(id, "이미지 저장 완료", result, downloaded));
			return ResponseEntity.ok(ImageUploadResponse.from(result, downloaded, downloadFiles.size()));
		} catch (Exception e) {
			actionLogService.record(ActionLogConstants.PRODUCT_IMAGE_UPDATE, null,
				ActionStatus.FAILED, "이미지 수정 실패 (상품 " + id + "): " + e.getMessage());
			throw e;
		}
	}

	@GetMapping("/{id}/images/crawl")
	public ResponseEntity<List<String>> crawlSourceImages(@PathVariable
	Long id) {
		// D-078: 소스이미지 크롤 활동로그 배선. 마켓 무관(소싱 크롤)이므로 marketType null.
		// 빈결과(소싱 URL 없음/스크랩 null)도 "왜 비었는지" 사용자에게 보이도록 결과 기록.
		try {
			Product product = productSearchUseCase.getProductDetail(id);
			String sourcingUrl = product.getSourcingUrl();
			if (sourcingUrl == null || sourcingUrl.isEmpty()) {
				actionLogService.record(ActionLogConstants.SOURCE_IMAGE_CRAWL, null,
					ActionStatus.SUCCESS, "소스이미지 없음 — 소싱 URL 미등록 (상품 " + id + ")");
				return ResponseEntity.ok(List.of());
			}
			ScrapedProductDto scraped = productInfoCrawlerPort.crawlProductInfoAsDto(sourcingUrl);
			List<String> images = (scraped == null || scraped.sourceImages() == null)
				? List.of() : scraped.sourceImages();
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
	public ResponseEntity<ImageUploadResponse> crawlAndUpload(@PathVariable Long id) {
		try {
			Product product = productSearchUseCase.getProductDetail(id);
			String sourcingUrl = product.getSourcingUrl();
			if (sourcingUrl == null || sourcingUrl.isEmpty()) {
				actionLogService.record(ActionLogConstants.SOURCE_IMAGE_CRAWL, null,
					ActionStatus.SUCCESS, "소스이미지 없음 — 소싱 URL 미등록 (상품 " + id + ")");
				return ResponseEntity.ok(ImageUploadResponse.from(
					new MarketRepublishResult(List.of(), List.of(), Map.of())));
			}
			ScrapedProductDto scraped = productInfoCrawlerPort.crawlProductInfoAsDto(sourcingUrl);
			List<String> images = (scraped == null || scraped.sourceImages() == null)
				? List.of() : scraped.sourceImages();
			if (images.isEmpty()) {
				actionLogService.record(ActionLogConstants.SOURCE_IMAGE_CRAWL, null,
					ActionStatus.SUCCESS, "소스이미지 0개 — 크롤 결과 없음 (상품 " + id + ")");
				return ResponseEntity.ok(ImageUploadResponse.from(
					new MarketRepublishResult(List.of(), List.of(), Map.of())));
			}
			// F-PROD-16: 개별 URL 다운로드 실패를 조용히 드롭하지 않고 집계해 응답에 표면화.
			ImageProcessResult downloaded = imageDownloadClient.downloadAndConvertDetailed(images);
			List<ImageUploadFile> files = downloaded.succeeded();
			MarketRepublishResult result = productManageUseCase.updateImagesAndHtml(id, files);
			actionLogService.record(ActionLogConstants.SOURCE_IMAGE_CRAWL, null,
				ActionStatus.SUCCESS,
				buildImageResultMessage(id, "소스이미지 " + images.size() + "개 크롤·업로드", result, downloaded));
			return ResponseEntity.ok(ImageUploadResponse.from(result, downloaded, files.size()));
		} catch (Exception e) {
			actionLogService.record(ActionLogConstants.SOURCE_IMAGE_CRAWL, null,
				ActionStatus.FAILED, "소스이미지 크롤·업로드 실패 (상품 " + id + "): " + e.getMessage());
			throw e;
		}
	}

	@PutMapping("/{id}")
	public ResponseEntity<Void> updateProduct(
		@PathVariable
		Long id,
		@RequestBody
		ProductUpdateRequest request) {
		// D-076: 상품 정보 수정 — 결과만 기록.
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
	public ResponseEntity<Void> deleteProduct(@PathVariable
	Long id) {
		// D-076: 상품 삭제 — 결과만 기록.
		try {
			productManageUseCase.deleteProduct(id);
			actionLogService.record(ActionLogConstants.PRODUCT_DELETE, null,
				ActionStatus.SUCCESS, "상품 삭제 성공 (상품 " + id + ")");
			return ResponseEntity.ok().build();
		} catch (Exception e) {
			actionLogService.record(ActionLogConstants.PRODUCT_DELETE, null,
				ActionStatus.FAILED, "상품 삭제 실패 (상품 " + id + "): " + e.getMessage());
			throw e;
		}
	}

	/**
	 * D-047: 페이지에 담긴 상품들의 마켓 등록정보를 배치 조회(findByProductIdIn)하여 productId로 그룹화한다.
	 * 기존 row별 findByProductId(N+1) 대비 쿼리 1회로 축소(성능 회귀 예방).
	 */
	private Map<Long, List<MarketRegistration>> loadRegistrations(List<Product> products) {
		List<Long> productIds = products.stream().map(Product::getId).toList();
		if (productIds.isEmpty()) {
			return Collections.emptyMap();
		}
		return marketRegistrationRepository.findByProductIdIn(productIds).stream()
			.collect(Collectors.groupingBy(MarketRegistration::getProductId));
	}

	/**
	 * D-077: 마켓별 상세 활동로그 메시지를 조립한다.
	 * "{prefix} | 쿠팡 {번호} 성공, 스마트스토어 {번호} 실패(사유), G마켓 스킵" 형태.
	 * 마켓 라벨은 한글(MarketType.getLabel()), 상품번호는 extractMarketCode()(없으면 생략).
	 * 실패 사유는 50자로 절단(전체 message 1000자는 ActionLogService.truncate가 처리).
	 */
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

	private Map<String, String> buildMarketMap(List<MarketRegistration> registrations) {
		if (registrations.isEmpty()) {
			return Collections.emptyMap();
		}
		Map<String, String> marketMap = new HashMap<>();
		for (MarketRegistration reg : registrations) {
			// D-052: 마켓별 실제 상품코드(쿠팡=vendorItemId, 스토어=originProductNo,
			// 11번가=elevenstId, 카페24=product_no, ESM+=goodsNo). 코드 없으면 productId 폴백('미확인').
			String marketId = reg.extractMarketCode();
			if (marketId == null || marketId.isEmpty()) {
				marketId = String.valueOf(reg.getProductId());
			}
			marketMap.put(reg.getMarketType().name(), marketId);
		}
		return marketMap;
	}

	/**
	 * F-PROD-12: 개별 이미지 리사이즈 실패를 조용히 드롭하지 않고, 성공 파일과 실패 항목(파일명·사유)을
	 * {@link ImageProcessResult}로 함께 반환한다. 실패는 응답의 imagesFailed로 표면화된다.
	 */
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

	/**
	 * F-PROD-12·F-PROD-16: 마켓 재게시 메시지에 개별 이미지 부분 실패(성공 N/실패 M)를 덧붙인다.
	 * 이미지 실패가 없으면 기존 마켓 메시지를 그대로 반환한다.
	 */
	private String buildImageResultMessage(
		Long productId, String prefix, MarketRepublishResult result, ImageProcessResult images) {
		int failed = images.failed().size();
		if (failed == 0) {
			return buildMarketResultMessage(productId, prefix, result);
		}
		String imgPrefix = prefix + " (이미지 " + images.succeeded().size() + "장 성공, " + failed + "장 실패)";
		return buildMarketResultMessage(productId, imgPrefix, result);
	}
}
