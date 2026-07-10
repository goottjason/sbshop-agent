package com.sbshop.agent.api.controller;

import com.sbshop.agent.api.dto.product.ImageUploadResponse;
import com.sbshop.agent.api.dto.product.PriceStockUpdateRequest;
import com.sbshop.agent.api.dto.product.ProductDetailResponse;
import com.sbshop.agent.api.dto.product.ProductListResponse;
import com.sbshop.agent.api.dto.product.ProductUpdateRequest;
import com.sbshop.agent.core.application.product.ProductManageUseCase;
import com.sbshop.agent.core.application.product.MarketRepublishResult;
import com.sbshop.agent.core.application.product.ProductSearchUseCase;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.client.ImageDownloadClient;
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
		MarketRepublishResult result =
			productManageUseCase.updatePriceStock(id, request.price(), request.stock());
		return ResponseEntity.ok(result);
	}

	@PutMapping("/{id}/images")
	public ResponseEntity<ImageUploadResponse> uploadImages(
		@PathVariable
		Long id,
		@RequestPart("images")
		List<MultipartFile> images) {
		List<ImageUploadFile> uploadFiles = prepareImageFiles(images);
		MarketRepublishResult result = productManageUseCase.updateImagesAndHtml(id, uploadFiles);
		return ResponseEntity.ok(ImageUploadResponse.from(result));
	}

	@PutMapping("/{id}/images/by-url")
	public ResponseEntity<ImageUploadResponse> uploadImagesByUrl(
		@PathVariable
		Long id,
		@RequestBody
		List<String> imageUrls) {
		List<ImageUploadFile> downloadFiles = imageDownloadClient.downloadAndConvert(imageUrls);
		MarketRepublishResult result = productManageUseCase.updateImagesAndHtml(id, downloadFiles);
		return ResponseEntity.ok(ImageUploadResponse.from(result));
	}

	@GetMapping("/{id}/images/crawl")
	public ResponseEntity<List<String>> crawlSourceImages(@PathVariable
	Long id) {
		Product product = productSearchUseCase.getProductDetail(id);
		String sourcingUrl = product.getSourcingUrl();
		if (sourcingUrl == null || sourcingUrl.isEmpty()) {
			return ResponseEntity.ok(List.of());
		}
		ScrapedProductDto scraped = productInfoCrawlerPort.crawlProductInfoAsDto(sourcingUrl);
		if (scraped == null || scraped.sourceImages() == null) {
			return ResponseEntity.ok(List.of());
		}
		return ResponseEntity.ok(scraped.sourceImages());
	}

	@PutMapping("/{id}")
	public ResponseEntity<Void> updateProduct(
		@PathVariable
		Long id,
		@RequestBody
		ProductUpdateRequest request) {
		productManageUseCase.updateProduct(id, request.toCommand());
		return ResponseEntity.ok().build();
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> deleteProduct(@PathVariable
	Long id) {
		productManageUseCase.deleteProduct(id);
		return ResponseEntity.ok().build();
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

	private List<ImageUploadFile> prepareImageFiles(List<MultipartFile> images) {
		List<ImageUploadFile> uploadFiles = new ArrayList<>();
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
			}
		}
		return uploadFiles;
	}
}
