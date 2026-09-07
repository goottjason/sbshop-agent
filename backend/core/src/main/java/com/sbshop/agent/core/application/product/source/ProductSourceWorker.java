package com.sbshop.agent.core.application.product.source;

import com.sbshop.agent.core.application.product.content.*;
import com.sbshop.agent.core.domain.product.content.ProductContentLaneRepository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.product.source.ProductSourceData.*;
import com.sbshop.agent.core.domain.product.source.*;
import com.sbshop.agent.core.domain.product.source.ProductSourceSnapshot.State;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class ProductSourceWorker {
	private final ProductSourceSnapshotRepository snapshots;
	private final ProductContentLaneRepository lanes;
	private final ProductSourceObservationSource source;
	private final ObjectMapper mapper;
	private final PlatformTransactionManager transactions;

	public record Claim(String id, String token, String sourceUrl, String vendor, String captured) {
	}

	@Scheduled(fixedDelayString = "${products.source.worker-delay-ms:5000}", initialDelayString = "${products.source.worker-initial-delay-ms:30000}")
	public void tick() {
		var tx = new TransactionTemplate(transactions);
		tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		Claim claim;
		try {
			claim = tx.execute(status -> claim());
		} catch (Exception e) {
			log.warn("가격·재고 수집 작업 조회 실패: {}", e.getClass().getSimpleName());
			return;
		}
		if (claim == null)
			return;
		try {
			// The durable claim has committed before any HTTP/download/upload starts.
			if (TransactionSynchronizationManager.isActualTransactionActive())
				throw new IllegalStateException("외부 수집 중 DB transaction을 유지할 수 없습니다.");
			var fetched = ProductSourceHttpGuard.scoped(
				() -> ProductSourceVendorGate.beforeHttp(lanes, transactions, claim.vendor(), claim.token()),
				() -> source.fetch(com.sbshop.agent.core.domain.product.enums.VendorType.valueOf(claim.vendor()),
					ProductContentUrls.source(
						com.sbshop.agent.core.domain.product.enums.VendorType.valueOf(claim.vendor()),
						claim.sourceUrl())));
			var captured = mapper.readValue(claim.captured(), Captured.class);
			var proposal = proposal(captured, fetched,
				com.sbshop.agent.core.domain.product.enums.VendorType.valueOf(claim.vendor()));
			String payload = mapper.writeValueAsString(proposal);
			if (payload.length() > 2_500_000)
				throw new IllegalArgumentException("수집 내용이 저장 한도를 초과했습니다.");
			tx.executeWithoutResult(status -> finish(claim, payload, proposal.priceAvailable(),
				proposal.stockAvailable(), null, false, null));
		} catch (Exception e) {
			boolean throttled = e instanceof ProductContentThrottledException;
			Instant serverRetryAfter = e instanceof ProductContentThrottledException limit ? limit.retryAfter() : null;
			String reason = throttled || e instanceof ProductContentFailureException ? e.getMessage()
				: "소싱 가격·재고 수집에 실패했습니다. 차단·응답 형식·환율 조회 상태를 확인 후 새로 수집하세요.";
			log.warn("가격·재고 수집 실패 snapshot={}, type={}", claim.id(), e.getClass().getSimpleName());
			try {
				tx.executeWithoutResult(
					status -> finish(claim, null, false, false, reason, throttled, serverRetryAfter));
			} catch (Exception persistence) {
				log.error("가격·재고 수집 결과 저장 실패 snapshot={}", claim.id(), persistence);
			}
		}
	}

	private Claim claim() {
		var lane = lanes.findLocked("PRICE_STOCK").orElse(null);
		if (lane == null)
			return null;
		Instant now = Instant.now();
		if (lane.getNextAllowedAt() != null && now.isBefore(lane.getNextAllowedAt()))
			return null;
		if (lane.getSnapshotId() != null) {
			if (lane.getLeaseUntil() != null && now.isBefore(lane.getLeaseUntil()))
				return null;
			snapshots.findLocked(lane.getSnapshotId()).filter(s -> s.getState() == State.COLLECTING)
				.ifPresent(s -> {
					ProductSourceVendorGate.finish(lanes, s.getVendor(), s.getClaimToken(), now, false, null);
					s.fail(State.FAILED, "수집 작업이 중단되었거나 시간 제한을 초과했습니다. 성공으로 간주하지 않습니다. 새로 수집하세요.");
				});
			lane.release(now);
			return null;
		}
		// Choose the oldest available vendor rather than letting one vendor's 429 stop every source.
		var candidates = Arrays.stream(com.sbshop.agent.core.domain.product.enums.VendorType.values())
			.filter(ProductContentUrls::supports)
			.map(v -> snapshots.findFirstByStateAndVendorOrderByRequestedAtAscIdAsc(State.QUEUED, v.name()))
			.flatMap(Optional::stream)
			.sorted(
				Comparator.comparing(ProductSourceSnapshot::getRequestedAt).thenComparing(ProductSourceSnapshot::getId))
			.toList();
		for (var snapshot : candidates) {
			String token = UUID.randomUUID().toString();
			if (!ProductSourceVendorGate.claim(lanes, snapshot.getVendor(), token, now))
				continue;
			snapshot.claim(token);
			lane.claim(snapshot.getId(), now);
			return new Claim(snapshot.getId(), token, snapshot.getSourceUrl(), snapshot.getVendor(),
				snapshot.getCaptured());
		}
		return null;
	}

	private Proposed proposal(Captured captured, Observed fetched,
		com.sbshop.agent.core.domain.product.enums.VendorType vendor) {
		if (fetched == null)
			throw new IllegalArgumentException("수집 결과 없음");
		var notices = new ArrayList<>(fetched.notices() == null ? List.<String>of() : fetched.notices());
		boolean stock = fetched.stockStatus() != null;
		if (fetched.stock() != null && (fetched.stock() < 0 || fetched.stock() > 999999))
			throw new IllegalArgumentException("소싱 실재고가 유효한 범위를 벗어났습니다.");
		java.math.BigDecimal cost = null;
		if (fetched.goodsPriceKrw() != null) {
			try {
				// Source adapters normalize FX before converting goods, never only at persistence time.
				if (fetched.exchangeRate() == null || fetched.exchangeRate().signum() <= 0
					|| fetched.exchangeRate().stripTrailingZeros().scale() > 2)
					throw new IllegalArgumentException("소싱 환율의 저장 정밀도 검증이 완료되지 않았습니다. 새로 수집하세요.");
				if (captured.shipping() == null || !Objects.equals(captured.shipping().currency(), fetched.currency()))
					throw new IllegalArgumentException("소싱 통화와 배송비 정책이 없거나 일치하지 않습니다.");
				if (captured.bundleQuantity() == null || captured.bundleQuantity() < 1)
					throw new IllegalArgumentException("묶음수량이 없어 개당 원가를 계산할 수 없습니다.");
				cost = com.sbshop.agent.core.domain.pricing.LandedCostCalculator.buyPricePerUnit(
					fetched.goodsPriceKrw(),
					captured.weightKg(), captured.bundleQuantity(), captured.shipping().policy(),
					fetched.exchangeRate());
				if (cost.signum() <= 0 || cost.compareTo(new java.math.BigDecimal("9999999999999.99")) > 0)
					throw new IllegalArgumentException("계산한 매입 원가가 저장 범위를 벗어났습니다.");
			} catch (IllegalArgumentException | IllegalStateException e) {
				notices.add(e.getMessage());
				cost = null;
			}
		}
		if (fetched.stock() == null)
			notices.add("소싱처의 실제 수량은 관측되지 않았습니다. 기존 실재고와 판매용 설정 수량을 유지합니다.");
		notices.add("매입 원가는 확인된 상품가격에 기존 소싱 배송비 규칙을 적용한 값입니다. 가격 정책·최소마진에 따른 판매가 변경은 저장 전 검토에서 확인합니다.");
		notices.add("품절·재입고는 확인된 재고 상태만 반영합니다. 상품 부재·차단·미확인 응답을 품절 또는 0개로 변환하지 않습니다.");
		return new Proposed(
			new Values(cost, cost == null ? null : fetched.exchangeRate(), fetched.stockStatus(), fetched.stock()),
			cost != null, stock, notices, fetched.pricingEvidence());
	}

	private void finish(Claim claim, String payload, boolean images, boolean detail, String failure, boolean throttled,
		Instant serverRetryAfter) {
		var lane = lanes.findLocked("PRICE_STOCK").orElseThrow();
		var snapshot = snapshots.findLocked(claim.id()).orElseThrow();
		boolean sourceLeaseValid = ProductSourceVendorGate.finish(lanes, claim.vendor(), claim.token(), Instant.now(),
			throttled, serverRetryAfter);
		if (!Objects.equals(lane.getSnapshotId(), claim.id())
			|| !Objects.equals(snapshot.getClaimToken(), claim.token())
			|| snapshot.getState() != State.COLLECTING)
			return;
		Instant now = Instant.now();
		if (!sourceLeaseValid || lane.getLeaseUntil() == null || !now.isBefore(lane.getLeaseUntil())) {
			snapshot.fail(State.FAILED, "수집 시간 제한을 초과했습니다. 늦게 도착한 결과는 적용하지 않았습니다. 새로 수집하세요.");
		} else if (failure != null)
			snapshot.fail(State.FAILED, failure);
		else
			snapshot.complete(payload, images, detail, now);
		lane.release(now);
	}
}
