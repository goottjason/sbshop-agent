package com.sbshop.agent.api.controller;

import com.sbshop.agent.api.dto.product.BulkProductCreateResponse;
import com.sbshop.agent.api.dto.product.IherbSourcingResponse;
import com.sbshop.agent.api.dto.product.ProductSaveRequest;
import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.product.ProductCreateUseCase;
import com.sbshop.agent.core.application.product.ProductPublishUseCase;
import com.sbshop.agent.core.application.product.dto.BulkProductCreateResult;
import com.sbshop.agent.core.application.sourcing.ProductSourcingUseCase;
import com.sbshop.agent.core.application.sourcing.dto.SourcingCrawlResult;
import com.sbshop.agent.core.domain.actionlog.ActionLogConstants;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
	// D-076: 사용자 액션 활동로그 기록 서비스
	private final ActionLogService actionLogService;

	@PostMapping("/sourcing/iherb")
	public ResponseEntity<IherbSourcingResponse> sourceFromIherb(@RequestBody
	List<String> urls) {
		// D-076: iHerb 소싱 크롤(장시간) — 시작+결과 기록.
		int reqCount = urls != null ? urls.size() : 0;
		actionLogService.record(ActionLogConstants.PRODUCT_SOURCING, null,
			ActionStatus.STARTED, "iHerb 소싱 크롤 요청 (" + reqCount + "건)");
		try {
			SourcingCrawlResult result = productSourcingUseCase.sourceFromIherb(urls);
			IherbSourcingResponse response = IherbSourcingResponse.from(result);
			// F-PSRC-2: 실패 URL은 조용히 누락하지 않고 응답에 포함하며, 로그에도 성공/실패 건수를 남긴다.
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
		List<com.sbshop.agent.core.domain.product.dto.ProductCreateCommand> commands = requests.stream()
			.map(ProductSaveRequest::toCommand)
			.toList();
		// SP-D: 신규 상품 일괄 등록 — 성공/실패를 각각 집계 반환(F-PSRC-6, 마켓등록 연결·실패 표면화용).
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
	public ResponseEntity<Void> publishToMarket(
		@PathVariable
		Long id,
		@PathVariable
		String marketType) {
		MarketType type = MarketType.valueOf(marketType.toUpperCase());
		// D-076: 마켓 등록(게시) — 결과만 기록(marketType은 경로변수에서).
		try {
			productPublishUseCase.publishToMarket(id, type);
			actionLogService.record(ActionLogConstants.PRODUCT_PUBLISH, type.name(),
				ActionStatus.SUCCESS, "마켓 게시 성공 (상품 " + id + ")");
			return ResponseEntity.ok().build();
		} catch (Exception e) {
			actionLogService.record(ActionLogConstants.PRODUCT_PUBLISH, type.name(),
				ActionStatus.FAILED, "마켓 게시 실패 (상품 " + id + "): " + e.getMessage());
			throw e;
		}
	}
}
