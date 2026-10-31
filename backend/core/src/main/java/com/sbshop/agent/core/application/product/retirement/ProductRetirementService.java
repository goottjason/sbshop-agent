package com.sbshop.agent.core.application.product.retirement;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.product.MarketListingDeletion;
import com.sbshop.agent.core.application.product.ProductDeleteTxService;
import com.sbshop.agent.core.domain.actionlog.ActionLogConstants;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
import com.sbshop.agent.core.domain.market.MarketConnectionState;
import com.sbshop.agent.core.domain.market.MarketFailureClassifier;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.SyncErrorType;
import com.sbshop.agent.core.domain.market.UnsyncReason;
import com.sbshop.agent.core.domain.market.client.MarketClient;
import com.sbshop.agent.core.domain.market.client.MarketClientRouter;
import com.sbshop.agent.core.domain.market.client.dto.MarketListingObservation;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.ClaimStage;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.vo.ClaimData;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.ProductRepository;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import com.sbshop.agent.core.domain.product.retirement.ProductRetirement;
import com.sbshop.agent.core.domain.product.retirement.ProductRetirementRepository;
import com.sbshop.agent.core.domain.product.retirement.RetirementStatus;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductRetirementService {

	private static final int MAX_BATCH = 500;
	private static final int MAX_CANDIDATES = 1000;
	private static final List<MarketType> MARKET_ORDER = List.of(MarketType.SMART_STORE, MarketType.COUPANG,
		MarketType.ELEVEN_STREET, MarketType.GMARKET, MarketType.AUCTION, MarketType.UNKNOWN, MarketType.CAFE24);
	private static final Set<ShippingStatus> OPEN_SHIPPING = EnumSet.of(ShippingStatus.NEW,
		ShippingStatus.PREPARING, ShippingStatus.DISPATCHED, ShippingStatus.SHIPPED);
	private static final String BLOCKED_OPEN_ORDER = "BLOCKED_OPEN_ORDER";

	private final ProductRetirementRepository retirementRepository;
	private final ProductRepository productRepository;
	private final MarketRegistrationRepository marketRegistrationRepository;
	private final OrderLineItemRepository orderLineItemRepository;
	private final MarketClientRouter marketClientRouter;
	private final ProductDeleteTxService productDeleteTxService;
	private final ActionLogService actionLogService;
	private final ObjectMapper objectMapper = new ObjectMapper();
	private final AtomicBoolean running = new AtomicBoolean(false);

	public enum RetirementMarketResult {
		DELETED, ALREADY_DONE, ELEVENST_STOPPED, ESM_PENDING, COUPANG_REVIEW_PENDING, MANUAL, FAILED, WOULD_DELETE;

		public boolean done() {
			return this == DELETED || this == ALREADY_DONE || this == ELEVENST_STOPPED;
		}
	}

	public record MarketOutcome(RetirementMarketResult result, String message) {}

	public record RetirementReport(Long productId, String sbCode, String stage, Map<MarketType, MarketOutcome> markets,
		boolean disposed, String error) {}

	public static class RetirementAlreadyRunningException extends IllegalStateException {
		public RetirementAlreadyRunningException() {
			super("상품 퇴출이 이미 실행 중입니다. 끝난 뒤 다시 요청하세요.");
		}
	}

	public record Candidate(Long productId, String sbCode, List<MarketType> markets, boolean hasGmarketId,
		boolean hasAuctionId) {}

	public record StartResult(Long productId, String sbCode, String result) {}

	public record EsmTarget(Long productId, String sbCode, String gmarketGoodsNo, String auctionGoodsNo) {}

	public record EsmConfirmation(List<String> confirmed, List<String> unmatched) {}

	public record CancelResult(Long productId, String result, String lastReport) {}

	public record Summary(Map<String, Long> statuses, Map<String, Long> inProgressStages) {}

	public List<Candidate> candidates(VendorType vendor, int limit) {
		int size = Math.max(1, Math.min(limit, MAX_CANDIDATES));
		List<Product> products = retirementRepository.findCandidates(vendor, PageRequest.of(0, size));
		if (products.isEmpty())
			return List.of();
		Map<Long, List<MarketRegistration>> registrations = marketRegistrationRepository
			.findByProductIdIn(products.stream().map(Product::getId).toList()).stream()
			.collect(Collectors.groupingBy(MarketRegistration::getProductId));
		List<Candidate> out = new ArrayList<>();
		for (Product product : products) {
			List<MarketRegistration> regs = registrations.getOrDefault(product.getId(), List.of());
			MarketRegistration cafe = regs.stream().filter(r -> r.getMarketType() == MarketType.CAFE24).findFirst()
				.orElse(null);
			out.add(new Candidate(product.getId(), product.getSbCode(),
				regs.stream().map(MarketRegistration::getMarketType).sorted().toList(),
				cafe != null && cafe.connectionIdentifier(MarketType.GMARKET) != null,
				cafe != null && cafe.connectionIdentifier(MarketType.AUCTION) != null));
		}
		return out;
	}

	public List<StartResult> start(List<Long> productIds, boolean dryRun) {
		requireBatch(productIds);
		List<StartResult> out = new ArrayList<>();
		for (Long productId : new LinkedHashSet<>(productIds)) {
			Optional<Product> found = productRepository.findById(productId);
			String sbCode = found.map(Product::getSbCode).orElse(null);
			out.add(new StartResult(productId, sbCode, startOne(productId, found, dryRun)));
		}
		return out;
	}

	private String startOne(Long productId, Optional<Product> product, boolean dryRun) {
		if (product.isEmpty())
			return "NOT_FOUND";
		if (product.get().isDeleted())
			return "ALREADY_DELETED";
		if (hasOpenOrder(productId))
			return "OPEN_ORDER";
		Optional<ProductRetirement> existing = retirementRepository.findByProductId(productId);
		if (existing.isEmpty()) {
			if (dryRun)
				return "WOULD_START";
			retirementRepository.save(ProductRetirement.start(productId));
			return "STARTED";
		}
		ProductRetirement retirement = existing.get();
		return switch (retirement.getRetirementStatus()) {
			case IN_PROGRESS -> "ALREADY_IN_PROGRESS";
			case DONE -> "ALREADY_DONE";
			case CANCELED -> {
				if (dryRun)
					yield "WOULD_RESUME";
				retirement.resume();
				retirementRepository.save(retirement);
				yield "RESUMED";
			}
		};
	}

	public List<RetirementReport> advance(int limit, long throttleMs, boolean dryRun) {
		if (!running.compareAndSet(false, true))
			throw new RetirementAlreadyRunningException();
		try {
			int size = Math.max(1, Math.min(limit, MAX_BATCH));
			List<RetirementReport> reports = new ArrayList<>();
			for (ProductRetirement due : retirementRepository.findDue(RetirementStatus.IN_PROGRESS,
				PageRequest.of(0, size))) {
				reports.add(advanceSafely(due.getProductId(), throttleMs, dryRun));
			}
			if (!dryRun)
				recordRound(reports);
			return reports;
		} finally {
			running.set(false);
		}
	}

	private RetirementReport advanceSafely(Long productId, long throttleMs, boolean dryRun) {
		try {
			return advanceOne(productId, throttleMs, dryRun);
		} catch (org.springframework.dao.OptimisticLockingFailureException conflict) {
			log.warn("[퇴출] 동시 변경 충돌 — 이 상품만 건너뛴다: productId={}", productId);
			return recordUnexpected(new RetirementReport(productId, null, "SKIPPED_CONFLICT", Map.of(), false,
				conflict.getMessage()), dryRun);
		} catch (RuntimeException e) {
			log.error("[퇴출] 상품 처리 중 예상 밖 오류 — 다음 상품으로 넘어간다: productId={}, error={}", productId,
				e.getMessage(), e);
			return recordUnexpected(new RetirementReport(productId, null, "FAILED", Map.of(), false, e.getMessage()),
				dryRun);
		}
	}

	private RetirementReport recordUnexpected(RetirementReport report, boolean dryRun) {
		if (dryRun)
			return report;
		try {
			ProductRetirement latest = retirementRepository.findByProductId(report.productId()).orElse(null);
			if (latest != null && latest.inProgress()) {
				latest.recordAttempt(json(report), LocalDateTime.now());
				retirementRepository.save(latest);
			}
		} catch (RuntimeException e) {
			log.warn("[퇴출] 실패 기록 저장도 실패 — 회차는 계속한다: productId={}, error={}", report.productId(),
				e.getMessage());
		}
		return report;
	}

	private RetirementReport advanceOne(Long productId, long throttleMs, boolean dryRun) {
		ProductRetirement retirement = retirementRepository.findByProductId(productId).orElse(null);
		if (retirement == null || !retirement.inProgress())
			return new RetirementReport(productId, null, "SKIPPED_NOT_IN_PROGRESS", Map.of(), false, null);
		Product product = productRepository.findById(productId).orElse(null);
		if (product == null || product.isDeleted()) {
			RetirementReport gone = new RetirementReport(productId, product == null ? null : product.getSbCode(),
				"PRODUCT_GONE", Map.of(), false, null);
			if (!dryRun)
				retirement.cancel();
			return finish(retirement, gone, dryRun);
		}
		if (hasOpenOrder(productId))
			return finish(retirement,
				new RetirementReport(productId, product.getSbCode(), BLOCKED_OPEN_ORDER, Map.of(), false, null), dryRun);

		List<MarketRegistration> registrations = marketRegistrationRepository.findByProductId(productId);
		boolean elevenstStoppedBefore = previousResult(retirement,
			MarketType.ELEVEN_STREET) == RetirementMarketResult.ELEVENST_STOPPED;
		Map<MarketType, MarketOutcome> markets = new LinkedHashMap<>();
		ProductRetirement current = retirement;
		for (MarketRegistration reg : registrations.stream()
			.sorted(Comparator.comparingInt(r -> MARKET_ORDER.indexOf(r.getMarketType()))).toList()) {
			if (!dryRun) {
				current = retirementRepository.findByProductId(productId).orElse(null);
				if (current == null || !current.inProgress())
					return canceledDuringRun(productId, product, markets);
			}
			markets.put(reg.getMarketType(), retire(reg, current, dryRun, elevenstStoppedBefore, throttleMs));
		}

		boolean allDone = markets.values().stream().allMatch(outcome -> outcome.result().done());
		if (dryRun)
			return new RetirementReport(productId, product.getSbCode(), allDone ? "WOULD_DISPOSE"
				: remainingStage(markets), markets, false, null);
		ProductRetirement latest = retirementRepository.findByProductId(productId).orElse(null);
		if (latest == null || !latest.inProgress())
			return canceledDuringRun(productId, product, markets);
		if (allDone) {
			product.recordDeletionFollowup(json(markets));
			productDeleteTxService.deleteWithRegistrations(product, registrations);
			latest.complete();
		}
		return finish(latest, new RetirementReport(productId, product.getSbCode(),
			allDone ? "DISPOSED" : remainingStage(markets), markets, allDone, null), false);
	}

	private static RetirementReport canceledDuringRun(Long productId, Product product,
		Map<MarketType, MarketOutcome> markets) {
		log.warn("[퇴출] 회차 도중 취소됨 — 남은 마켓·폐기를 멈추고 기록도 덮어쓰지 않는다: productId={}", productId);
		return new RetirementReport(productId, product.getSbCode(), "CANCELED_DURING_RUN", markets, false, null);
	}

	private MarketOutcome retire(MarketRegistration reg, ProductRetirement retirement, boolean dryRun,
		boolean elevenstStoppedBefore, long throttleMs) {
		MarketType market = reg.getMarketType();
		if (market == MarketType.CAFE24 && retirement.getEsmConfirmedAt() == null)
			return outcome(RetirementMarketResult.ESM_PENDING, "G마켓·옥션 부재 확인 전에는 카페24를 지우지 않습니다");
		if (isDone(reg))
			return outcome(RetirementMarketResult.ALREADY_DONE, null);
		String code = reg.extractDeleteCode();
		if (code == null || code.isEmpty())
			return outcome(RetirementMarketResult.MANUAL, "마켓 상품코드를 몰라 자동 삭제할 수 없습니다 — 마켓에서 직접 확인해야 합니다");
		if (!marketClientRouter.hasClient(market))
			return outcome(RetirementMarketResult.MANUAL, "마켓 클라이언트가 없어 자동 삭제할 수 없습니다 — 마켓에서 직접 지워야 합니다");
		if (dryRun)
			return outcome(RetirementMarketResult.WOULD_DELETE, code);
		MarketClient client = marketClientRouter.getClient(market);
		try {
			if (market == MarketType.ELEVEN_STREET)
				return retireElevenst(reg, client, code, elevenstStoppedBefore);
			MarketListingDeletion.Attempt attempt = MarketListingDeletion.deleteAndInspect(client, code);
			if (attempt.absent())
				return detach(reg);
			return failure(reg, MarketListingDeletion.failureOf(attempt));
		} catch (UnsupportedOperationException unsupported) {
			return outcome(RetirementMarketResult.MANUAL, unsupported.getMessage());
		} catch (RuntimeException e) {
			return failure(reg, e);
		} finally {
			sleepQuietly(throttleMs);
		}
	}

	private MarketOutcome retireElevenst(MarketRegistration reg, MarketClient client, String code,
		boolean stoppedBefore) {
		if (stoppedBefore) {
			String account = client.inspectionAccountReference();
			MarketListingObservation observation = client.inspectListing(code);
			if (observed(client, account, observation, MarketListingObservation.State.DELETED))
				return detach(reg);
			if (observed(client, account, observation, MarketListingObservation.State.STOPPED))
				return outcome(RetirementMarketResult.ELEVENST_STOPPED, "재조회로 판매중지 유지를 확인했습니다");
		}
		MarketListingDeletion.Attempt attempt = MarketListingDeletion.deleteAndInspect(client, code);
		if (attempt.absent())
			return detach(reg);
		if (observed(client, attempt.account(), attempt.observation(), MarketListingObservation.State.STOPPED))
			return outcome(RetirementMarketResult.ELEVENST_STOPPED, "삭제는 미확인 · 재조회로 판매중지를 확인했습니다");
		client.stopSelling(code);
		MarketListingObservation after = client.inspectListing(code);
		if (observed(client, attempt.account(), after, MarketListingObservation.State.DELETED))
			return detach(reg);
		if (observed(client, attempt.account(), after, MarketListingObservation.State.STOPPED))
			return outcome(RetirementMarketResult.ELEVENST_STOPPED, "판매중지 후 재조회로 확인했습니다");
		return failure(reg, new IllegalStateException("11번가 삭제·판매중지 후에도 판매중지를 확인하지 못했습니다. 확인 결과: "
			+ (after == null ? "응답 없음" : after.state() + " / " + after.code())));
	}

	private static boolean observed(MarketClient client, String account, MarketListingObservation observation,
		MarketListingObservation.State state) {
		return observation != null && observation.state() == state
			&& MarketListingDeletion.sameAccount(client, account, observation);
	}

	private static boolean isDone(MarketRegistration reg) {
		if (reg.connectionStateFor(reg.getMarketType()).detached())
			return true;
		return Boolean.FALSE.equals(reg.getIsSynced()) && (reg.getUnsyncReason() == UnsyncReason.DELETED_ON_MARKET
			|| reg.getUnsyncReason() == UnsyncReason.NEVER_SYNCED);
	}

	private MarketOutcome detach(MarketRegistration reg) {
		reg.markAbsentFromMarket(UnsyncReason.DELETED_ON_MARKET);
		reg.detachConnection(reg.getMarketType(), MarketConnectionState.DETACHED_DELETED);
		marketRegistrationRepository.save(reg);
		return outcome(RetirementMarketResult.DELETED, null);
	}

	private MarketOutcome failure(MarketRegistration reg, RuntimeException error) {
		SyncErrorType type = MarketFailureClassifier.classifyError(error);
		reg.recordSyncError(type, error.getMessage());
		marketRegistrationRepository.save(reg);
		log.warn("[퇴출] 마켓 삭제 미완료: productId={}, market={}, type={}, error={}",
			reg.getProductId(), reg.getMarketType(), type, error.getMessage());
		if (reg.getMarketType() == MarketType.COUPANG && type == SyncErrorType.BLOCKED_BY_MARKET)
			return outcome(RetirementMarketResult.COUPANG_REVIEW_PENDING, error.getMessage());
		return outcome(RetirementMarketResult.FAILED, error.getMessage());
	}

	private static MarketOutcome outcome(RetirementMarketResult result, String message) {
		return new MarketOutcome(result, message);
	}

	private static String remainingStage(Map<MarketType, MarketOutcome> markets) {
		Set<RetirementMarketResult> remaining = new TreeSet<>();
		for (MarketOutcome outcome : markets.values()) {
			if (!outcome.result().done())
				remaining.add(outcome.result());
		}
		return remaining.stream().map(Enum::name).collect(Collectors.joining("+"));
	}

	private RetirementReport finish(ProductRetirement retirement, RetirementReport report, boolean dryRun) {
		if (!dryRun) {
			retirement.recordAttempt(json(report), LocalDateTime.now());
			try {
				retirementRepository.save(retirement);
			} catch (org.springframework.dao.OptimisticLockingFailureException conflict) {
				log.warn("[퇴출] 기록 저장 중 동시 변경 충돌 — 이 상품은 건너뛴다: productId={}", report.productId());
				return new RetirementReport(report.productId(), report.sbCode(), "SKIPPED_CONFLICT", report.markets(),
					report.disposed(), conflict.getMessage());
			}
		}
		return report;
	}

	private RetirementMarketResult previousResult(ProductRetirement retirement, MarketType market) {
		if (retirement.getLastReport() == null)
			return null;
		try {
			String value = objectMapper.readTree(retirement.getLastReport()).path("markets").path(market.name())
				.path("result").asText(null);
			return value == null ? null : RetirementMarketResult.valueOf(value);
		} catch (Exception e) {
			return null;
		}
	}

	private void recordRound(List<RetirementReport> reports) {
		Map<String, Long> stages = reports.stream()
			.collect(Collectors.groupingBy(RetirementReport::stage, LinkedHashMap::new, Collectors.counting()));
		long disposed = reports.stream().filter(RetirementReport::disposed).count();
		boolean failed = reports.stream().flatMap(r -> r.markets().values().stream())
			.anyMatch(o -> o.result() == RetirementMarketResult.FAILED);
		actionLogService.record(ActionLogConstants.PRODUCT_RETIREMENT, null,
			failed ? ActionStatus.WARNING : ActionStatus.SUCCESS,
			"상품 퇴출 회차 | 대상 " + reports.size() + " · 폐기 " + disposed + " · 단계 " + stages);
	}

	private boolean hasOpenOrder(Long productId) {
		return orderLineItemRepository.findByProductId(productId).stream()
			.anyMatch(ProductRetirementService::isOpen);
	}

	private static boolean isOpen(OrderLineItem line) {
		ClaimData claim = line.getClaimData();
		ClaimStage stage = claim == null ? ClaimStage.NONE : claim.getClaimStage();
		if (stage == ClaimStage.REQUESTED || stage == ClaimStage.IN_PROGRESS)
			return true;
		ShippingStatus shipping = line.getShippingData() == null ? null : line.getShippingData().getShippingStatus();
		return shipping != null && OPEN_SHIPPING.contains(shipping) && (claim == null || !claim.isRefundTerminal());
	}

	public List<EsmTarget> esmTargets() {
		List<ProductRetirement> pending = retirementRepository
			.findByRetirementStatusAndEsmConfirmedAtIsNull(RetirementStatus.IN_PROGRESS);
		if (pending.isEmpty())
			return List.of();
		List<Long> ids = pending.stream().map(ProductRetirement::getProductId).toList();
		Map<Long, MarketRegistration> cafes = marketRegistrationRepository.findByProductIdIn(ids).stream()
			.filter(r -> r.getMarketType() == MarketType.CAFE24)
			.collect(Collectors.toMap(MarketRegistration::getProductId, Function.identity(), (a, b) -> a));
		Map<Long, Product> products = productRepository.findAllById(ids).stream()
			.collect(Collectors.toMap(Product::getId, Function.identity(), (a, b) -> a));
		List<EsmTarget> out = new ArrayList<>();
		for (Long id : ids) {
			MarketRegistration cafe = cafes.get(id);
			if (cafe == null)
				continue;
			Product product = products.get(id);
			out.add(new EsmTarget(id, product == null ? null : product.getSbCode(),
				cafe.connectionIdentifier(MarketType.GMARKET), cafe.connectionIdentifier(MarketType.AUCTION)));
		}
		return out;
	}

	@Transactional
	public EsmConfirmation confirmEsmAbsent(List<String> sbCodes) {
		List<String> codes = sbCodes == null ? List.of()
			: sbCodes.stream().filter(c -> c != null && !c.isBlank()).map(String::trim).distinct().toList();
		if (codes.isEmpty())
			return new EsmConfirmation(List.of(), List.of());
		List<Product> products = productRepository.findBySbCodeIn(codes);
		Map<Long, ProductRetirement> active = products.isEmpty() ? Map.of()
			: retirementRepository.findByProductIdIn(products.stream().map(Product::getId).toList()).stream()
				.filter(ProductRetirement::inProgress)
				.collect(Collectors.toMap(ProductRetirement::getProductId, Function.identity(), (a, b) -> a));
		LocalDateTime now = LocalDateTime.now();
		List<String> confirmed = new ArrayList<>();
		for (Product product : products) {
			ProductRetirement retirement = active.get(product.getId());
			if (retirement == null || !codes.contains(product.getSbCode()))
				continue;
			retirement.confirmEsm(now);
			retirementRepository.save(retirement);
			for (MarketRegistration reg : marketRegistrationRepository.findByProductId(product.getId())) {
				if (reg.getMarketType() != MarketType.CAFE24)
					continue;
				boolean changed = false;
				for (MarketType child : List.of(MarketType.GMARKET, MarketType.AUCTION)) {
					if (reg.connectionStateFor(child) == MarketConnectionState.LINKED) {
						reg.detachConnection(child, MarketConnectionState.DETACHED_DELETED);
						changed = true;
					}
				}
				if (changed)
					marketRegistrationRepository.save(reg);
			}
			confirmed.add(product.getSbCode());
		}
		List<String> unmatched = codes.stream().filter(c -> !confirmed.contains(c)).toList();
		return new EsmConfirmation(confirmed, unmatched);
	}

	public List<CancelResult> cancel(List<Long> productIds) {
		requireBatch(productIds);
		List<CancelResult> out = new ArrayList<>();
		for (Long productId : new LinkedHashSet<>(productIds)) {
			Optional<ProductRetirement> found = retirementRepository.findByProductId(productId);
			if (found.isEmpty()) {
				out.add(new CancelResult(productId, "NOT_FOUND", null));
				continue;
			}
			ProductRetirement retirement = found.get();
			String result = switch (retirement.getRetirementStatus()) {
				case DONE -> "ALREADY_DONE";
				case CANCELED -> "ALREADY_CANCELED";
				case IN_PROGRESS -> {
					retirement.cancel();
					retirementRepository.save(retirement);
					yield "CANCELED";
				}
			};
			out.add(new CancelResult(productId, result, retirement.getLastReport()));
		}
		return out;
	}

	public Summary summary() {
		Map<String, Long> statuses = new LinkedHashMap<>();
		for (RetirementStatus status : RetirementStatus.values())
			statuses.put(status.name(), retirementRepository.countByRetirementStatus(status));
		Map<String, Long> stages = new LinkedHashMap<>();
		for (ProductRetirement retirement : retirementRepository.findByRetirementStatus(RetirementStatus.IN_PROGRESS))
			stages.merge(stageOf(retirement), 1L, Long::sum);
		return new Summary(statuses, stages);
	}

	private String stageOf(ProductRetirement retirement) {
		if (retirement.getLastReport() == null)
			return "NOT_ATTEMPTED";
		try {
			String stage = objectMapper.readTree(retirement.getLastReport()).path("stage").asText(null);
			return stage == null ? "UNKNOWN" : stage;
		} catch (Exception e) {
			return "UNKNOWN";
		}
	}

	private static void requireBatch(List<Long> productIds) {
		if (productIds == null || productIds.isEmpty())
			throw new IllegalArgumentException("상품 id가 필요합니다.");
		if (productIds.size() > MAX_BATCH)
			throw new IllegalArgumentException("한 번에 최대 " + MAX_BATCH + "개까지 처리합니다: " + productIds.size());
	}

	private String json(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("퇴출 보고 직렬화 실패", e);
		}
	}

	private static void sleepQuietly(long millis) {
		if (millis <= 0)
			return;
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
