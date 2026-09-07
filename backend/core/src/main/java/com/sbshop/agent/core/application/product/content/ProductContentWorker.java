package com.sbshop.agent.core.application.product.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.product.content.ProductContentData.*;
import com.sbshop.agent.core.domain.product.content.*;
import com.sbshop.agent.core.domain.product.content.ProductContentSnapshot.State;
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
public class ProductContentWorker {
	private final ProductContentSnapshotRepository snapshots;
	private final ProductContentLaneRepository lanes;
	private final ProductContentSource source;
	private final ObjectMapper mapper;
	private final PlatformTransactionManager transactions;

	public record Claim(String id, String token, String sourceUrl, String captured) {
	}

	@Scheduled(fixedDelayString = "${products.content.worker-delay-ms:5000}", initialDelayString = "${products.content.worker-initial-delay-ms:30000}")
	public void tick() {
		var tx = new TransactionTemplate(transactions);
		tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		Claim claim;
		try {
			claim = tx.execute(status -> claim());
		} catch (Exception e) {
			log.warn("콘텐츠 수집 작업 조회 실패: {}", e.getClass().getSimpleName());
			return;
		}
		if (claim == null)
			return;
		try {
			// The durable claim has committed before any HTTP/download/upload starts.
			if (TransactionSynchronizationManager.isActualTransactionActive())
				throw new IllegalStateException("외부 수집 중 DB transaction을 유지할 수 없습니다.");
			var fetched = source.fetch(ProductContentUrls.source(claim.sourceUrl()));
			var captured = mapper.readValue(claim.captured(), Captured.class);
			var proposal = proposal(captured, fetched);
			String payload = mapper.writeValueAsString(proposal);
			if (payload.length() > 2_500_000)
				throw new IllegalArgumentException("수집 내용이 저장 한도를 초과했습니다.");
			tx.executeWithoutResult(status -> finish(claim, payload, proposal.imagesAvailable(),
				proposal.detailAvailable(), null, false, null));
		} catch (Exception e) {
			boolean throttled = e instanceof ProductContentThrottledException;
			Instant serverRetryAfter = e instanceof ProductContentThrottledException limit ? limit.retryAfter() : null;
			String reason = throttled || e instanceof ProductContentFailureException ? e.getMessage()
				: "소싱 콘텐츠 수집에 실패했습니다. 차단·응답 형식·이미지 호스팅 상태를 확인 후 새로 수집하세요.";
			log.warn("콘텐츠 수집 실패 snapshot={}, type={}", claim.id(), e.getClass().getSimpleName());
			try {
				tx.executeWithoutResult(
					status -> finish(claim, null, false, false, reason, throttled, serverRetryAfter));
			} catch (Exception persistence) {
				log.error("콘텐츠 수집 결과 저장 실패 snapshot={}", claim.id(), persistence);
			}
		}
	}

	private Claim claim() {
		var lane = lanes.findLocked("IHB").orElse(null);
		if (lane == null)
			return null;
		Instant now = Instant.now();
		if (lane.getNextAllowedAt() != null && now.isBefore(lane.getNextAllowedAt()))
			return null;
		if (lane.getSnapshotId() != null) {
			if (lane.getLeaseUntil() != null && now.isBefore(lane.getLeaseUntil()))
				return null;
			snapshots.findLocked(lane.getSnapshotId()).filter(s -> s.getState() == State.COLLECTING)
				.ifPresent(s -> s.fail(State.FAILED, "수집 작업이 중단되었거나 시간 제한을 초과했습니다. 성공으로 간주하지 않습니다. 새로 수집하세요."));
			lane.release(now);
			return null;
		}
		var snapshot = snapshots.findFirstByStateOrderByRequestedAtAscIdAsc(State.QUEUED).orElse(null);
		if (snapshot == null)
			return null;
		String token = UUID.randomUUID().toString();
		snapshot.claim(token);
		lane.claim(snapshot.getId(), now);
		return new Claim(snapshot.getId(), token, snapshot.getSourceUrl(), snapshot.getCaptured());
	}

	private Proposed proposal(Captured captured, ProductContentSource.Fetch fetched) {
		if (fetched == null)
			throw new IllegalArgumentException("수집 결과 없음");
		List<String> sourceImages = fetched.sourceImages() == null ? List.of() : fetched.sourceImages();
		List<String> hostedImages = fetched.hostedImages() == null ? List.of() : fetched.hostedImages();
		boolean images = fetched.imagesComplete() && !sourceImages.isEmpty() && sourceImages.size() <= 8
			&& sourceImages.size() == hostedImages.size();
		var notices = new ArrayList<>(fetched.notices() == null ? List.of() : fetched.notices());
		if (images) {
			sourceImages.forEach(ProductContentUrls::sourceImage);
			hostedImages.forEach(ProductContentUrls::hostedImage);
		}
		String html = null;
		if (fetched.detailComplete() && fetched.sanitizedDetailHtml() != null
			&& !fetched.sanitizedDetailHtml().isBlank()) {
			try {
				List<String> htmlImages = images ? hostedImages : captured.current().hostedImages();
				htmlImages.forEach(ProductContentUrls::hostedImage);
				html = captured.generation().generate(htmlImages, fetched.sanitizedDetailHtml());
				if (html.length() > 300_000)
					throw new IllegalArgumentException("자동 생성 상세 HTML이 너무 큽니다.");
			} catch (IllegalArgumentException e) {
				notices.add(e.getMessage());
				html = null;
			}
		}
		notices.add("상품명·원문명·묶음수량·용량은 수집 요청 당시 DB 값으로 고정하여 기존 상세 생성 규칙을 사용했습니다.");
		notices.add("상세 HTML에 최신 이미지가 포함될 수 있습니다. 대표·부가 이미지도 바꾸려면 이미지 항목을 함께 선택하세요.");
		notices.add("수집 성공은 DB 저장이나 외부마켓 반영 성공이 아닙니다. 연결 마켓의 콘텐츠 수정 조건 확인 전에는 적용이 제한됩니다.");
		return new Proposed(new Values(images ? List.copyOf(sourceImages) : List.of(),
			images ? List.copyOf(hostedImages) : List.of(), html), images, html != null, notices);
	}

	private void finish(Claim claim, String payload, boolean images, boolean detail, String failure, boolean throttled,
		Instant serverRetryAfter) {
		var lane = lanes.findLocked("IHB").orElseThrow();
		var snapshot = snapshots.findLocked(claim.id()).orElseThrow();
		if (!Objects.equals(lane.getSnapshotId(), claim.id())
			|| !Objects.equals(snapshot.getClaimToken(), claim.token())
			|| snapshot.getState() != State.COLLECTING)
			return;
		Instant now = Instant.now();
		if (lane.getLeaseUntil() == null || !now.isBefore(lane.getLeaseUntil())) {
			snapshot.fail(State.FAILED, "수집 시간 제한을 초과했습니다. 늦게 도착한 결과는 적용하지 않았습니다. 새로 수집하세요.");
		} else if (failure != null)
			snapshot.fail(State.FAILED, failure);
		else
			snapshot.complete(payload, images, detail, now);
		lane.release(now);
		if (throttled)
			lane.throttle(now, serverRetryAfter);
	}
}
