package com.sbshop.agent.api.controller;

import com.sbshop.agent.api.dto.product.BulkProductCreateResponse;
import com.sbshop.agent.api.dto.product.IherbSourcingResponse;
import com.sbshop.agent.api.dto.product.MarketPublishPriceRequest;
import com.sbshop.agent.api.dto.product.MarketPublishResponse;
import com.sbshop.agent.api.dto.product.ProductSaveRequest;
import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.product.ProductCreateUseCase;
import com.sbshop.agent.core.application.product.ProductPublishUseCase;
import com.sbshop.agent.core.application.product.dto.BulkProductCreateResult;
import com.sbshop.agent.core.application.product.dto.MarketPublishOutcome;
import com.sbshop.agent.core.application.sourcing.ProductSourcingUseCase;
import com.sbshop.agent.core.application.sourcing.dto.SourcingCrawlResult;
import com.sbshop.agent.core.domain.actionlog.ActionLogConstants;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.dto.ProductCreateCommand;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ProductSourcingController {
	private final ProductSourcingUseCase productSourcingUseCase;
	private final ProductCreateUseCase productCreateUseCase;
	private final ProductPublishUseCase productPublishUseCase;
	private final MarketRegistrationRepository marketRegistrationRepository;
	private final ActionLogService actionLogService;
	private static final int MAX_IHERB_URLS = 100;

	private static final Pattern IHERB_URL_PATTERN = Pattern.compile(
		"^https?://([a-z0-9-]+\\.)*iherb\\.com(:\\d+)?(/\\S*)?(/product/\\d+|/pr/[^/]+/\\d+).*$",
		Pattern.CASE_INSENSITIVE);

	@PostMapping("/sourcing/iherb")
	public ResponseEntity<IherbSourcingResponse> sourceFromIherb(@RequestBody
	List<String> urls) {
		if (urls == null || urls.isEmpty()) {
			throw new IllegalArgumentException("urls는 필수이며 비어 있을 수 없습니다.");
		}
		urls = validateAndDedupeIherbUrls(urls);
		int reqCount = urls.size();
		actionLogService.record(ActionLogConstants.PRODUCT_SOURCING, null,
			ActionStatus.STARTED, "iHerb 소싱 크롤 요청 (" + reqCount + "건)");
		try {
			SourcingCrawlResult result = productSourcingUseCase.sourceFromIherb(urls);
			IherbSourcingResponse response = IherbSourcingResponse.from(result);
			actionLogService.record(ActionLogConstants.PRODUCT_SOURCING, null,
				ActionStatus.SUCCESS, "iHerb 소싱 크롤 완료 (성공 " + response.succeeded().size()
					+ "건, 실패 " + response.failed().size() + "건)");
			return ResponseEntity.ok(response);
		} catch (Exception e) {
			actionLogService.record(ActionLogConstants.PRODUCT_SOURCING, null,
				ActionStatus.FAILED, "iHerb 소싱 크롤 실패: " + e.getMessage());
			throw e;
		}
	}

	@PostMapping("/products/bulk")
	public ResponseEntity<BulkProductCreateResponse> saveProductsBulk(@RequestBody
	List<ProductSaveRequest> requests) {
		if (requests == null || requests.isEmpty()) {
			throw new IllegalArgumentException("requests는 필수이며 비어 있을 수 없습니다.");
		}
		for (ProductSaveRequest request : requests) {
			if (request.costPrice() != null && request.costPrice().signum() < 0) {
				throw new IllegalArgumentException("costPrice는 음수일 수 없습니다: " + request.costPrice());
			}
		}
		List<ProductCreateCommand> commands = requests.stream()
			.map(ProductSaveRequest::toCommand)
			.toList();
		try {
			BulkProductCreateResult result = productCreateUseCase.createBulk(commands);
			BulkProductCreateResponse response = BulkProductCreateResponse.from(result);
			actionLogService.record(ActionLogConstants.PRODUCT_BULK_CREATE, null,
				ActionStatus.SUCCESS, "상품 일괄등록 완료 (성공 " + response.succeeded().size()
					+ "건, 실패 " + response.failed().size() + "건)");
			return ResponseEntity.ok(response);
		} catch (Exception e) {
			actionLogService.record(ActionLogConstants.PRODUCT_BULK_CREATE, null,
				ActionStatus.FAILED, "상품 일괄등록 실패 (" + commands.size() + "건): " + e.getMessage());
			throw e;
		}
	}

	@PostMapping("/products/{id}/markets/{marketType}")
	public ResponseEntity<MarketPublishResponse> publishToMarket(
		@PathVariable
		Long id,
		@PathVariable
		String marketType,
		@RequestBody(required = false)
		MarketPublishPriceRequest priceRequest,
		@RequestParam(defaultValue = "false")
		boolean force) {
		MarketType type = MarketType.valueOf(marketType.toUpperCase());
		try {
			MarketPublishOutcome outcome = productPublishUseCase.publishToMarket(
				id, type, priceRequest == null ? null : priceRequest.toOverrides(), force);
			String url = marketRegistrationRepository.findByProductIdAndMarketType(id, type)
				.map(MarketRegistration::buildMarketUrl)
				.orElse(null);
			actionLogService.record(ActionLogConstants.PRODUCT_PUBLISH, type.name(),
				ActionStatus.SUCCESS, "마켓 게시 성공 (상품 " + id + ")");
			return ResponseEntity.ok(MarketPublishResponse.from(outcome, url));
		} catch (Exception e) {
			actionLogService.record(ActionLogConstants.PRODUCT_PUBLISH, type.name(),
				ActionStatus.FAILED, "마켓 게시 실패 (상품 " + id + "): " + e.getMessage());
			throw e;
		}
	}

	private List<String> validateAndDedupeIherbUrls(List<String> urls) {
		List<String> deduped = new ArrayList<>(new LinkedHashSet<>(urls));
		if (deduped.size() > MAX_IHERB_URLS) {
			throw new IllegalArgumentException(
				"URL은 한 번에 최대 " + MAX_IHERB_URLS + "개까지 처리할 수 있습니다: " + deduped.size() + "개");
		}
		for (String url : deduped) {
			if (url == null || url.isBlank()) {
				throw new IllegalArgumentException("URL은 비어 있을 수 없습니다.");
			}
			if (!IHERB_URL_PATTERN.matcher(url.trim()).matches()) {
				throw new IllegalArgumentException("유효한 iHerb 상품 URL이 아닙니다: " + url);
			}
		}
		return deduped;
	}
}
