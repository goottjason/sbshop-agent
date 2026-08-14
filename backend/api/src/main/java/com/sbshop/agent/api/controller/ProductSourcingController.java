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
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.product.dto.ProductCreateCommand;
import com.sbshop.agent.core.domain.actionlog.ActionLogConstants;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
import com.sbshop.agent.core.domain.order.enums.MarketType;
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
	// D-076: 사용자 액션 활동로그 기록 서비스
	private final ActionLogService actionLogService;

	// F-PSRC-5: iHerb 소싱 URL 한 요청당 상한. 크롤은 URL당 외부 HTTP 왕복이라
	// 무제한 목록은 장시간 점유·부하 위험 → 합리적 상한으로 차단.
	private static final int MAX_IHERB_URLS = 100;
	// F-PSRC-5: iHerb 상품 URL 형식.
	//   - http(s) 스킴
	//   - 호스트가 iherb.com 또는 그 서브도메인(www.iherb.com 등)
	//   - 크롤러(IherbScraperClient.extractProductId)가 ID를 뽑을 수 있는 경로:
	//     /product/{숫자} 또는 /pr/{이름}/{숫자}. 이 패턴이 없으면 크롤이 조용히 실패한다.
	private static final Pattern IHERB_URL_PATTERN = Pattern.compile(
		"^https?://([a-z0-9-]+\\.)*iherb\\.com(:\\d+)?(/\\S*)?(/product/\\d+|/pr/[^/]+/\\d+).*$",
		Pattern.CASE_INSENSITIVE);

	@PostMapping("/sourcing/iherb")
	public ResponseEntity<IherbSourcingResponse> sourceFromIherb(@RequestBody
	List<String> urls) {
		// F-PSRC-1: urls가 null/빈이면 UseCase에서 NPE(500)가 나기 전에 진입부에서 400으로 거부한다
		// (STARTED 로그만 남기고 실패하는 것을 방지).
		if (urls == null || urls.isEmpty()) {
			throw new IllegalArgumentException("urls는 필수이며 비어 있을 수 없습니다.");
		}
		// F-PSRC-5: 형식 검증(iHerb 도메인/http(s)/상품ID 패턴)·개수 상한·중복 제거.
		// 기존 null/빈 검증 위에 얹는다(형식 위반은 400으로 거부, 정상 URL은 오거부하지 않음).
		urls = validateAndDedupeIherbUrls(urls);
		// D-076: iHerb 소싱 크롤(장시간) — 시작+결과 기록.
		int reqCount = urls.size();
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

	// F-PSRC-5: 형식 위반 URL을 400으로 거부하고, 개수 상한을 넘으면 거부하며, 중복은 제거한다.
	// 순서를 보존(LinkedHashSet)하고 중복 제거 후 개수를 산정한다.
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

	@PostMapping("/products/bulk")
	public ResponseEntity<BulkProductCreateResponse> saveProductsBulk(@RequestBody
	List<ProductSaveRequest> requests) {
		// F-PSRC-7: requests가 null이면 진입부 .stream() NPE(500, 로그도 없음) 대신 400으로 거부.
		// F-PSRC-11: 빈 목록도 거부(처리할 대상 없음).
		if (requests == null || requests.isEmpty()) {
			throw new IllegalArgumentException("requests는 필수이며 비어 있을 수 없습니다.");
		}
		// F-PSRC-11: 금액(costPrice) 음수는 데이터 오염이므로 거부한다.
		for (ProductSaveRequest request : requests) {
			if (request.costPrice() != null && request.costPrice().signum() < 0) {
				throw new IllegalArgumentException("costPrice는 음수일 수 없습니다: " + request.costPrice());
			}
		}
		List<ProductCreateCommand> commands = requests.stream()
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
	public ResponseEntity<MarketPublishResponse> publishToMarket(
		@PathVariable
		Long id,
		@PathVariable
		String marketType,
		// 결함 B: 등록가 산정 파라미터(마진율·쿠폰율·최소마진) — 선택적 바디. 없으면 종전 동작과 같다.
		@RequestBody(required = false)
		MarketPublishPriceRequest priceRequest) {
		MarketType type = MarketType.valueOf(marketType.toUpperCase());
		// D-076: 마켓 등록(게시) — 결과만 기록(marketType은 경로변수에서).
		try {
			MarketPublishOutcome outcome = productPublishUseCase.publishToMarket(
				id, type, priceRequest == null ? null : priceRequest.toOverrides());
			// 등록 직후 링크 식별자가 확보됐으면 URL까지 내려 배지를 바로 링크로 바꾼다.
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
}
