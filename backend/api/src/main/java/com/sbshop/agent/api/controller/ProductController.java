package com.sbshop.agent.api.controller;

import com.sbshop.agent.api.dto.product.PriceStockUpdateRequest;
import com.sbshop.agent.api.dto.product.ProductDetailResponse;
import com.sbshop.agent.api.dto.product.ProductListResponse;
import com.sbshop.agent.api.dto.product.ProductUpdateRequest;
import com.sbshop.agent.core.application.product.ProductManageUseCase;
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

	@GetMapping
	public ResponseEntity<Page<ProductListResponse>> getProducts(
			@RequestParam(required = false) String keyword,
			@RequestParam(required = false) String marketFilter,
			@PageableDefault(size = 50) Pageable pageable) {
		Page<Product> products;
		if (marketFilter != null && !marketFilter.isBlank()) {
			boolean registered = !marketFilter.startsWith("!");
			String marketName = registered ? marketFilter : marketFilter.substring(1);
			MarketType marketType = MarketType.valueOf(marketName.toUpperCase());
			products = productSearchUseCase.searchByMarket(marketType, registered, pageable);
		} else {
			products = productSearchUseCase.searchProducts(keyword, pageable);
		}
		return ResponseEntity.ok(products.map(this::toListResponse));
	}

	@GetMapping("/{id}")
	public ResponseEntity<ProductDetailResponse> getProduct(@PathVariable Long id) {
		Product product = productSearchUseCase.getProductDetail(id);
		return ResponseEntity.ok(ProductDetailResponse.from(product));
	}

	@PutMapping("/{id}/price-stock")
	public ResponseEntity<Void> updatePriceStock(
			@PathVariable Long id,
			@RequestBody PriceStockUpdateRequest request) {
		productManageUseCase.updatePriceStock(id, request.price(), request.stock());
		return ResponseEntity.ok().build();
	}

	@PutMapping("/{id}/images")
	public ResponseEntity<Void> uploadImages(
			@PathVariable Long id,
			@RequestPart("images") List<MultipartFile> images) {
		List<ImageUploadFile> uploadFiles = prepareImageFiles(images);
		productManageUseCase.updateImagesAndHtml(id, uploadFiles);
		return ResponseEntity.ok().build();
	}

	@PutMapping("/{id}/images/by-url")
	public ResponseEntity<Void> uploadImagesByUrl(
			@PathVariable Long id,
			@RequestBody List<String> imageUrls) {
		List<ImageUploadFile> downloadFiles = imageDownloadClient.downloadAndConvert(imageUrls);
		productManageUseCase.updateImagesAndHtml(id, downloadFiles);
		return ResponseEntity.ok().build();
	}

	@GetMapping("/{id}/images/crawl")
	public ResponseEntity<List<String>> crawlSourceImages(@PathVariable Long id) {
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
			@PathVariable Long id,
			@RequestBody ProductUpdateRequest request) {
		productManageUseCase.updateProduct(id, request.toCommand());
		return ResponseEntity.ok().build();
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> deleteProduct(@PathVariable Long id) {
		productManageUseCase.deleteProduct(id);
		return ResponseEntity.ok().build();
	}

	private ProductListResponse toListResponse(Product p) {
		Map<String, String> marketMap = getMarketMap(p.getId());
		return ProductListResponse.from(p, marketMap);
	}

	private Map<String, String> getMarketMap(Long productId) {
		List<MarketRegistration> registrations = marketRegistrationRepository.findByProductId(productId);
		if (registrations.isEmpty()) {
			return Collections.emptyMap();
		}
		Map<String, String> marketMap = new HashMap<>();
		for (MarketRegistration reg : registrations) {
			String marketId = reg.extractVendorItemId();
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
