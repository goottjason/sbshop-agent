package com.sbshop.agent.api.controller;

import com.sbshop.agent.api.dto.sourcing.SourcingDtos.CandidateResponse;
import com.sbshop.agent.api.dto.sourcing.SourcingDtos.ConfigResponse;
import com.sbshop.agent.api.dto.sourcing.SourcingDtos.ConfigUpdateRequest;
import com.sbshop.agent.api.dto.sourcing.SourcingDtos.CreateDraftsRequest;
import com.sbshop.agent.api.dto.sourcing.SourcingDtos.CreateDraftsResponse;
import com.sbshop.agent.api.dto.sourcing.SourcingDtos.DiscoveryRunResponse;
import com.sbshop.agent.api.dto.sourcing.SourcingDtos.DraftFailure;
import com.sbshop.agent.api.dto.sourcing.SourcingDtos.DraftResponse;
import com.sbshop.agent.api.dto.sourcing.SourcingDtos.PublishDraftResponse;
import com.sbshop.agent.api.dto.sourcing.SourcingDtos.UpdateDraftRequest;
import com.sbshop.agent.api.service.SourcingDiscoveryRunner;
import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.sourcing.SourcingQueryService;
import com.sbshop.agent.core.application.sourcing.customs.BannedIngredientSyncService;
import com.sbshop.agent.core.application.sourcing.discovery.SourcingConfigService;
import com.sbshop.agent.core.application.sourcing.dto.DiscoverySummary;
import com.sbshop.agent.core.application.sourcing.enrich.DraftEnrichmentUseCase;
import com.sbshop.agent.core.application.sourcing.publish.DraftPublishUseCase;
import com.sbshop.agent.core.domain.actionlog.ActionLogConstants;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
import com.sbshop.agent.core.domain.sourcing.SourcingCandidate;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1/sourcing")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class SourcingDiscoveryController {

	private final SourcingDiscoveryRunner discoveryRunner;
	private final DraftEnrichmentUseCase enrichmentUseCase;
	private final DraftPublishUseCase publishUseCase;
	private final SourcingQueryService queryService;
	private final SourcingConfigService configService;
	private final BannedIngredientSyncService bannedIngredientSyncService;
	private final ActionLogService actionLogService;

	@PostMapping("/discovery/run")
	public ResponseEntity<Map<String, Object>> runDiscovery() {
		if (!discoveryRunner.tryStart()) {
			return ResponseEntity.status(409)
				.body(Map.of("message", "발굴이 이미 실행 중입니다."));
		}
		try {
			discoveryRunner.runAsync();
		} catch (RuntimeException e) {
			discoveryRunner.abort();
			throw e;
		}
		return ResponseEntity.accepted().body(Map.of(
			"message", "발굴을 시작했습니다. 수 분 소요되며 완료되면 추천 목록이 갱신됩니다.",
			"statusUrl", "/api/v1/sourcing/discovery/status"));
	}

	@GetMapping("/discovery/status")
	public ResponseEntity<Map<String, Object>> discoveryStatus() {
		DiscoverySummary summary = discoveryRunner.lastSummary();
		return ResponseEntity.ok(Map.of(
			"running", discoveryRunner.isRunning(),
			"lastRun", summary == null ? Map.of() : DiscoveryRunResponse.from(summary)));
	}

	@GetMapping("/candidates")
	public ResponseEntity<List<CandidateResponse>> candidates(
		@RequestParam(required = false)
		Integer limit,
		@RequestParam(defaultValue = "true")
		boolean includeReview) {
		return ResponseEntity.ok(queryService.recommended(limit, includeReview).stream()
			.map(CandidateResponse::from).toList());
	}

	@GetMapping("/candidates/customs-blocked")
	public ResponseEntity<List<CandidateResponse>> customsBlocked() {
		return ResponseEntity.ok(queryService.customsBlocked().stream()
			.map(CandidateResponse::from).toList());
	}

	@GetMapping("/candidates/{id}")
	public ResponseEntity<CandidateResponse> candidate(@PathVariable
	Long id) {
		return ResponseEntity.ok(CandidateResponse.from(queryService.requireCandidate(id)));
	}

	@PostMapping("/candidates/{id}/reject")
	public ResponseEntity<CandidateResponse> reject(@PathVariable
	Long id) {
		return ResponseEntity.ok(CandidateResponse.from(queryService.reject(id)));
	}

	@PostMapping("/drafts")
	public ResponseEntity<CreateDraftsResponse> createDrafts(
		@RequestBody
		CreateDraftsRequest request) {
		if (request == null || request.candidateIds() == null || request.candidateIds().isEmpty()) {
			throw new IllegalArgumentException("candidateIds는 필수이며 비어 있을 수 없습니다.");
		}
		List<SourcingCandidate> candidates = queryService.findAllById(request.candidateIds());
		if (candidates.isEmpty()) {
			throw new IllegalArgumentException("선택한 후보를 찾을 수 없습니다.");
		}

		DraftEnrichmentUseCase.Result result = enrichmentUseCase.enrichAll(candidates);
		actionLogService.record(ActionLogConstants.SOURCING_DRAFT, null,
			result.failures().isEmpty() ? ActionStatus.SUCCESS : ActionStatus.FAILED,
			"초안 생성 — 성공 %d · 실패 %d".formatted(result.drafts().size(), result.failures().size()));

		return ResponseEntity.ok(new CreateDraftsResponse(
			result.drafts().stream().map(DraftResponse::from).toList(),
			result.failures().stream()
				.map(f -> new DraftFailure(f.candidateId(), f.name(), f.reason())).toList()));
	}

	@GetMapping("/drafts")
	public ResponseEntity<List<DraftResponse>> drafts(
		@RequestParam(required = false)
		List<String> status) {
		return ResponseEntity.ok(queryService.drafts(status).stream()
			.map(DraftResponse::from).toList());
	}

	@GetMapping("/drafts/{id}")
	public ResponseEntity<DraftResponse> draft(@PathVariable
	Long id) {
		return ResponseEntity.ok(DraftResponse.from(queryService.requireDraft(id)));
	}

	@PatchMapping("/drafts/{id}")
	public ResponseEntity<DraftResponse> updateDraft(@PathVariable
	Long id,
		@RequestBody
		UpdateDraftRequest request) {
		SourcingQueryService.DraftUpdate update = new SourcingQueryService.DraftUpdate(
			request.baseNameKo(), request.bundleQty(), request.marginRate(), request.costPrice(),
			request.origin(), request.hsCode(), request.barcode(), request.weightG(),
			request.capacity(), request.measureUnit(), request.detailHtml(), request.customsAck(),
			request.marketDrafts() == null ? List.of() : request.marketDrafts().stream()
				.map(m -> new SourcingQueryService.MarketDraftUpdate(
					m.marketType(), m.productName(), m.categoryId(), m.categoryPath(),
					m.salePrice(), m.keywords(), m.enabled()))
				.toList());
		return ResponseEntity.ok(DraftResponse.from(queryService.updateDraft(id, update)));
	}

	@PostMapping("/drafts/{id}/publish")
	public ResponseEntity<PublishDraftResponse> publish(@PathVariable
	Long id) {
		try {
			DraftPublishUseCase.PublishResult result = publishUseCase.publish(id);
			actionLogService.record(ActionLogConstants.SOURCING_PUBLISH, null,
				result.successCount() == result.outcomes().size()
					? ActionStatus.SUCCESS : ActionStatus.FAILED,
				"초안 %d 등록 — 성공 %d/%d".formatted(id, result.successCount(),
					result.outcomes().size()));
			return ResponseEntity.ok(PublishDraftResponse.from(result));
		} catch (RuntimeException e) {
			actionLogService.record(ActionLogConstants.SOURCING_PUBLISH, null, ActionStatus.FAILED,
				"초안 %d 등록 실패: %s".formatted(id, e.getMessage()));
			throw e;
		}
	}

	@GetMapping("/config")
	public ResponseEntity<ConfigResponse> config() {
		return ResponseEntity.ok(ConfigResponse.from(configService.getOrCreate()));
	}

	@PutMapping("/config")
	public ResponseEntity<ConfigResponse> updateConfig(@RequestBody
	ConfigUpdateRequest r) {
		return ResponseEntity.ok(ConfigResponse.from(configService.update(
			r.recommendCount(), r.categories(), r.pagesPerCategory(), r.scoreWeights(),
			r.profitGuardEnabled(), r.targetMarginRate(), r.minMarginPrice(), r.maxPriceRatio(),
			r.couponRate(), r.rejectCooldownDays(), r.excludeSponsored(), r.minReviewCount(),
			r.minRating(), r.scheduleEnabled(), r.scheduleCron())));
	}

	@PostMapping("/customs/sync-banned")
	public ResponseEntity<BannedIngredientSyncService.SyncResult> syncBanned() {
		return ResponseEntity.ok(bannedIngredientSyncService.sync());
	}
}
