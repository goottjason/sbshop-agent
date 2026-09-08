package com.sbshop.agent.core.application.market.inspection;

import com.sbshop.agent.core.application.market.MarketConnectionService;
import com.sbshop.agent.core.application.product.edit.ProductEditConflictException;
import com.sbshop.agent.core.domain.common.exception.ResourceNotFoundException;
import com.sbshop.agent.core.domain.market.client.MarketClientRouter;
import com.sbshop.agent.core.domain.market.client.dto.MarketListingObservation;
import com.sbshop.agent.core.domain.market.inspection.*;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.ProductRepository;
import java.time.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
@Slf4j
public class MarketInspectionService {
	private static final String GATE = MarketInspectionGate.SMART_STORE_SCOPE;
	private static final MarketType MARKET = MarketType.SMART_STORE;
	public static final Set<MarketType> SUPPORTED = Set.of(MarketType.SMART_STORE, MarketType.COUPANG,
		MarketType.ELEVEN_STREET, MarketType.CAFE24);

	private static String gateId(MarketType market) {
		return market.name() + "_ORIGIN_READ";
	}

	private static final int MAX_ATTEMPTS = 5;
	private static final Set<String> ACTIVE = Set.of("QUEUED", "RUNNING", "RETRY_WAIT");
	private final MarketInspectionBatchRepository batches;
	private final MarketInspectionTaskRepository tasks;
	private final MarketInspectionGateRepository gates;
	private final ProductRepository products;
	private final MarketRegistrationRepository registrations;
	private final MarketClientRouter clients;
	private final MarketConnectionService connections;
	private final PlatformTransactionManager transactions;
	private final JdbcTemplate jdbc;
	@org.springframework.beans.factory.annotation.Value("${products.connection-inspection.single-account-confirmed:false}")
	private boolean singleAccountConfirmed;
	@org.springframework.beans.factory.annotation.Value("${products.connection-inspection.additional-single-accounts-confirmed:false}")
	private boolean additionalSingleAccountsConfirmed;

	public record TaskView(Long id, Long productId, String sbCode, String externalId, String state, int attempts,
		Instant nextRunAt, String code, String detail, String observedState, Long eventId) {
	}
	public record BatchView(String id, String actor, String source, Instant createdAt, int total, int pending,
		int confirmed, int detached, int needsAttention, int skipped, List<TaskView> items, String market) {
		public BatchView(String id, String actor, String source, Instant createdAt, int total, int pending,
			int confirmed,
			int detached, int needsAttention, int skipped, List<TaskView> items) {
			this(id, actor, source, createdAt, total, pending, confirmed, detached, needsAttention, skipped, items,
				"SMART_STORE");
		}
	}
	public record Availability(boolean supported, boolean accountVerified, String detail) {
	}
	public record Claim(Long taskId, String token, String actor, String account,
		MarketConnectionService.Snapshot snapshot) {
	}

	public Availability availability() {
		return availability(MARKET);
	}

	public Availability availability(MarketType market) {
		if (!SUPPORTED.contains(market))
			return new Availability(false, false, "해당 마켓의 상태 조회 API 계약 확인이 필요합니다.");
		String account = account(market);
		boolean verified = connections.accountVerified(market, account);
		return new Availability(account != null, verified,
			account == null
				? market.getLabel() + " 연동 계정을 확인할 수 없습니다."
				: verified ? market.getLabel() + " 상품번호로 상태를 확인합니다. 일반 오류나 빈 응답만으로 삭제하지 않습니다."
						: "과거 판매 계정 귀속 확인 전에는 부재 응답만으로 연결을 해제하지 않습니다.");
	}

	public BatchView create(List<Long> productIds, String requestId, String actor) {
		return create(productIds, requestId, actor, "SELECTION", MARKET);
	}

	public BatchView create(List<Long> productIds, String requestId, String actor, MarketType market) {
		return create(productIds, requestId, actor, "SELECTION", market);
	}

	private BatchView create(List<Long> productIds, String requestId, String actor, String source, MarketType market) {
		if (market == null || !SUPPORTED.contains(market))
			throw new IllegalArgumentException("지원하는 마켓을 선택하세요.");
		if (actor == null || actor.isBlank() || actor.length() > 200)
			throw new IllegalArgumentException("인증된 작업자가 필요합니다.");
		if (productIds == null || productIds.isEmpty() || productIds.size() > 5000
			|| productIds.stream().anyMatch(id -> id == null || id <= 0))
			throw new IllegalArgumentException("상품을 1~5,000개 선택하세요.");
		try {
			if (requestId == null || !UUID.fromString(requestId).toString().equals(requestId))
				throw new IllegalArgumentException();
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("유효한 요청 ID가 필요합니다.");
		}
		var ids = productIds.stream().distinct().sorted().toList();
		ensureGate(market);
		return tx().execute(status -> enqueueLocked(ids, requestId, actor, source, null, null, market));
	}

	/** Joins the daily cursor transaction; all creation paths serialize on the same gate. */
	@Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
	public BatchView enqueueLocked(List<Long> ids, String requestId, String actor, String source,
		String expectedAccount, String sweepId) {
		return enqueueLocked(ids, requestId, actor, source, expectedAccount, sweepId, MARKET);
	}

	@Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
	public BatchView enqueueLocked(List<Long> ids, String requestId, String actor, String source,
		String expectedAccount, String sweepId, MarketType market) {
		String identity = market.name() + ":" + source + ":" + ids;
		gates.lock(gateId(market)).orElseThrow();
		var existing = batches.findById(requestId);
		if (existing.isPresent()) {
			boolean sameSelection = existing.get().getRequestIdentity().equals(identity)
				|| (market == MARKET && existing.get().getMarket().equals(MARKET.name())
					&& existing.get().getRequestIdentity().equals(source + ":" + ids));
			if (!existing.get().getActor().equals(actor) || !sameSelection)
				throw new ProductEditConflictException("요청 ID가 다른 작업에 사용되었습니다.");
			return view(existing.get());
		}
		String account = account(market);
		if (expectedAccount != null && !expectedAccount.equals(account))
			throw new IllegalStateException("정기 조회 계정이 변경되었습니다.");
		if (account == null)
			throw new IllegalStateException(market.getLabel() + " 조회 계정을 확인할 수 없습니다.");
		if (tasks.activeCountForMarket(market.name()) + ids.size() > 20000)
			throw new IllegalStateException("대기 작업이 많습니다. 현재 작업 처리 후 다시 시도하세요.");
		Instant now = now();
		var batch = new MarketInspectionBatch(requestId, actor, identity, source, now);
		batch.attachToSweep(sweepId);
		batch.market(market);
		batches.save(batch);
		var productMap = products.findAllById(ids).stream().collect(Collectors.toMap(p -> p.getId(), p -> p));
		var regMap = registrations.findByProductIdIn(ids).stream().filter(r -> r.getMarketType() == market)
			.collect(Collectors.toMap(r -> r.getProductId(), r -> r));
		var active = new HashSet<>(tasks.activeProducts(market.name(), ids));
		for (Long id : ids) {
			var product = productMap.get(id);
			var reg = regMap.get(id);
			String skip = product == null || product.isDeleted() ? "상품이 없거나 폐기되었습니다."
				: reg == null ? market.getLabel() + " 연결이 없습니다."
					: reg.getConnectionState().detached() ? "이미 해제된 연결입니다."
						: reg.connectionIdentifier(market) == null ? "마켓 상품번호가 없습니다."
							: active.contains(id) ? "이미 상태 확인 작업이 대기·진행 중입니다." : null;
			var snapshot = reg == null ? null : new MarketConnectionService.Snapshot(reg.getId(), id,
				reg.getRevision(), market, reg.connectionIdentifier(market), reg.getMarketIdentifiers());
			var task = new MarketInspectionTask(requestId, id, product == null ? null : product.getSbCode(),
				snapshot, account, skip, now);
			task.market(market);
			tasks.save(task);
		}
		return view(batch);
	}

	@Transactional(readOnly = true)
	public BatchView get(String id) {
		return view(batches.findById(id).orElseThrow(() -> new ResourceNotFoundException("상태 확인 작업을 찾을 수 없습니다.")));
	}

	@Transactional(readOnly = true)
	public List<BatchView> recent() {
		return batches.findTop20ByOrderByCreatedAtDesc().stream().map(this::summary).toList();
	}

	BatchView summary(MarketInspectionBatch batch) {
		var counts = tasks.counts(batch.getId()).stream().collect(Collectors
			.toMap(MarketInspectionTaskRepository.StateCount::getState, c -> Math.toIntExact(c.getCount())));
		return summary(batch, counts);
	}

	BatchView summary(MarketInspectionBatch batch, Map<String, Integer> counts) {
		int total = counts.values().stream().mapToInt(Integer::intValue).sum();
		int pending = ACTIVE.stream().mapToInt(s -> counts.getOrDefault(s, 0)).sum();
		int confirmed = counts.getOrDefault("CONFIRMED", 0), detached = counts.getOrDefault("DETACHED", 0),
			skipped = counts.getOrDefault("SKIPPED", 0);
		return new BatchView(batch.getId(), batch.getActor(), batch.getSource(), batch.getCreatedAt(), total,
			pending, confirmed, detached, total - pending - confirmed - detached - skipped, skipped, List.of(),
			batch.getMarket());
	}

	public BatchView retry(String id, String requestId, String actor) {
		var existing = batches.findById(requestId);
		if (existing.isPresent()) {
			if (!existing.get().getActor().equals(actor) || !existing.get().getSource().equals("RETRY:" + id))
				throw new ProductEditConflictException("요청 ID가 다른 작업에 사용되었습니다.");
			return get(requestId);
		}
		var batch = get(id);
		var ids = batch.items().stream()
			.filter(t -> Set.of("UNKNOWN", "FAILED", "STALE", "ACCOUNT_REVIEW_REQUIRED").contains(t.state()))
			.map(TaskView::productId).toList();
		if (ids.isEmpty())
			throw new IllegalArgumentException("다시 확인할 실패·미확인 상품이 없습니다.");
		return create(ids, requestId, actor, "RETRY:" + id, MarketType.valueOf(batch.market()));
	}

	public void processOne() {
		processOne(MARKET);
	}

	public void processOne(MarketType market) {
		Claim claim = claim(market);
		if (claim == null)
			return;
		MarketListingObservation observation;
		try {
			if (!Objects.equals(claim.account(), account(market)))
				observation = accountChanged();
			else {
				observation = clients.getClient(market).inspectListing(claim.snapshot().externalId());
				if (observation == null)
					observation = MarketListingObservation.unknown("빈 조회 결과입니다.");
				if (!Objects.equals(claim.account(), account(market)) || (observation.accountReference() != null
					&& !claim.account().equals(observation.accountReference())))
					observation = accountChanged();
				if (observation.state() != MarketListingObservation.State.UNKNOWN
					&& observation.accountReference() == null)
					observation = accountChanged();
			}
		} catch (Exception e) {
			observation = MarketListingObservation.unknown("상태 조회 실행 실패. 연결은 유지했습니다.");
		}
		// If persistence fails, the lease remains visible and is recovered on expiry. No fake success.
		finish(claim, observation);
	}

	public Claim claim() {
		return claim(MARKET);
	}

	public Claim claim(MarketType market) {
		ensureGate(market);
		return tx().execute(status -> {
			var gate = gates.lock(gateId(market)).orElseThrow();
			Instant now = now();
			if (!gate.available(now))
				return null;
			var due = tasks.due(market.name(), now, PageRequest.of(0, 1));
			if (due.isEmpty())
				return null;
			var task = due.getFirst();
			if (task.getAttempts() >= MAX_ATTEMPTS) {
				task.finish("FAILED", "INTERRUPTED", "실행 중단 후 재시도 한도에 도달했습니다. 다시 확인할 수 있습니다.", "UNKNOWN",
					task.getEventId(), now, now);
				return null;
			}
			String token = UUID.randomUUID().toString();
			Instant until = now.plusSeconds(180);
			task.claim(token, until, now);
			gate.claim(token, until);
			return new Claim(task.getId(), token, batches.findById(task.getBatchId()).orElseThrow().getActor(),
				task.getAccountReference(), task.snapshot());
		});
	}

	public void finish(Claim claim, MarketListingObservation observation) {
		MarketType market = claim.snapshot().market();
		tx().executeWithoutResult(status -> {
			var gate = gates.lock(gateId(market)).orElseThrow();
			var task = tasks.findById(claim.taskId()).orElseThrow();
			Instant now = now();
			if (!gate.owns(claim.token(), now) || !task.owns(claim.token(), now))
				return;
			var result = connections.applyObservation(claim.snapshot(), observation, "INSPECTION_JOB", claim.actor());
			boolean retry = "OBSERVED".equals(result.result()) && observation.retryable()
				&& task.getAttempts() < MAX_ATTEMPTS;
			Instant retryAt = nextAttempt(now, task.getAttempts(), observation.retryAfter());
			String state = retry ? "RETRY_WAIT" : "STALE".equals(result.result()) ? "STALE"
				: "ACCOUNT_REVIEW_REQUIRED".equals(result.result()) ? "ACCOUNT_REVIEW_REQUIRED"
					: observation.state() == MarketListingObservation.State.UNKNOWN ? "UNKNOWN"
						: result.state().startsWith("DETACHED_") ? "DETACHED" : "CONFIRMED";
			task.finish(state, observation.code(), result.detail(), observation.state().name(), result.eventId(),
				retry ? retryAt : now, now);
			gate.release(observation.rateLimited() || observation.retryAfter() != null ? retryAt : now.plusSeconds(2));
		});
	}

	static Instant nextAttempt(Instant now, int attempt, Instant serverAfter) {
		long delay = Math.min(600, 30L << Math.min(4, Math.max(0, attempt - 1)))
			+ ThreadLocalRandom.current().nextLong(6);
		Instant local = now.plusSeconds(delay);
		return serverAfter != null && serverAfter.isAfter(local) ? serverAfter : local;
	}

	private BatchView view(MarketInspectionBatch batch) {
		var rows = tasks.findByBatchIdOrderById(batch.getId());
		var items = rows.stream()
			.map(t -> new TaskView(t.getId(), t.getProductId(), t.getSbCode(), t.getExternalId(), t.getState(),
				t.getAttempts(), t.getNextRunAt(), t.getCode(), t.getDetail(), t.getObservedState(), t.getEventId()))
			.toList();
		int pending = (int)rows.stream().filter(t -> ACTIVE.contains(t.getState())).count();
		int confirmed = (int)rows.stream().filter(t -> t.getState().equals("CONFIRMED")).count();
		int detached = (int)rows.stream().filter(t -> t.getState().equals("DETACHED")).count();
		int skipped = (int)rows.stream().filter(t -> t.getState().equals("SKIPPED")).count();
		return new BatchView(batch.getId(), batch.getActor(), batch.getSource(), batch.getCreatedAt(), rows.size(),
			pending, confirmed, detached, rows.size() - pending - confirmed - detached - skipped, skipped, items,
			batch.getMarket());
	}

	String account() {
		return account(MARKET);
	}

	String account(MarketType market) {
		String account = clients.hasClient(market) ? clients.getClient(market).inspectionAccountReference() : null;
		return account == null || account.isBlank() ? null : account;
	}

	private MarketListingObservation accountChanged() {
		return new MarketListingObservation(MarketListingObservation.State.UNKNOWN, "ACCOUNT_CHANGED",
			"대기 중 조회 계정이 바뀌었거나 응답 계정을 확인하지 못해 연결을 유지했습니다.", null, null, Instant.now());
	}

	Instant now() {
		return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", (rs, row) -> rs.getTimestamp(1).toInstant());
	}

	private TransactionTemplate tx() {
		var tx = new TransactionTemplate(transactions);
		tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		return tx;
	}

	void ensureGate() {
		ensureGate(MARKET);
	}

	void ensureGate(MarketType market) {
		String gateKey = gateId(market);
		try {
			tx().executeWithoutResult(s -> {
				var gate = gates.lock(gateKey)
					.orElseGet(() -> gates.saveAndFlush(new MarketInspectionGate(gateKey, now())));
				if (market == MARKET && singleAccountConfirmed)
					gate.confirmInitialAccount(account(), now());
				else if (additionalSingleAccountsConfirmed
					&& Set.of(MarketType.COUPANG, MarketType.ELEVEN_STREET, MarketType.CAFE24).contains(market))
					gate.confirmInitialAccount(account(market), now(),
						"USER_Q26_2026-09-08: " + market.name()
							+ " 과거 상품도 현재 연결된 단일 계정의 상품이며 상품 부재 확인 시 연결 제외·이력 보존 승인");
			});
		} catch (DataIntegrityViolationException collision) {
			if (!gates.existsById(gateKey))
				throw collision;
		}
	}
}
