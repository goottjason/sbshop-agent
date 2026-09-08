package com.sbshop.agent.core.application.market.inspection;

import com.sbshop.agent.core.application.market.MarketConnectionService;
import com.sbshop.agent.core.domain.common.exception.ResourceNotFoundException;
import com.sbshop.agent.core.domain.market.inspection.*;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class DailyMarketInspectionService {
	public static final Set<MarketType> SUPPORTED = Set.of(MarketType.SMART_STORE, MarketType.COUPANG,
		MarketType.CAFE24, MarketType.ELEVEN_STREET);
	private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
	private static final LocalTime START = LocalTime.of(3, 0);
	private final MarketInspectionService queue;
	private final MarketInspectionSweepRepository sweeps;
	private final MarketInspectionGateRepository gates;
	private final MarketRegistrationRepository registrations;
	private final MarketInspectionTaskRepository tasks;
	private final MarketInspectionBatchRepository batches;
	private final MarketConnectionService connections;
	private final PlatformTransactionManager transactions;
	@Value("${products.connection-inspection.daily-enabled:false}")
	private boolean enabled;
	@Value("${products.connection-inspection.enrollment-page-size:500}")
	private int pageSize;

	public record Totals(long pending, long confirmed, long detached, long needsAttention, long skipped) {
	}
	public record SweepView(String id, LocalDate date, String state, long enrolled, int batchCount,
		Instant startedAt, Instant finishedAt, Totals totals) {
	}
	public record DailyStatus(boolean enabled, boolean accountVerified, String schedule, Instant nextDueAt,
		String detail, SweepView latest) {
	}

	public record MarketDailyStatus(String market, DailyStatus status) {
	}

	public void tick() {
		Instant now = queue.now();
		for (var market : SUPPORTED.stream().sorted().toList()) {
			try {
				tickAt(now, market);
			} catch (Exception e) {
				log.error("정기 마켓 상태 조회 접수 실패. 저장된 위치부터 재시도합니다: {}", market, e);
			}
		}
	}

	// Package-private deterministic clock entry for the date-boundary integration tests.
	void tickAt(Instant instant) {
		tickAt(instant, MarketType.SMART_STORE);
	}

	void tickAt(Instant instant, MarketType market) {
		if (!SUPPORTED.contains(market))
			throw new IllegalArgumentException("지원하지 않는 정기 마켓입니다.");
		if (!enabled)
			return;
		queue.ensureGate(market);
		var tx = new TransactionTemplate(transactions);
		tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		tx.executeWithoutResult(s -> {
			gates.lock(market.name() + "_ORIGIN_READ").orElseThrow();
			String account = queue.account(market);
			if (account == null || market == MarketType.SMART_STORE && !connections.accountVerified(account))
				return;
			var local = instant.atZone(ZONE);
			var sweep = sweeps.findTopByMarketOrderByRunDateDesc(market.name()).orElse(null);
			if (sweep != null && !"FINISHED".equals(sweep.getState())) {
				if (!account.equals(sweep.getAccountReference()))
					return;
				if ("ENQUEUED".equals(sweep.getState())) {
					if (totals(sweep.getId()).pending() > 0)
						return;
					sweep.finished(instant);
				}
			}
			if (sweep == null || "FINISHED".equals(sweep.getState())) {
				if (local.toLocalTime().isBefore(START)
					|| sweeps.existsByMarketAndRunDate(market.name(), local.toLocalDate()))
					return;
				sweep = sweeps
					.saveAndFlush(new MarketInspectionSweep(UUID.randomUUID().toString(), local.toLocalDate(), account,
						registrations.lastRegistrationId(market), instant, market.name()));
			}
			long room = 20000 - tasks.activeCountForMarket(market.name());
			if (room <= 0)
				return;
			int limit = (int)Math.min(room, Math.max(1, Math.min(5000, pageSize)));
			var page = registrations.dailyInspectionPage(market, sweep.getCursorRegistrationId(),
				sweep.getUpperRegistrationId(), PageRequest.of(0, limit));
			if (page.isEmpty()) {
				sweep.enrolled(instant);
				if (totals(sweep.getId()).pending() == 0)
					sweep.finished(instant);
				return;
			}
			long cursor = page.getLast().getId();
			String requestId = UUID
				.nameUUIDFromBytes(("daily:" + sweep.getId() + ":" + sweep.getCursorRegistrationId() + ":" + cursor)
					.getBytes(StandardCharsets.UTF_8))
				.toString();
			queue.enqueueLocked(page.stream().map(r -> r.getProductId()).toList(), requestId,
				"system:daily-connection-inspection", "DAILY", account, sweep.getId(), market);
			sweep.advance(cursor, page.size(), instant);
			if (cursor == sweep.getUpperRegistrationId() || page.size() < limit)
				sweep.enrolled(instant);
		});
	}

	@Transactional(readOnly = true)
	public DailyStatus status() {
		return status(MarketType.SMART_STORE);
	}

	@Transactional(readOnly = true)
	public List<MarketDailyStatus> statuses() {
		return SUPPORTED.stream().sorted().map(m -> new MarketDailyStatus(m.name(), status(m))).toList();
	}

	@Transactional(readOnly = true)
	public DailyStatus status(MarketType market) {
		if (!SUPPORTED.contains(market))
			throw new IllegalArgumentException("지원하지 않는 정기 마켓입니다.");
		String account = queue.account(market);
		boolean verified = connections.accountVerified(market, account);
		boolean mayEnroll = account != null && (market != MarketType.SMART_STORE || verified);
		var sweep = sweeps.findTopByMarketOrderByRunDateDesc(market.name()).orElse(null);
		Instant now = queue.now();
		var local = now.atZone(ZONE);
		Instant next = local.toLocalDate().atTime(START).atZone(ZONE).toInstant();
		boolean unfinished = sweep != null && !"FINISHED".equals(sweep.getState());
		String detail = !enabled ? "정기 확인이 꺼져 있습니다." : !mayEnroll ? "확인한 계정과 현재 연동 계정이 다르거나 계정이 없어 정기 확인을 보류합니다."
			: unfinished && !Objects.equals(account, sweep.getAccountReference())
				? "진행 중인 회차의 계정이 변경되어 접수를 보류합니다. 기존 계정 귀속 확인이 필요합니다."
				: unfinished ? "진행 중인 정기 확인을 이어서 처리합니다." : "매일 오전 3시부터 전체 활성 연결을 순서대로 확인합니다.";
		if (market != MarketType.SMART_STORE && !verified)
			detail += " 과거 계정 귀속은 미확인입니다. 일반 부재 응답만으로 삭제하지 않고 명시된 상품 상태만 판정합니다.";
		if (!enabled || !mayEnroll || unfinished)
			next = null;
		else if (sweep != null && !sweep.getRunDate().isBefore(local.toLocalDate()))
			next = sweep.getRunDate().plusDays(1).atTime(START).atZone(ZONE).toInstant();
		SweepView view = sweep == null ? null : new SweepView(sweep.getId(), sweep.getRunDate(), sweep.getState(),
			sweep.getEnrolledCount(), sweep.getBatchCount(), sweep.getStartedAt(), sweep.getFinishedAt(),
			totals(sweep.getId()));
		return new DailyStatus(enabled, verified, "매일 03:00 (한국시간)", next, detail, view);
	}

	@Transactional(readOnly = true)
	public List<MarketInspectionService.BatchView> batches(String sweepId) {
		if (!sweeps.existsById(sweepId))
			throw new ResourceNotFoundException("정기 확인 작업을 찾을 수 없습니다.");
		var counts = tasks.batchCountsForSweep(sweepId).stream().collect(Collectors.groupingBy(
			MarketInspectionTaskRepository.BatchStateCount::getBatchId,
			Collectors.toMap(MarketInspectionTaskRepository.BatchStateCount::getState,
				c -> Math.toIntExact(c.getCount()))));
		return batches.findBySweepIdOrderByCreatedAtAscIdAsc(sweepId).stream()
			.map(batch -> queue.summary(batch, counts.getOrDefault(batch.getId(), Map.of()))).toList();
	}

	private Totals totals(String id) {
		var counts = tasks.sweepCounts(id).stream().collect(Collectors.toMap(
			MarketInspectionTaskRepository.StateCount::getState, MarketInspectionTaskRepository.StateCount::getCount));
		long pending = Set.of("QUEUED", "RUNNING", "RETRY_WAIT").stream()
			.mapToLong(state -> counts.getOrDefault(state, 0L)).sum();
		long confirmed = counts.getOrDefault("CONFIRMED", 0L), detached = counts.getOrDefault("DETACHED", 0L),
			skipped = counts.getOrDefault("SKIPPED", 0L);
		return new Totals(pending, confirmed, detached,
			counts.values().stream().mapToLong(Long::longValue).sum() - pending - confirmed - detached - skipped,
			skipped);
	}
}
