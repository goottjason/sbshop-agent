package com.sbshop.agent.core.application.product.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.pricing.VendorPricePolicyService;
import com.sbshop.agent.core.application.product.content.ProductContentUrls;
import com.sbshop.agent.core.application.product.edit.*;
import com.sbshop.agent.core.application.product.source.ProductSourceData;
import com.sbshop.agent.core.application.product.source.ProductSourceService;
import com.sbshop.agent.core.domain.common.exception.ResourceNotFoundException;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.batch.*;
import com.sbshop.agent.core.domain.product.content.ProductContentLaneRepository;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class ProductSupplierBatchService {
	public static final Set<MarketType> ALLOWED_MARKETS = Set.of(MarketType.COUPANG, MarketType.ELEVEN_STREET,
		MarketType.SMART_STORE, MarketType.CAFE24);
	private final ProductSupplierBatchRunRepository runs;
	private final ProductSupplierBatchItemRepository itemRows;
	private final ProductSupplierBatchStageRepository stageRows;
	private final ProductSupplierBatchAttemptRepository attempts;
	private final ProductSupplierBatchRetryRepository retries;
	private final ProductEditPlanner editPlanner;
	private final com.sbshop.agent.core.domain.product.source.ProductSourceSnapshotRepository sourceSnapshots;
	private final com.sbshop.agent.core.domain.product.edit.ProductChangeHistoryRepository changeHistories;
	private final ProductContentLaneRepository lanes;
	private final VendorPricePolicyService vendorPolicies;
	private final com.sbshop.agent.core.domain.market.client.MarketClientRouter clients;
	private final ObjectMapper mapper;
	private final EntityManager em;
	private final JdbcTemplate jdbc;
	private final PlatformTransactionManager transactions;

	public enum Mode {
		PRICE_STOCK, PRICE, STOCK;

		public boolean price() {
			return this != STOCK;
		}

		public boolean stock() {
			return this != PRICE;
		}
	}
	public enum Step {
		CRAWL, DB, MARKET
	}
	public enum Field {
		PRICE, STOCK
	}
	public record Policy(BigDecimal marginRate, BigDecimal couponRate, BigDecimal minMarginPrice) {
	}
	public record CreateRequest(String requestId, VendorType vendor, Mode mode, BigDecimal marginRate,
		BigDecimal couponRate, BigDecimal minMarginPrice, Set<MarketType> markets) {
		public CreateRequest {
			ProductSourceService.requireUuid(requestId);
			if (vendor == null || mode == null)
				throw new IllegalArgumentException("소싱처와 실행 모드를 선택하세요.");
			if (markets == null || markets.isEmpty() || markets.stream().anyMatch(Objects::isNull)
				|| !ALLOWED_MARKETS.containsAll(markets))
				throw new IllegalArgumentException("반영할 마켓을 선택하세요.");
			markets = Collections.unmodifiableSet(new TreeSet<>(markets));
			if (mode.price()) {
				number(marginRate, "마진율", new BigDecimal("99.99"));
				number(couponRate, "소싱 구매 쿠폰율", new BigDecimal("100"));
				number(minMarginPrice, "최소마진", new BigDecimal("9999999999999.99"));
			}
		}
	}
	public record RetryRequest(String requestId, Long itemId, Step stage, MarketType market, Field field) {
		public RetryRequest {
			ProductSourceService.requireUuid(requestId);
			if (itemId != null && itemId < 1)
				throw new IllegalArgumentException("상품 작업 번호가 올바르지 않습니다.");
			if (market == MarketType.UNKNOWN)
				throw new IllegalArgumentException("마켓을 확인하세요.");
			if (stage != null && stage != Step.MARKET && (market != null || field != null))
				throw new IllegalArgumentException("수집·DB 단계 재시도에는 마켓이나 전송 필드를 지정하지 않습니다.");
		}
	}
	public record VendorOption(String vendor, String label, long productCount, Policy defaults) {
	}
	public record MarketOption(String market, String label) {
	}
	public record Options(List<VendorOption> vendors, List<MarketOption> markets, Set<String> supportedVendors) {
	}
	public record View(String id, String vendor, String mode, String actor, String state, Instant createdAt,
		Instant updatedAt, Instant finishedAt, Policy policy, List<String> markets, int total, int processed,
		int succeeded, int failed, int blocked, int pending, int inFlight, Instant nextRunAt,
		int dbOnly, Map<String, Long> stageProgress) {
		public View(String id, String vendor, String mode, String actor, String state, Instant createdAt,
			Instant updatedAt, Instant finishedAt, Policy policy, List<String> markets, int total, int processed,
			int succeeded, int failed, int blocked, int pending, int inFlight, Instant nextRunAt) {
			this(id, vendor, mode, actor, state, createdAt, updatedAt, finishedAt, policy, markets, total, processed,
				succeeded, failed, blocked, pending, inFlight, nextRunAt, 0, Map.of());
		}
	}
	public record Stage(Long id, String stage, String market, String field, String state, String detail,
		boolean retryable, int attempts, String referenceId, String expected, String observed, Instant startedAt,
		Instant finishedAt, Instant nextRunAt) {
	}
	public record Item(Long id, Long productId, String sbCode, String productName, String thumbnailUrl, String state,
		String detail, int attempts, String sourceSnapshotId, String editReviewId, List<Stage> stages) {
	}
	public record Attempt(Long id, String stage, String market, String field, String state, String detail,
		Instant recordedAt) {
	}
	public record Calculation(BigDecimal costPrice, BigDecimal exchangeRate, Policy policy,
		ProductSourceData.PricingEvidence pricingEvidence, List<ProductEditPlanner.Price> prices,
		List<String> notices, BigDecimal appliedCouponRate) {
		public Calculation(BigDecimal costPrice, BigDecimal exchangeRate, Policy policy,
			ProductSourceData.PricingEvidence pricingEvidence, List<ProductEditPlanner.Price> prices,
			List<String> notices) {
			this(costPrice, exchangeRate, policy, pricingEvidence, prices, notices, null);
		}
	}
	public record ItemDetail(Item item, List<Attempt> history, Calculation priceCalculation, String sourceUrl,
		BatchSourceDiagnosis sourceDiagnosis, boolean productDeleted, boolean latestSourceDiagnosis) {
	}
	public record RetryOptions(Map<String, Long> retryableStageCounts, long retryableProducts, long blockedStageCount) {
	}

	@Transactional(readOnly = true)
	public RetryOptions retryOptions(String id) {
		run(id);
		Map<String, Long> counts = new LinkedHashMap<>();
		for (String key : List.of("CRAWL", "DB", "MARKET"))
			counts.put(key, 0L);
		for (Object[] row : em.createQuery(
			"select s.stage,count(s) from ProductSupplierBatchStage s where s.batchId=:id and s.retryable=true and s.state in ('FAILED','BLOCKED') group by s.stage",
			Object[].class).setParameter("id", id).getResultList())
			counts.put((String)row[0], ((Number)row[1]).longValue());
		long products = em.createQuery(
			"select count(distinct s.itemId) from ProductSupplierBatchStage s where s.batchId=:id and s.retryable=true and s.state in ('FAILED','BLOCKED')",
			Long.class).setParameter("id", id).getSingleResult();
		long blocked = em.createQuery(
			"select count(s) from ProductSupplierBatchStage s where s.batchId=:id and s.state in ('FAILED','BLOCKED') and s.retryable=false",
			Long.class).setParameter("id", id).getSingleResult();
		return new RetryOptions(counts, products, blocked);
	}

	@Transactional(readOnly = true)
	public Options options() {
		Map<String, Long> counts = new HashMap<>();
		for (Object[] row : em.createQuery(
			"select p.sourcingInfo.vendor,count(p) from Product p where p.deletedAt is null group by p.sourcingInfo.vendor",
			Object[].class).getResultList())
			if (row[0] != null)
				counts.put(row[0].toString(), ((Number)row[1]).longValue());
		var vendors = Arrays.stream(VendorType.values()).map(v -> {
			var p = vendorPolicies.find(v).orElse(null);
			return new VendorOption(v.name(), vendorLabel(v), counts.getOrDefault(v.name(), 0L),
				p == null ? new Policy(null, null, null)
					: new Policy(p.getMarginRate(), p.getCouponRate(), p.getMinMarginPrice()));
		}).toList();
		var markets = Arrays.stream(MarketType.values()).filter(ALLOWED_MARKETS::contains)
			.map(m -> new MarketOption(m.name(), m == MarketType.SMART_STORE ? "스마트스토어" : m.getLabel())).toList();
		return new Options(vendors, markets, Arrays.stream(VendorType.values()).filter(ProductContentUrls::supports)
			.map(Enum::name).collect(Collectors.toCollection(TreeSet::new)));
	}

	public View create(CreateRequest request, String actor) {
		requireActor(actor);
		String payload = json(request);
		String id = tx().execute(s -> {
			lanes.findLocked("PRICE_STOCK").orElseThrow(() -> new IllegalStateException("소싱 배치 DB 준비가 필요합니다."));
			var existing = runs.findByActorAndRequestId(actor, request.requestId());
			if (existing.isPresent()) {
				if (!read(existing.get().getRequestPayload(), CreateRequest.class).equals(request))
					throw new ProductEditConflictException("같은 요청 ID에는 같은 배치 정책과 대상만 사용할 수 있습니다.");
				return existing.get().getId();
			}
			if (!ProductContentUrls.supports(request.vendor()))
				throw new IllegalArgumentException("해당 소싱처의 가격·재고 계약이 확인되지 않았습니다.");
			if (runs.existsByVendorAndStateIn(request.vendor().name(), List.of("RUNNING", "PAUSING", "PAUSED")))
				throw new ProductEditConflictException("이 소싱처에는 진행 중이거나 일시정지된 배치가 있습니다. 기존 배치를 확인하세요.");
			var products = em.createQuery(
				"select p.id,p.sbCode,p.productName,p.imageInfo.hostedImages from Product p where p.deletedAt is null and p.sourcingInfo.vendor=:vendor order by p.id",
				Object[].class).setParameter("vendor", request.vendor()).getResultList();
			Instant now = Instant.now();
			var run = runs.saveAndFlush(new ProductSupplierBatchRun(UUID.randomUUID().toString(), request.requestId(),
				actor, request.vendor().name(), request.mode().name(), payload,
				json(new Policy(request.marginRate(), request.couponRate(), request.minMarginPrice())),
				json(request.markets().stream().map(Enum::name).toList()), products.size(), now));
			Map<String, String> accounts = new TreeMap<>();
			for (MarketType market : request.markets())
				accounts.put(market.name(),
					clients.hasClient(market) ? clients.getClient(market).inspectionAccountReference() : null);
			run.accounts(json(accounts));
			for (Object[] p : products) {
				String thumbnail = p[3] instanceof List<?> images && !images.isEmpty()
					? String.valueOf(images.getFirst()) : null;
				var item = itemRows.save(new ProductSupplierBatchItem(run.getId(), ((Number)p[0]).longValue(),
					(String)p[1], (String)p[2], thumbnail, now));
				stageRows.save(new ProductSupplierBatchStage(run.getId(), item.getId(), "CRAWL", null, null, now));
				stageRows.save(new ProductSupplierBatchStage(run.getId(), item.getId(), "DB", null, null, now));
			}
			return run.getId();
		});
		return get(id);
	}

	@Transactional(readOnly = true)
	public Page<View> recent(int page, int size) {
		return runs.findAllByOrderByCreatedAtDesc(page(page, size, 50)).map(this::view);
	}

	@Transactional(readOnly = true)
	public View get(String id) {
		return view(run(id));
	}

	@Transactional(readOnly = true)
	public Page<Item> items(String id, int page, int size, String keyword, String filter) {
		run(id);
		if (!Set.of("ALL", "FAILED", "BLOCKED", "PENDING", "SUCCEEDED", "DB_ONLY").contains(filter))
			throw new IllegalArgumentException("상품 결과 필터를 확인하세요.");
		String word = keyword == null ? "" : keyword.strip().toLowerCase(Locale.ROOT);
		if (word.length() > 100)
			throw new IllegalArgumentException("검색어는 100자 이내로 입력하세요.");
		var rows = itemRows.page(id, filter, word, page(page, size, 100));
		Map<Long, List<ProductSupplierBatchStage>> stages = rows.isEmpty() ? Map.of()
			: stageRows
				.findByItemIdInOrderById(rows.getContent().stream().map(ProductSupplierBatchItem::getId).toList())
				.stream().collect(Collectors.groupingBy(ProductSupplierBatchStage::getItemId));
		return rows.map(item -> item(item, stages.getOrDefault(item.getId(), List.of())));
	}

	@Transactional(readOnly = true)
	public ItemDetail detail(String id, Long itemId) {
		run(id);
		var item = ownedItem(id, itemId);
		var history = attempts.findByItemIdOrderByIdDesc(itemId, PageRequest.of(0, 100)).stream()
			.map(a -> new Attempt(a.getId(), a.getStage(), empty(a.getMarket()), empty(a.getField()), a.getState(),
				a.getDetail(), a.getRecordedAt()))
			.toList();
		var snapshot = item.getSourceSnapshotId() == null ? null
			: sourceSnapshots.findById(item.getSourceSnapshotId()).orElse(null);
		var product = em.find(com.sbshop.agent.core.domain.product.Product.class, item.getProductId());
		String sourceUrl = snapshot != null ? snapshot.getSourceUrl()
			: product == null ? null : product.getSourcingUrl();
		var diagnosisSnapshot = snapshot;
		if (snapshot != null
			&& (snapshot.getState() == com.sbshop.agent.core.domain.product.source.ProductSourceSnapshot.State.FAILED
				|| snapshot
					.getState() == com.sbshop.agent.core.domain.product.source.ProductSourceSnapshot.State.PARTIAL)) {
			var latest = sourceSnapshots.findFirstByProductIdOrderByRequestedAtDescIdDesc(item.getProductId())
				.orElse(null);
			if (latest != null && latest.getRequestedAt().isAfter(snapshot.getRequestedAt())
				&& Objects.equals(latest.getSourceUrl(), snapshot.getSourceUrl())
				&& latest.getState() == com.sbshop.agent.core.domain.product.source.ProductSourceSnapshot.State.FAILED
				&& latest.getReason() != null && (latest.getReason().startsWith("[SOURCE_DISCONTINUED]")
					|| latest.getReason().startsWith("[SOURCE_PRICE_ZERO]")))
				diagnosisSnapshot = latest;
		}
		return new ItemDetail(item(item, stageRows.findByItemIdOrderById(itemId)), history,
			item.getCalculation() == null ? null : read(item.getCalculation(), Calculation.class), sourceUrl,
			BatchSourceDiagnosis.from(diagnosisSnapshot, mapper), product == null || product.isDeleted(),
			diagnosisSnapshot != snapshot);
	}

	public View pause(String id, String actor) {
		tx().executeWithoutResult(s -> {
			var run = ownedLocked(id, actor);
			run.pause(Instant.now());
			if (!run.leased(Instant.now()) && inFlight(id) == 0 && run.getState().equals("PAUSING"))
				run.paused(Instant.now());
		});
		return get(id);
	}

	public View resume(String id, String actor) {
		tx().executeWithoutResult(s -> {
			var run = ownedLocked(id, actor);
			run.resume(Instant.now());
		});
		return get(id);
	}

	public View retry(String id, RetryRequest request, String actor) {
		tx().executeWithoutResult(s -> {
			lanes.findLocked("PRICE_STOCK").orElseThrow(() -> new IllegalStateException("소싱 배치 DB 준비가 필요합니다."));
			var run = ownedLocked(id, actor);
			var receipt = retries.findByBatchIdAndRequestId(id, request.requestId());
			if (receipt.isPresent()) {
				if (!read(receipt.get().getPayload(), RetryRequest.class).equals(request))
					throw new ProductEditConflictException("같은 재시도 ID의 범위를 변경할 수 없습니다.");
				return;
			}
			if (request.itemId() != null)
				ownedItem(id, request.itemId());
			var failed = stageRows
				.retryable(id, request.itemId(), request.stage() == null ? "" : request.stage().name(),
					request.market() == null ? "" : request.market().name(),
					request.field() == null ? "" : request.field().name())
				.stream().filter(stage -> !hasLiveChild(stage)).toList();
			if (!failed.isEmpty() && run.getState().equals("COMPLETED")
				&& runs.existsByVendorAndStateIn(run.getVendor(), List.of("RUNNING", "PAUSING", "PAUSED")))
				throw new ProductEditConflictException("같은 소싱처의 다른 배치가 실행 중이므로 이전 배치를 재시도할 수 없습니다.");
			Instant now = Instant.now();
			Set<Long> restarted = new HashSet<>();
			for (var stage : failed) {
				var item = ownedItem(id, stage.getItemId());
				if (restarted.contains(item.getId()))
					continue;
				if (stage.getStage().equals("CRAWL")) {
					var pipeline = stageRows.findByItemIdOrderById(item.getId());
					if (pipeline.stream().anyMatch(this::hasLiveChild))
						throw new ProductEditConflictException("이 상품의 기존 작업이 진행 중입니다. 완료 후 수집을 재시도하세요.");
					// Keep immutable snapshots/reviews/attempts; detach only this item's old execution pointers.
					item.source(null);
					item.reviewed(null, null);
					item.saved(null, null);
					var selected = new HashSet<>(readStrings(run.getMarkets()));
					var links = em.createQuery("select r from MarketRegistration r where r.productId=:id",
						com.sbshop.agent.core.domain.market.MarketRegistration.class)
						.setParameter("id", item.getProductId()).getResultList();
					for (var step : pipeline) {
						record(step);
						step.retry(now);
						step.reference(null);
						step.task(null);
						step.target(null);
						step.values(null, null);
						if (step.getStage().equals("MARKET")) {
							boolean linked = links.stream()
								.anyMatch(r -> r.getMarketType().name().equals(step.getMarket())
									&& !r.getConnectionState().detached() && r.hasActiveConnections());
							if (!selected.contains(step.getMarket()))
								step.outcome("SKIPPED", "이번 배치에서 선택하지 않은 마켓입니다.", false, now);
							else if (!linked)
								step.outcome("SKIPPED", "현재 연결된 마켓 상품이 없습니다. 신규 등록은 실행하지 않습니다.", false, now);
						}
						record(step);
					}
					item.retry(now);
					restarted.add(item.getId());
					continue;
				}
				boolean expired = expiredUnsavedSource(stage, item, now);
				stage.retry(now);
				if (expired) {
					stage.reference(null);
					item.reviewed(null, null);
					for (var prerequisite : stageRows.findByItemIdOrderById(item.getId()))
						if (prerequisite.getStage().equals("CRAWL")) {
							prerequisite.retry(now);
							record(prerequisite);
						}
				}
				record(stage);
				item.retry(now);
				if (!stage.getStage().equals("MARKET")) {
					for (var downstream : stageRows.findByItemIdOrderById(item.getId()))
						if (downstream.getState().equals("SKIPPED") && downstream.getDetail().startsWith("선행 단계"))
							downstream.unblock(now);
				}
			}
			if (!failed.isEmpty()) {
				boolean completed = run.getState().equals("COMPLETED");
				run.reopen(now);
				if (completed)
					run.resume(now);
			}
			retries.save(new ProductSupplierBatchRetry(id, request.requestId(), actor, json(request), now));
		});
		return get(id);
	}

	View view(ProductSupplierBatchRun r) {
		Map<String, Integer> counts = new HashMap<>();
		for (Object[] c : itemRows.counts(r.getId()))
			counts.put((String)c[0], ((Number)c[1]).intValue());
		int succeeded = counts.getOrDefault("SUCCEEDED", 0), failed = counts.getOrDefault("FAILED", 0),
			blocked = counts.getOrDefault("BLOCKED", 0), skipped = counts.getOrDefault("SKIPPED", 0);
		int processed = succeeded + failed + blocked + skipped;
		int dbOnly = em.createQuery(
			"select count(i) from ProductSupplierBatchItem i where i.batchId=:id and i.state='SUCCEEDED' and not exists(select s.id from ProductSupplierBatchStage s where s.itemId=i.id and s.stage='MARKET' and s.state in ('SUCCEEDED','UNCHANGED'))",
			Long.class)
			.setParameter("id", r.getId()).getSingleResult().intValue();
		Map<String, Long> progress = new LinkedHashMap<>();
		for (Object[] row : em.createQuery(
			"select s.stage,s.state,count(s) from ProductSupplierBatchStage s where s.batchId=:id group by s.stage,s.state",
			Object[].class).setParameter("id", r.getId()).getResultList())
			progress.put(row[0] + "_" + row[1], ((Number)row[2]).longValue());
		succeeded -= dbOnly;
		return new View(r.getId(), r.getVendor(), r.getMode(), r.getActor(), r.getState(), r.getCreatedAt(),
			r.getUpdatedAt(), r.getFinishedAt(), read(r.getPolicy(), Policy.class), readStrings(r.getMarkets()),
			r.getTotal(), processed, succeeded, failed, blocked, r.getTotal() - processed, inFlight(r.getId()),
			stageRows.nextRunAt(r.getId()), dbOnly, progress);
	}

	Item item(ProductSupplierBatchItem i, List<ProductSupplierBatchStage> stages) {
		boolean dbOnly = i.getState().equals("SUCCEEDED") && stages.stream()
			.noneMatch(stage -> stage.getStage().equals("MARKET") && stage.successful());
		return new Item(i.getId(), i.getProductId(), i.getSbCode(), i.getProductName(), i.getThumbnailUrl(),
			dbOnly ? "SKIPPED" : i.getState(), dbOnly ? "SB 저장 완료 · 마켓 대상 없음" : i.getDetail(), i.getAttempts(),
			i.getSourceSnapshotId(), i.getEditReviewId(),
			stages.stream()
				.map(s -> new Stage(s.getId(), s.getStage(), empty(s.getMarket()), empty(s.getField()), s.getState(),
					s.getDetail(), s.isRetryable(), s.getAttempts(), s.getReferenceId(), empty(s.getExpected()),
					empty(s.getObserved()), s.getStartedAt(), s.getFinishedAt(), s.getNextRunAt()))
				.toList());
	}

	void record(ProductSupplierBatchStage stage) {
		attempts.save(new ProductSupplierBatchAttempt(stage, Instant.now()));
	}

	void settle(ProductSupplierBatchRun run, ProductSupplierBatchItem item) {
		var rows = stageRows.findByItemIdOrderById(item.getId());
		if (rows.stream().anyMatch(s -> !s.terminal())) {
			item.outcome("RUNNING", "단계별 작업 진행 중", Instant.now());
			return;
		}
		String state = rows.stream().anyMatch(s -> s.getState().equals("FAILED")) ? "FAILED"
			: rows.stream().anyMatch(s -> s.getState().equals("BLOCKED")) ? "BLOCKED"
				: rows.stream().anyMatch(ProductSupplierBatchStage::successful) ? "SUCCEEDED" : "SKIPPED";
		item.outcome(state, state.equals("SUCCEEDED") ? "모든 필요한 단계 처리 완료" : "실패·보류 사유를 확인하세요.", Instant.now());
		if (Objects.equals(run.getActiveItemId(), item.getId()))
			run.activeItem(null);
		em.flush();
		completeIfFinished(run);
	}

	void completeIfFinished(ProductSupplierBatchRun run) {
		long pending = itemRows.counts(run.getId()).stream().filter(c -> Set.of("WAITING", "RUNNING").contains(c[0]))
			.mapToLong(c -> ((Number)c[1]).longValue()).sum();
		if (pending == 0)
			run.complete(Instant.now());
	}

	boolean expiredUnsavedSource(ProductSupplierBatchStage stage, ProductSupplierBatchItem item, Instant now) {
		if (!stage.getStage().equals("DB") || item.getSourceSnapshotId() == null || item.getHistoryId() != null)
			return false;
		if (stage.getReferenceId() != null
			&& changeHistories.findByReviewIdAndProductId(stage.getReferenceId(), item.getProductId()).isPresent())
			return false;
		var expired = sourceSnapshots.findById(item.getSourceSnapshotId())
			.filter(s -> s.getExpiresAt() != null && !now.isBefore(s.getExpiresAt()));
		if (expired.isEmpty())
			return false;
		var snapshot = expired.get();
		var product = em.find(com.sbshop.agent.core.domain.product.Product.class, item.getProductId());
		var links = em
			.createQuery("select r from MarketRegistration r where r.productId=:id",
				com.sbshop.agent.core.domain.market.MarketRegistration.class)
			.setParameter("id", item.getProductId()).getResultList();
		if (product == null || product.isDeleted() || product.getRevision() != snapshot.getRevision()
			|| !Objects.equals(product.getSourcingUrl(), snapshot.getSourceUrl()) || product.getVendor() == null
			|| !product.getVendor().name().equals(snapshot.getVendor())
			|| !editPlanner.fingerprint(links).equals(snapshot.getConnectionFingerprint()))
			throw new ProductEditConflictException(
				"수집 이후 상품 또는 마켓 연결이 변경되어 기존 배치를 재수집으로 이어갈 수 없습니다. 현재 상품으로 새 실행을 검토하세요.");
		return true;
	}

	boolean hasLiveChild(ProductSupplierBatchStage stage) {
		if (stage.getReferenceId() == null)
			return false;
		String sql;
		if (stage.getStage().equals("CRAWL"))
			sql = "select count(*) from sb_product_source_snapshot where collection_id=? and state in ('QUEUED','COLLECTING')";
		else if (stage.getStage().equals("MARKET"))
			sql = "select count(*) from "
				+ (stage.getField().equals("PRICE") ? "sb_market_price_task" : "sb_market_stock_task")
				+ " where review_id=? and state in ('CHECK','VERIFY')";
		else
			return false;
		Integer count = jdbc.queryForObject(sql, Integer.class, stage.getReferenceId());
		return count != null && count > 0;
	}

	int inFlight(String id) {
		Integer count = jdbc.queryForObject(
			"SELECT (SELECT COUNT(*) FROM sb_product_source_snapshot p JOIN sb_product_source_collection c ON c.id=p.collection_id JOIN sb_supplier_batch_stage s ON s.operation_id=c.request_id AND s.stage='CRAWL' WHERE s.batch_id=? AND p.state='COLLECTING')+(SELECT COUNT(*) FROM sb_market_price_task t JOIN sb_supplier_batch_stage s ON s.reference_id=t.review_id AND s.stage='MARKET' AND s.field='PRICE' WHERE s.batch_id=? AND t.lease_until>CURRENT_TIMESTAMP AND t.lease_token IS NOT NULL)+(SELECT COUNT(*) FROM sb_market_stock_task t JOIN sb_supplier_batch_stage s ON s.reference_id=t.review_id AND s.stage='MARKET' AND s.field='STOCK' WHERE s.batch_id=? AND t.lease_until>CURRENT_TIMESTAMP AND t.lease_token IS NOT NULL)",
			Integer.class, id, id, id);
		return count == null ? 0 : count;
	}

	ProductSupplierBatchRun run(String id) {
		return runs.findById(id).orElseThrow(() -> new ResourceNotFoundException("배치 실행 기록을 찾을 수 없습니다."));
	}

	ProductSupplierBatchItem ownedItem(String id, Long itemId) {
		return itemRows.findById(itemId).filter(i -> i.getBatchId().equals(id))
			.orElseThrow(() -> new ResourceNotFoundException("배치 상품 기록을 찾을 수 없습니다."));
	}

	ProductSupplierBatchRun ownedLocked(String id, String actor) {
		requireActor(actor);
		var r = runs.lock(id).orElseThrow(() -> new ResourceNotFoundException("배치 실행 기록을 찾을 수 없습니다."));
		if (!r.getActor().equals(actor))
			throw new ProductEditConflictException("배치를 실행한 계정으로 제어하세요.");
		return r;
	}

	TransactionTemplate tx() {
		var t = new TransactionTemplate(transactions);
		t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		return t;
	}

	String json(Object value) {
		try {
			return mapper.writeValueAsString(value);
		} catch (Exception e) {
			throw new IllegalStateException("배치 기록 저장 형식 오류", e);
		}
	}

	<T> T read(String value, Class<T> type) {
		try {
			return mapper.readValue(value, type);
		} catch (Exception e) {
			throw new IllegalStateException("배치 기록 읽기 오류", e);
		}
	}

	List<String> readStrings(String value) {
		try {
			return mapper.readValue(value, mapper.getTypeFactory().constructCollectionType(List.class, String.class));
		} catch (Exception e) {
			throw new IllegalStateException("배치 마켓 기록 읽기 오류", e);
		}
	}

	static String empty(String value) {
		return value == null || value.isBlank() ? null : value;
	}

	static void requireActor(String actor) {
		if (actor == null || actor.isBlank() || actor.length() > 200)
			throw new IllegalArgumentException("인증된 작업자가 필요합니다.");
	}

	static void number(BigDecimal value, String label, BigDecimal max) {
		if (value == null || value.signum() < 0 || value.compareTo(max) > 0 || value.stripTrailingZeros().scale() > 2)
			throw new IllegalArgumentException(label + "은 0~" + max + " 범위의 소수 둘째 자리까지 입력하세요.");
	}

	static PageRequest page(int page, int size, int max) {
		if (page < 0 || size < 1 || size > max)
			throw new IllegalArgumentException("페이지 범위를 확인하세요.");
		return PageRequest.of(page, size);
	}

	static String vendorLabel(VendorType v) {
		return switch (v) {
			case IHB -> "아이허브";
			case VTB -> "비타바이오틱스";
			case FTN -> "포트넘앤메이슨";
			case COK -> "코스트코 UK";
			case OCD -> "오카도";
			default -> v.name();
		};
	}
}
