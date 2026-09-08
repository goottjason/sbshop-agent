package com.sbshop.agent.core.application.product.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.market.sync.*;
import com.sbshop.agent.core.application.product.batch.ProductSupplierBatchService.*;
import com.sbshop.agent.core.application.product.edit.*;
import com.sbshop.agent.core.application.product.source.*;
import com.sbshop.agent.core.domain.market.client.MarketClientRouter;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.product.ProductRepository;
import com.sbshop.agent.core.domain.product.batch.*;
import com.sbshop.agent.core.domain.product.edit.*;
import com.sbshop.agent.core.domain.product.source.*;
import java.time.Instant;
import java.util.*;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import com.sbshop.agent.core.domain.market.sync.*;
import com.sbshop.agent.core.application.product.ProductMarketSyncService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.sbshop.agent.core.domain.order.enums.MarketType;

/** Short durable claims surround local enqueue/read operations; all child service calls are outside run locks. */
@Component
@Slf4j
@RequiredArgsConstructor
public class ProductSupplierBatchRunner {
	private final ProductSupplierBatchService service;
	private final ProductEditPlanner editPlanner;
	private final ProductSupplierBatchRunRepository runs;
	private final ProductSupplierBatchItemRepository items;
	private final ProductSupplierBatchStageRepository stages;
	private final ProductRepository products;
	private final MarketRegistrationRepository registrations;
	private final ProductChangeTargetRepository targets;
	private final ProductSourceSnapshotRepository snapshots;
	private final ProductSourceReviewRepository sourceReviews;
	private final ProductChangeHistoryRepository histories;
	private final MarketPriceTaskRepository priceTasks;
	private final MarketStockTaskRepository stockTasks;
	private final ProductSourceService source;
	private final ProductSupplierBatchSource sourceEdits;
	private final MarketPriceSyncService prices;
	private final MarketStockSyncService stocks;
	private final MarketClientRouter clients;
	private final ObjectMapper mapper;

	public record Claim(ProductSupplierBatchRun run, ProductSupplierBatchItem item, ProductSupplierBatchStage stage,
		String token) {
	}
	@Builder
	@Getter
	private static class Result {
		private String state, detail, referenceId, sourceSnapshotId, editReviewId, expected, observed;
		private boolean retryable, dbSaved;
		private Long historyId, revision, taskId;
		private Calculation calculation;
		private Instant nextRunAt;
	}

	public void tick() {
		if (TransactionSynchronizationManager.isActualTransactionActive())
			throw new IllegalStateException("배치 하위 작업은 부모 DB transaction 밖에서 실행합니다.");
		for (var run : runs.findByStateInOrderByCreatedAtAsc(List.of("RUNNING", "PAUSING"), PageRequest.of(0, 10)))
			for (int work = 0; work < 32; work++)
				process(run.getId());
	}

	public void process(String id) {
		Claim claim = service.tx().execute(status -> claim(id));
		if (claim == null)
			return;
		Result result;
		try {
			result = execute(claim);
		} catch (Exception failure) {
			String code = failure instanceof SourceEvidenceExpired ? "BATCH_SOURCE_EXPIRED"
				: failure instanceof ProductEditConflictException ? "BATCH_REVIEW_CONFLICT"
					: failure instanceof UnsupportedOperationException ? "BATCH_UNSUPPORTED"
						: failure instanceof IllegalArgumentException ? "BATCH_INVALID_INPUT" : "BATCH_STAGE_ERROR";
			// Exception class and workflow identifiers are sufficient to locate evidence without logging API bodies.
			log.warn("supplier batch action failed run={} item={} stage={} code={} exception={}", id,
				claim.item().getId(), claim.stage().getId(), code, failure.getClass().getName());
			boolean explicit = failure instanceof ProductEditConflictException
				|| failure instanceof UnsupportedOperationException || failure instanceof IllegalArgumentException;
			String message = explicit ? safe(failure.getMessage()) : "처리 중 오류가 발생했습니다. 저장된 작업 기록으로 다시 확인합니다.";
			boolean busy = failure instanceof IllegalArgumentException && message.contains("수집 대기 상품");
			boolean uncertain = !explicit && couldHaveLiveWork(claim) || service.hasLiveChild(claim.stage());
			if (busy || uncertain)
				result = waiting("[" + code + "] " + message + " 기존 접수 결과를 10초 뒤 다시 확인합니다.", 10);
			else
				result = Result.builder()
					.state(failure instanceof ProductEditConflictException
						|| failure instanceof UnsupportedOperationException ? "BLOCKED" : "FAILED")
					.detail("[" + code + "] " + message)
					.retryable(
						failure instanceof SourceEvidenceExpired || !(failure instanceof ProductEditConflictException
							|| failure instanceof UnsupportedOperationException))
					.build();
		}
		Result outcome = result;
		service.tx().executeWithoutResult(status -> finish(claim, outcome));
	}

	private Claim claim(String id) {
		var run = runs.lock(id).orElse(null);
		if (run == null)
			return null;
		Instant now = Instant.now();
		if (run.leased(now))
			return null;
		if (run.getState().equals("PAUSING")) {
			if (service.inFlight(id) == 0)
				run.paused(now);
			return null;
		}
		if (!run.getState().equals("RUNNING"))
			return null;
		ProductSupplierBatchItem item = run.getActiveItemId() == null ? null
			: items.findById(run.getActiveItemId()).orElse(null);
		ProductSupplierBatchStage stage = null;
		if (item == null) {
			var retry = items.findPipeline(id, PageRequest.of(0, 1));
			item = retry.isEmpty()
				? (items.countByBatchIdAndState(id, "RUNNING") < 128
					? items.findFirstByBatchIdAndStateOrderByIdAsc(id, "WAITING").orElse(null) : null)
				: retry.getFirst();
			if (item != null) {
				if (item.getState().equals("WAITING"))
					item.start(now);
				run.activeItem(item.getId());
				seedMarkets(run, item);
			}
		}
		if (item != null) {
			var pipeline = stages.findByItemIdOrderById(item.getId()).stream()
				.filter(s -> !s.getStage().equals("MARKET") && !s.terminal()).toList();
			if (pipeline.isEmpty()) {
				run.activeItem(null);
				item = null;
			} else {
				var first = pipeline.getFirst();
				if (!first.getNextRunAt().isAfter(now))
					stage = first;
			}
		}
		if (stage == null) {
			var market = stages.dueMarkets(id, now, PageRequest.of(0, 1));
			if (!market.isEmpty()) {
				stage = market.getFirst();
				item = items.findById(stage.getItemId()).orElseThrow();
			}
		}
		if (stage == null) {
			service.completeIfFinished(run);
			return null;
		}
		if (stage.getStartedAt() == null) {
			stage.start(now);
			service.record(stage);
		}
		String token = UUID.randomUUID().toString();
		run.claim(token, now);
		return new Claim(run, item, stage, token);
	}

	private Result execute(Claim c) {
		return switch (c.stage().getStage()) {
			case "CRAWL" -> crawl(c);
			case "DB" -> save(c);
			case "MARKET" -> market(c);
			default -> throw new IllegalStateException("알 수 없는 배치 단계");
		};
	}

	private Result crawl(Claim c) {
		var current = products.findById(c.item().getProductId())
			.orElseThrow(() -> new ProductEditConflictException("상품이 없습니다."));
		if (current.isDeleted() || current.getVendor() == null
			|| !current.getVendor().name().equals(c.run().getVendor()))
			throw new ProductEditConflictException("상품의 소싱처가 배치 실행 당시와 다르거나 상품이 삭제되었습니다.");
		if (c.stage().getReferenceId() == null) {
			verifyRetrySourceIdentity(c);
			var collection = source.collect(new ProductSourceService.CollectionRequest(c.stage().getOperationId(),
				List.of(c.item().getProductId())), c.run().getActor());
			verifyRecollectedIdentity(c, collection.items().getFirst());
			return Result.builder().state("RUNNING").detail("소싱 수집 큐 접수 완료").referenceId(collection.id())
				.sourceSnapshotId(collection.items().getFirst().id()).nextRunAt(Instant.now().plusSeconds(2)).build();
		}
		var collection = source.collection(c.stage().getReferenceId(), c.run().getActor());
		if (collection.items().size() != 1)
			throw new ProductEditConflictException("정확한 단건 소싱 수집 결과가 아닙니다.");
		var snapshot = collection.items().getFirst();
		if (!c.run().getVendor().equals(snapshot.vendor()) || !c.item().getProductId().equals(snapshot.productId()))
			throw new ProductEditConflictException("수집 기록의 상품·소싱처가 승인한 배치 대상과 다릅니다.");
		if (Set.of("QUEUED", "COLLECTING").contains(snapshot.state().name()))
			return waiting(snapshot.reason() == null ? "소싱 수집 대기·진행 중" : snapshot.reason(), 2);
		Set<Field> needed = fields(Mode.valueOf(c.run().getMode()));
		Set<Field> available = new HashSet<>();
		for (var field : snapshot.fields())
			if (field.available())
				available.add(Field.valueOf(field.field().name()));
		return collected(c, snapshot, needed, available);
	}

	private Result collected(Claim c, ProductSourceService.Snapshot snapshot, Set<Field> needed, Set<Field> available) {
		// The approved combined mode requires both observations before any product or market change.
		if (!available.containsAll(needed)) {
			boolean unsupportedSource = snapshot.state() == ProductSourceSnapshot.State.UNSUPPORTED
				&& available.isEmpty();
			String detail = needed.stream()
				.map(f -> (f == Field.PRICE ? "가격" : "재고") + (available.contains(f) ? " 확인 완료" : " 수집 실패"))
				.collect(java.util.stream.Collectors.joining(" · "))
				+ (snapshot.reason() == null || snapshot.reason().isBlank() ? "" : " · " + safe(snapshot.reason()));
			if (needed.containsAll(Set.of(Field.PRICE, Field.STOCK)))
				detail += " · 가격과 재고가 모두 확인되지 않아 이 상품은 어느 항목도 적용하지 않았습니다. 수집을 재시도하면 두 항목을 다시 확인합니다.";
			return Result.builder()
				.state(unsupportedSource ? "BLOCKED" : "FAILED")
				.detail(detail).retryable(!unsupportedSource)
				.sourceSnapshotId(snapshot.id()).build();
		}
		return Result.builder().state("SUCCEEDED")
			.detail(needed.stream().map(f -> f == Field.PRICE ? "가격" : "재고")
				.collect(java.util.stream.Collectors.joining("·")) + " 확인 완료 · 시스템 상품 저장 전")
			.sourceSnapshotId(snapshot.id()).build();
	}

	private Result save(Claim c) {
		if (c.item().getSourceSnapshotId() == null)
			throw new ProductEditConflictException("성공한 소싱 수집 기록이 없습니다.");
		verifySourceAge(c);
		if (needsDatabaseReview(c)) {
			var prepared = sourceEdits.review(c.item().getSourceSnapshotId(), fields(Mode.valueOf(c.run().getMode())),
				service.read(c.run().getPolicy(), Policy.class), c.run().getActor());
			if (!Set.of(ProductEditPlanner.State.READY, ProductEditPlanner.State.UNCHANGED)
				.contains(prepared.plan().state()))
				return Result.builder().state("BLOCKED").detail(String.join(" / ", prepared.plan().reasons()))
					.retryable(false).editReviewId(prepared.reviewId()).calculation(prepared.calculation()).build();
			return Result.builder().state("RUNNING").detail("실행 정책과 수집 증거로 DB 변경 검토 보관 완료")
				.referenceId(prepared.reviewId()).editReviewId(prepared.reviewId()).calculation(prepared.calculation())
				.nextRunAt(Instant.now()).build();
		}
		var saved = sourceEdits.commit(c.stage().getReferenceId(), c.run().getActor());
		if (!saved.state().equals("SAVED"))
			return Result.builder().state(saved.state().equals("FAILED") ? "FAILED" : "BLOCKED").detail(saved.detail())
				.retryable(saved.state().equals("FAILED")).build();
		return Result.builder().state("SUCCEEDED").detail(saved.detail()).historyId(saved.historyId())
			.revision(saved.revision()).dbSaved(true).build();
	}

	private static class SourceEvidenceExpired extends ProductEditConflictException {
		SourceEvidenceExpired() {
			super("수집한 지 24시간이 지나 적용을 보류했습니다. 이 저장 단계를 재시도하면 해당 상품만 새로 수집합니다.");
		}
	}

	private void verifySourceAge(Claim c) {
		if (c.stage().getReferenceId() != null
			&& histories.findByReviewIdAndProductId(c.stage().getReferenceId(), c.item().getProductId()).isPresent())
			return;
		var snapshot = snapshots.findById(c.item().getSourceSnapshotId())
			.orElseThrow(() -> new ProductEditConflictException("성공한 수집 기록이 없습니다."));
		verifySnapshotIdentity(c, snapshot);
		if (snapshot.getExpiresAt() != null && !Instant.now().isBefore(snapshot.getExpiresAt()))
			throw new SourceEvidenceExpired();
	}

	private void verifyRetrySourceIdentity(Claim c) {
		if (c.item().getSourceSnapshotId() == null)
			return;
		var snapshot = snapshots.findById(c.item().getSourceSnapshotId())
			.orElseThrow(() -> new ProductEditConflictException("이전 수집 기록을 확인할 수 없습니다."));
		verifySnapshotIdentity(c, snapshot);
	}

	private void verifyRecollectedIdentity(Claim c, ProductSourceService.Snapshot fresh) {
		if (!Objects.equals(fresh.productId(), c.item().getProductId()) || !c.run().getVendor().equals(fresh.vendor()))
			throw new ProductEditConflictException("수집 대상 상품·소싱처가 승인한 배치와 다릅니다.");
		if (c.item().getSourceSnapshotId() == null)
			return;
		var old = snapshots.findById(c.item().getSourceSnapshotId()).orElseThrow();
		if (old.getRevision() != fresh.revision() || !old.getSourceUrl().equals(fresh.sourceUrl())
			|| !old.getVendor().equals(fresh.vendor()))
			throw new ProductEditConflictException("재수집 접수 중 상품 정보가 변경되어 적용을 보류했습니다.");
	}

	private void verifySnapshotIdentity(Claim c, ProductSourceSnapshot snapshot) {
		var product = products.findById(c.item().getProductId())
			.orElseThrow(() -> new ProductEditConflictException("상품이 없습니다."));
		if (product.isDeleted() || product.getRevision() != snapshot.getRevision()
			|| !Objects.equals(product.getSourcingUrl(), snapshot.getSourceUrl()) || product.getVendor() == null
			|| !product.getVendor().name().equals(snapshot.getVendor())
			|| !editPlanner.fingerprint(registrations.findByProductId(product.getId()))
				.equals(snapshot.getConnectionFingerprint()))
			throw new ProductEditConflictException("수집 이후 상품 또는 마켓 연결이 변경되었습니다. 현재 상품으로 새 실행을 검토하세요.");
	}

	private boolean needsDatabaseReview(Claim c) {
		String id = c.stage().getReferenceId();
		if (id == null)
			return true;
		if (histories.findByReviewIdAndProductId(id, c.item().getProductId()).isPresent())
			return false;
		var review = sourceReviews.findById(id)
			.orElseThrow(() -> new ProductEditConflictException("저장 전 검토 기록이 없습니다."));
		if (!review.getActor().equals(c.run().getActor()))
			throw new ProductEditConflictException("저장 전 검토 계정이 배치 실행 계정과 다릅니다.");
		return !Instant.now().isBefore(review.getExpiresAt());
	}

	private Result market(Claim c) {
		// Review, durable child task and ownership pointer commit together. No network calls here.
		// A crash before finish() resumes this exact child rather than creating another request.
		return service.tx().execute(status -> {
			var owned = stages.findById(c.stage().getId()).orElseThrow();
			var current = new Claim(c.run(), c.item(), owned, c.token());
			MarketType market = MarketType.valueOf(owned.getMarket());
			Field field = Field.valueOf(owned.getField());
			if (needsMarketReview(current, field)) {
				verifyMarket(current, market);
				if (field == Field.PRICE) {
					var review = prices.preview(List.of(c.item().getProductId()), Set.of(market), c.run().getActor());
					owned.reference(review.id());
					var one = review.items().getFirst();
					if (!one.state().equals("READY"))
						return Result.builder().state("BLOCKED").detail(one.detail()).referenceId(review.id())
							.expected(text(one.expectedPrice())).build();
				} else {
					var review = stocks.preview(List.of(c.item().getProductId()), Set.of(market), c.run().getActor());
					owned.reference(review.id());
					var one = review.items().getFirst();
					if (!one.state().equals("READY"))
						return Result.builder().state("BLOCKED").detail(one.detail()).referenceId(review.id())
							.expected(text(one.expectedQuantity())).build();
				}
			}
			Result result;
			if (field == Field.PRICE) {
				var review = prices.get(owned.getReferenceId());
				if (!review.actor().equals(c.run().getActor()))
					throw new ProductEditConflictException("가격 검토 작업자가 배치 실행 계정과 다릅니다.");
				if (!review.committed()) {
					verifyMarket(current, market);
					review = prices.commit(review.id(), c.run().getActor());
				}
				var one = review.items().getFirst();
				result = marketResult(one.state(), one.detail(), one.id(), text(one.expectedPrice()),
					text(one.observedPrice()), one.nextRunAt(), "CONFIRMED_PRICE");
			} else {
				var review = stocks.get(owned.getReferenceId(), c.run().getActor());
				if (!review.committed()) {
					verifyMarket(current, market);
					review = stocks.commit(review.id(), c.run().getActor());
				}
				var one = review.items().getFirst();
				result = marketResult(one.state(), one.detail(), one.id(), text(one.expectedQuantity()),
					text(one.observedQuantity()), one.nextRunAt(), "CONFIRMED_QUANTITY");
			}
			result.referenceId = owned.getReferenceId();
			owned.task(result.taskId);
			return result;
		});
	}

	private boolean needsMarketReview(Claim c, Field field) {
		String id = c.stage().getReferenceId();
		if (id == null)
			return true;
		if (field == Field.PRICE) {
			var review = prices.get(id);
			if (!review.actor().equals(c.run().getActor()))
				throw new ProductEditConflictException("가격 검토 계정이 다릅니다.");
			return !review.committed() && !Instant.now().isBefore(review.expiresAt());
		}
		var review = stocks.get(id, c.run().getActor());
		return !review.committed() && !Instant.now().isBefore(review.expiresAt());
	}

	private Result marketResult(String state, String detail, Long task, String expected, String observed, Instant next,
		String success) {
		boolean pending = Set.of("CHECK", "VERIFY").contains(state);
		return Result.builder()
			.state(state.equals(success) ? "SUCCEEDED"
				: pending ? "RUNNING" : Set.of("SKIPPED", "BLOCKED", "STALE").contains(state) ? "BLOCKED" : "FAILED")
			.detail(pending ? (state.equals("CHECK") ? "전송 작업 접수 완료 · " : "전송 후 재조회 중 · ") + detail : detail)
			.retryable(!pending && !state.equals(success) && !state.equals("SKIPPED")).taskId(task)
			.expected(expected).observed(observed)
			.nextRunAt(pending
				? (next == null || next.isBefore(Instant.now().plusSeconds(2)) ? Instant.now().plusSeconds(2) : next)
				: null)
			.build();
	}

	private void verifyMarket(Claim c, MarketType market) {
		var product = products.findById(c.item().getProductId())
			.orElseThrow(() -> new ProductEditConflictException("상품이 없습니다."));
		if (product.isDeleted() || c.item().getSavedRevision() == null
			|| product.getRevision() != c.item().getSavedRevision())
			throw new ProductEditConflictException("배치 저장 이후 상품이 변경되었습니다. 현재 상품으로 새 배치를 검토하세요.");
		String expected;
		try {
			expected = mapper.readTree(c.run().getAccounts()).path(market.name()).asText(null);
		} catch (Exception e) {
			throw new ProductEditConflictException("승인 당시 마켓 계정 기록을 읽을 수 없습니다.");
		}
		if (expected == null || !clients.hasClient(market)
			|| !expected.equals(clients.getClient(market).inspectionAccountReference()))
			throw new ProductEditConflictException("실행 승인 당시 마켓 계정과 현재 설정이 다릅니다. 해당 마켓 전송을 보류합니다.");
	}

	private void finish(Claim c, Result result) {
		var run = runs.lock(c.run().getId()).orElseThrow();
		if (!run.owns(c.token()))
			return;
		var item = items.findById(c.item().getId()).orElseThrow();
		var stage = stages.findById(c.stage().getId()).orElseThrow();
		if (result.referenceId != null)
			stage.reference(result.referenceId);
		if (result.sourceSnapshotId != null)
			item.source(result.sourceSnapshotId);
		if (result.editReviewId != null)
			item.reviewed(result.editReviewId, result.calculation == null ? null : service.json(result.calculation));
		if (result.taskId != null)
			stage.task(result.taskId);
		if (result.expected != null || result.observed != null)
			stage.values(result.expected, result.observed);
		if (result.state.equals("RUNNING"))
			stage.waitUntil(result.detail, result.nextRunAt == null ? Instant.now().plusSeconds(2) : result.nextRunAt);
		else
			stage.outcome(result.state, result.detail, result.retryable, Instant.now());
		if (result.dbSaved) {
			item.saved(result.revision, result.historyId);
			attachTargets(item);
			run.activeItem(null);
		}
		if (stage.getStage().equals("MARKET"))
			targetOutcome(stage);
		if (Set.of("FAILED", "BLOCKED").contains(stage.getState()) && !stage.getStage().equals("MARKET")) {
			for (var downstream : stages.findByItemIdOrderById(item.getId()))
				if (!downstream.terminal() && !downstream.getId().equals(stage.getId())) {
					downstream.outcome("SKIPPED", "선행 단계 실패·보류로 실행하지 않았습니다.", false, Instant.now());
					service.record(downstream);
				}
			run.activeItem(null);
		}
		if (!result.state.equals("RUNNING") || result.referenceId != null || result.editReviewId != null)
			service.record(stage);
		service.settle(run, item);
		run.release(Instant.now());
		if (run.getState().equals("PAUSING") && service.inFlight(run.getId()) == 0)
			run.paused(Instant.now());
	}

	private void seedMarkets(ProductSupplierBatchRun run, ProductSupplierBatchItem item) {
		var existing = stages.findByItemIdOrderById(item.getId());
		if (existing.stream().anyMatch(s -> s.getStage().equals("MARKET")))
			return;
		Set<String> selected = new HashSet<>(service.readStrings(run.getMarkets()));
		var links = registrations.findByProductId(item.getProductId());
		for (MarketType market : new TreeSet<>(ProductSupplierBatchService.ALLOWED_MARKETS))
			for (Field field : fields(Mode.valueOf(run.getMode()))) {
				var stage = new ProductSupplierBatchStage(run.getId(), item.getId(), "MARKET", market.name(),
					field.name(), Instant.now());
				boolean linked = links.stream().anyMatch(
					r -> r.getMarketType() == market && !r.getConnectionState().detached() && r.hasActiveConnections());
				if (!selected.contains(market.name()))
					stage.outcome("SKIPPED", "이번 배치에서 선택하지 않은 마켓입니다. DB 변경의 미반영 이력은 유지합니다.", false, Instant.now());
				else if (!linked)
					stage.outcome("SKIPPED", "현재 연결된 마켓 상품이 없습니다. 신규 등록은 실행하지 않습니다.", false, Instant.now());
				stages.saveAndFlush(stage);
				if (stage.terminal())
					service.record(stage);
			}
	}

	private void attachTargets(ProductSupplierBatchItem item) {
		if (item.getHistoryId() == null)
			return;
		var stageList = stages.findByItemIdOrderById(item.getId());
		for (var target : targets.findByHistoryIdIn(List.of(item.getHistoryId()))) {
			String field;
			try {
				var changes = mapper.readTree(target.getSnapshot()).path("changes");
				boolean stock = false;
				for (var change : changes)
					if (Set.of("stockStatus", "salesQuantity").contains(change.path("field").asText()))
						stock = true;
				field = stock ? "STOCK" : "PRICE";
			} catch (Exception error) {
				continue;
			}
			var stage = stageList.stream().filter(s -> s.getStage().equals("MARKET")
				&& s.getMarket().equals(target.getMarket()) && s.getField().equals(field)).findFirst().orElse(null);
			if (stage == null) {
				stage = stages.saveAndFlush(new ProductSupplierBatchStage(item.getBatchId(), item.getId(), "MARKET",
					target.getMarket(), field, Instant.now()));
				stage.outcome("SKIPPED", "이번 배치에서 선택하지 않은 마켓·필드입니다. DB 변경의 미반영 이력은 유지합니다.", false, Instant.now());
				service.record(stage);
			}
			stage.target(target.getId());
		}
	}

	private void targetOutcome(ProductSupplierBatchStage stage) {
		if (stage.getTargetId() == null)
			return;
		var target = targets.findById(stage.getTargetId()).orElse(null);
		if (target == null || !target.isBatchManaged()
			|| !Set.of("BATCH_MANAGED", "DISPATCHED", "ACTION_REQUIRED").contains(target.getState()))
			return;
		boolean price = stage.getField().equals("PRICE");
		// Child queue finish owns proof and revision reconciliation. Never copy a late result over newer evidence.
		if (stage.getTaskId() != null) {
			boolean exact;
			if (price) {
				var task = priceTasks.findById(stage.getTaskId()).orElse(null);
				exact = task != null && task.getReviewId().equals(stage.getReferenceId())
					&& task.getProductId().equals(target.getProductId())
					&& task.getProductRevision() == target.getProductRevision()
					&& task.getMarket().equals(target.getMarket())
					&& Objects.equals(task.getRegistrationId(), target.getRegistrationId());
			} else {
				var task = stockTasks.findById(stage.getTaskId()).orElse(null);
				exact = task != null && task.getReviewId().equals(stage.getReferenceId())
					&& task.getProductId().equals(target.getProductId())
					&& task.getProductRevision() == target.getProductRevision()
					&& task.getMarket().equals(target.getMarket())
					&& Objects.equals(task.getRegistrationId(), target.getRegistrationId());
			}
			if (!exact)
				return;
			Long linked = price ? target.getPriceTaskId() : target.getStockTaskId();
			if (linked != null && !linked.equals(stage.getTaskId()))
				return;
			if (stage.getState().equals("RUNNING")) {
				if (price)
					target.dispatchedToPrice(stage.getTaskId());
				else
					target.dispatchedToStock(stage.getTaskId());
			}
		}
		if (Set.of("FAILED", "BLOCKED").contains(stage.getState())) {
			if (price)
				target.priceOutcome("ACTION_REQUIRED");
			else
				target.stockOutcome("ACTION_REQUIRED");
		}
	}

	private static Set<Field> fields(Mode mode) {
		Set<Field> fields = new TreeSet<>();
		if (mode.price())
			fields.add(Field.PRICE);
		if (mode.stock())
			fields.add(Field.STOCK);
		return fields;
	}

	private static boolean couldHaveLiveWork(Claim claim) {
		return claim.stage().getStage().equals("CRAWL")
			|| claim.stage().getStage().equals("MARKET") && claim.stage().getReferenceId() != null;
	}

	private static Result waiting(String detail, long seconds) {
		return Result.builder().state("RUNNING").detail(detail).nextRunAt(Instant.now().plusSeconds(seconds)).build();
	}

	private static String safe(String value) {
		return value == null || value.isBlank() ? "저장된 단계 기록을 확인하세요."
			: ProductMarketSyncService.sanitizeMarketMessage(value);
	}

	private static String text(Object value) {
		return value == null ? null : value.toString();
	}
}
