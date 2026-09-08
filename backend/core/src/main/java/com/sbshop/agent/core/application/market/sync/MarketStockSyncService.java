package com.sbshop.agent.core.application.market.sync;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.product.edit.ProductEditConflictException;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.client.MarketClientRouter;
import com.sbshop.agent.core.domain.market.client.dto.MarketStockRead;
import com.sbshop.agent.core.domain.market.inspection.*;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.market.sync.*;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.*;
import com.sbshop.agent.core.domain.product.enums.StockStatus;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Reviewed sales quantity only. Source stock counts never become marketplace inventory implicitly. */
@Service
@RequiredArgsConstructor
public class MarketStockSyncService {
	public static final Set<MarketType> SUPPORTED = Set.of(MarketType.COUPANG, MarketType.CAFE24,
		MarketType.SMART_STORE, MarketType.ELEVEN_STREET);
	private final MarketStockReviewRepository reviews;
	private final MarketStockTaskRepository tasks;
	private final MarketStockAttemptRepository attempts;
	private final MarketInspectionGateRepository gates;
	private final ProductRepository products;
	private final MarketRegistrationRepository registrations;
	private final MarketClientRouter clients;
	private final ObjectMapper mapper;
	private final PlatformTransactionManager transactions;
	private final JdbcTemplate jdbc;
	private final com.sbshop.agent.core.application.product.edit.ProductEditPlanner editPlanner;
	private final com.sbshop.agent.core.application.product.edit.ProductEditPolicy editPolicy;

	private final com.sbshop.agent.core.domain.product.edit.ProductChangeTargetRepository changeTargets;
	private final com.sbshop.agent.core.domain.product.edit.ProductChangeHistoryRepository changeHistories;

	/** Saving a reviewed quantity edit is its approval. No source-stock or mixed-field target is reinterpreted. */
	public void dispatchSavedQuantities() {
		for (var candidate : changeTargets.findTop50ByStateOrderById("PENDING_DISPATCH")) {
			tx().executeWithoutResult(s -> {
				var product = products.findForEdit(candidate.getProductId()).orElse(null);
				var target = changeTargets.findById(candidate.getId()).orElseThrow();
				if (!"PENDING_DISPATCH".equals(target.getState()) || !handlesSavedQuantityTarget(target, mapper))
					return;
				if (product == null || product.getRevision() != target.getProductRevision()) {
					target.stockOutcome("ACTION_REQUIRED");
					return;
				}
				if (tasks.active(target.getProductId(), target.getMarket()) > 0)
					return;
				var reg = registrations.findForConnectionUpdate(target.getRegistrationId()).orElse(null);
				var plan = plan(target.getProductId(), product, reg, MarketType.valueOf(target.getMarket()));
				if (!savedConnectionMatches(target)) {
					plan = new Plan(plan.productId(), plan.sbCode(), plan.registrationId(), plan.revision(),
						plan.registrationRevision(), plan.market(), plan.listingId(), plan.optionId(),
						plan.identifiers(),
						plan.account(), plan.quantity(), "저장 후 마켓 연결·편집 조건이 변경되었습니다. 최신 연결로 다시 검토하세요.");
				}
				var history = changeHistories.findById(target.getHistoryId()).orElseThrow();
				Instant now = now();
				// The original editor can inspect the automatic task and its failure details.
				var review = reviews.save(
					new MarketStockReview(UUID.randomUUID().toString(), history.getActor(), json(List.of(plan)), now));
				var task = tasks.saveAndFlush(new MarketStockTask(review.getId(), plan.productId(), plan.sbCode(),
					plan.registrationId(), plan.revision(), plan.registrationRevision(), plan.market(),
					plan.listingId(),
					plan.optionId(), plan.identifiers(), plan.account(), plan.quantity(), plan.reason(), now));
				review.commit(now);
				target.dispatchedToStock(task.getId());
				if (plan.reason() != null)
					target.stockOutcome("ACTION_REQUIRED");
			});
		}
	}

	private boolean savedConnectionMatches(com.sbshop.agent.core.domain.product.edit.ProductChangeTarget target) {
		try {
			var links = registrations.findByProductId(target.getProductId());
			String reviewed = mapper.readTree(target.getSnapshot()).path("connectionFingerprint").asText();
			boolean sourceStatus = mapper.readTree(target.getSnapshot()).path("command").hasNonNull("stockStatus");
			return !reviewed.isBlank() && reviewed.equals(editPlanner.fingerprint(links))
				&& (sourceStatus ? editPolicy.sourceObservationRule("stockStatus", links).editable()
					: editPolicy.rule("salesQuantity", links).editable());
		} catch (Exception ignored) {
			return false;
		}
	}

	public static boolean handlesSavedQuantityTarget(
		com.sbshop.agent.core.domain.product.edit.ProductChangeTarget target,
		ObjectMapper mapper) {
		try {
			var changes = mapper.readTree(target.getSnapshot()).path("changes");
			if (!changes.isArray() || changes.isEmpty())
				return false;
			boolean quantity = false;
			for (var change : changes) {
				String field = change.path("field").asText();
				if ("memo".equals(field))
					continue;
				if (!Set.of("salesQuantity", "stockStatus").contains(field))
					return false;
				quantity = true;
			}
			return quantity;
		} catch (Exception ignored) {
			return false;
		}
	}

	private void updateTargets(MarketStockTask task) {
		if (Set.of("CHECK", "VERIFY").contains(task.getState()))
			return;
		if ("CONFIRMED_QUANTITY".equals(task.getState())) {
			for (var target : changeTargets.findByProductIdAndMarket(task.getProductId(), task.getMarket())) {
				if (Set.of("PENDING_DISPATCH", "BATCH_MANAGED", "DISPATCHED", "ACTION_REQUIRED")
					.contains(target.getState())
					&& handlesSavedQuantityTarget(target, mapper)
					&& target.getProductRevision() <= task.getProductRevision())
					target.stockOutcome(target.getProductRevision() == task.getProductRevision()
						? "CONFIRMED_QUANTITY" : "SUPERSEDED_BY_CURRENT");
			}
		} else {
			for (var target : changeTargets.findByStockTaskId(task.getId())) {
				if ("DISPATCHED".equals(target.getState()))
					target.stockOutcome("ACTION_REQUIRED");
			}
		}
	}

	public record Plan(Long productId, String sbCode, Long registrationId, long revision, long registrationRevision,
		String market, String listingId, String optionId, String identifiers, String account,
		Integer quantity, String reason) {
	}
	public record Item(Long id, Long productId, String sbCode, String market, String listingId, long revision,
		Integer expectedQuantity, Integer observedQuantity, String state, String detail, int writes, int reads,
		Instant nextRunAt, Instant checkedAt) {
	}
	public record Review(String id, String actor, Instant createdAt, Instant expiresAt, boolean committed,
		List<Item> items, int total) {
	}
	public record Claim(Long taskId, String token, MarketType market, String sbCode, String listingId, String optionId,
		String account, int quantity) {
	}

	public Review preview(List<Long> ids, Set<MarketType> markets, String actor) {
		validate(ids, markets, actor);
		return tx().execute(s -> {
			var plans = new ArrayList<Plan>();
			for (Long id : ids.stream().distinct().sorted().toList()) {
				Product p = products.findById(id).orElse(null);
				for (MarketType market : markets.stream().sorted().toList()) {
					var r = registrations.findByProductIdAndMarketType(id, market).orElse(null);
					plans.add(plan(id, p, r, market));
				}
			}
			var review = reviews.save(new MarketStockReview(UUID.randomUUID().toString(), actor, json(plans), now()));
			return view(review);
		});
	}

	public Review commit(String id, String actor) {
		return tx().execute(s -> {
			var review = reviews.lock(id).orElseThrow(() -> new IllegalArgumentException("수량 검토가 없습니다."));
			requireActor(review, actor);
			if (review.getCommittedAt() != null)
				return view(review);
			Instant now = now();
			if (!review.getExpiresAt().isAfter(now))
				throw new ProductEditConflictException("검토가 만료되었습니다. 최신 값으로 다시 검토하세요.");
			for (Plan p : plans(review)) {
				Product product = products.findForEdit(p.productId()).orElse(null);
				MarketRegistration r = p.registrationId() == null ? null
					: registrations.findForConnectionUpdate(p.registrationId()).orElse(null);
				String skip = p.reason();
				if (skip == null && !same(p, plan(p.productId(), product, r, MarketType.valueOf(p.market()))))
					skip = "검토 후 상품·수량·연결·계정이 변경되었습니다. 다시 검토하세요.";
				if (skip == null && tasks.active(p.productId(), p.market()) > 0)
					skip = "이 상품·마켓의 수량 작업이 이미 진행 중입니다.";
				tasks.save(new MarketStockTask(id, p.productId(), p.sbCode(), p.registrationId(), p.revision(),
					p.registrationRevision(), p.market(), p.listingId(), p.optionId(), p.identifiers(), p.account(),
					p.quantity(), skip, now));
			}
			review.commit(now);
			return view(review);
		});
	}

	public Review get(String id, String actor) {
		return tx().execute(s -> {
			var review = reviews.findById(id).orElseThrow(() -> new IllegalArgumentException("수량 작업이 없습니다."));
			requireActor(review, actor);
			return view(review);
		});
	}

	public List<Review> recent(String actor) {
		requireActorName(actor);
		return tx().execute(s -> reviews.findTop20ByActorOrderByCreatedAtDesc(actor).stream()
			.map(
				r -> new Review(r.getId(), r.getActor(), r.getCreatedAt(), r.getExpiresAt(), r.getCommittedAt() != null,
					List.of(),
					r.getCommittedAt() == null ? plans(r).size() : Math.toIntExact(tasks.countByReviewId(r.getId()))))
			.toList());
	}

	public List<MarketStockAttempt> history(Long id, String actor) {
		return tx().execute(s -> {
			var task = tasks.findById(id).orElseThrow(() -> new IllegalArgumentException("수량 작업이 없습니다."));
			requireActor(reviews.findById(task.getReviewId()).orElseThrow(), actor);
			return attempts.findByTaskIdOrderById(id);
		});
	}

	/** All HTTP calls execute outside transactions, including calls made by a transactional caller. */
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public void processOne(MarketType market) {
		if (TransactionSynchronizationManager.isActualTransactionActive())
			throw new IllegalStateException("수량 전송은 데이터베이스 트랜잭션 밖에서 실행해야 합니다.");
		Claim c = claim(market);
		if (c == null)
			return;
		try {
			var client = clients.getClient(market);
			if (!Objects.equals(c.account(), client.inspectionAccountReference())) {
				finish(c, "STALE", "연동 계정이 변경되었습니다.", null, null);
				return;
			}
			MarketStockRead read = client.readStockQuantity(c.listingId(), c.optionId(), c.sbCode());
			if (!validRead(c, read) || !Objects.equals(c.account(), client.inspectionAccountReference())) {
				finish(c, "UNKNOWN", "정확한 계정·품목의 유효한 재고수량을 확인하지 못했습니다.", null, null);
				return;
			}
			if (!read.writable()) {
				finish(c, "BLOCKED", read.reason(), read, null);
				return;
			}
			if (read.quantity() == c.quantity()) {
				finish(c, "CONFIRMED_QUANTITY", "마켓 재조회 수량이 목표 판매용 수량과 일치합니다. 다른 필드의 일치를 뜻하지 않습니다.", read, null);
				return;
			}
			try {
				client.writeStockQuantity(c.listingId(), read.optionId(), c.sbCode(), c.quantity(), c.account(), () -> {
					if (!beginWrite(c, read))
						throw new WriteAborted();
				});
				finish(c, "VERIFY", "수량 전송 요청이 종료되었습니다. 실제 수량을 다시 조회합니다.", read, null);
			} catch (WriteAborted aborted) {
				// The guard already persisted STALE/FAILED_MISMATCH, or a newer lease now owns the task.
			} catch (UnsupportedOperationException blocked) {
				finish(c, "BLOCKED", blocked.getMessage(), read, null);
			} catch (Exception e) {
				finish(c, "VERIFY", "수량 전송 결과를 재조회합니다. " + message(e), read, failure(e));
			}
		} catch (UnsupportedOperationException blocked) {
			finish(c, "BLOCKED", blocked.getMessage(), null, null);
		} catch (Exception e) {
			finish(c, rejected(failure(e)) ? "BLOCKED" : "VERIFY", "수량 조회 실패: " + message(e), null, failure(e));
		}
	}

	public Claim claim(MarketType market) {
		if (!SUPPORTED.contains(market))
			return null;
		ensureGate(market);
		return tx().execute(s -> {
			var gate = gates.lock(gateId(market)).orElseThrow();
			Instant now = now();
			if (!gate.available(now))
				return null;
			var due = tasks.due(market.name(), now, PageRequest.of(0, 1));
			if (due.isEmpty())
				return null;
			var task = due.getFirst();
			if (!SupplierBatchMarketGate.mayStart(jdbc, task.getReviewId()))
				return null;
			String invalid = invalid(task);
			if (invalid != null || task.getReads() >= 9) {
				task.finish(invalid == null ? "UNKNOWN" : "STALE", invalid == null
					? "재조회 한도에 도달했습니다. 최신 값으로 새 검토를 만들어 재시도하세요." : invalid, now, now);
				updateTargets(task);
				attempts.save(new MarketStockAttempt(task.getId(), task.getState(), task.getDetail(), now));
				return null;
			}
			String token = UUID.randomUUID().toString();
			Instant until = now.plusSeconds(180);
			gate.claim(token, until);
			task.claim(token, until);
			attempts.save(new MarketStockAttempt(task.getId(), "READ_STARTED", "마켓 실제 판매용 수량 조회 시작", now));
			return new Claim(task.getId(), token, market, task.getSbCode(), task.getListingId(),
				task.getResolvedOptionId() == null ? task.getOptionId() : task.getResolvedOptionId(),
				task.getAccountReference(), task.getExpectedQuantity());
		});
	}

	/** Called by the adapter AFTER remote identity/status checks and immediately BEFORE the PUT. */
	public boolean beginWrite(Claim c, MarketStockRead read) {
		return tx().execute(s -> {
			var gate = gates.lock(gateId(c.market())).orElseThrow();
			var task = tasks.findById(c.taskId()).orElseThrow();
			Instant now = now();
			if (!gate.owns(c.token(), now) || !task.owns(c.token(), now))
				return false;
			if (!SupplierBatchMarketGate.mayStart(jdbc, task.getReviewId())) {
				task.finish("VERIFY", "배치가 일시정지 중입니다. 재개 후 현재 값을 다시 확인합니다.", now, now);
				attempts.save(new MarketStockAttempt(task.getId(), "BATCH_PAUSED", task.getDetail(), now));
				gate.release(now.plusSeconds(2));
				return false;
			}
			if (gate.getNextAllowedAt().isAfter(now)) {
				task.finish("VERIFY", "공유 호출 제한 대기 중입니다. 제한 해제 후 수량을 다시 조회합니다.", now, gate.getNextAllowedAt());
				attempts.save(new MarketStockAttempt(task.getId(), "THROTTLED", task.getDetail(), now));
				gate.release(gate.getNextAllowedAt());
				return false;
			}
			if (task.isWriteRejected()) {
				task.finish("BLOCKED", "마켓이 앞선 수량 전송을 명시적으로 거절했고 재조회 수량도 불일치합니다. 오류 원인을 확인한 뒤 새 검토로 재시도하세요.", now, now);
				updateTargets(task);
				attempts.save(new MarketStockAttempt(task.getId(), "BLOCKED", task.getDetail(), now));
				gate.release(now.plusSeconds(2));
				return false;
			}
			String invalid = invalid(task);
			if (invalid == null && (!validRead(c, read) || !read.writable()
				|| task.getResolvedOptionId() != null && !task.getResolvedOptionId().equals(read.optionId())))
				invalid = "조회 품목·계정·판매 상태가 변경되었습니다. 다시 검토하세요.";
			if (invalid != null || task.getWrites() >= 3) {
				task.finish(invalid == null ? "FAILED_MISMATCH" : "STALE", invalid == null
					? "3회 전송 후에도 수량이 일치하지 않습니다. 마켓 재고 정책·판매 상태를 확인하세요." : invalid, now, now);
				updateTargets(task);
				attempts.save(new MarketStockAttempt(task.getId(), task.getState(), task.getDetail(), now));
				gate.release(now.plusSeconds(2));
				return false;
			}
			task.observed(read.quantity(), read.optionId(), now);
			task.beginWrite(now);
			gate.claim(c.token(), now.plusSeconds(180));
			attempts.save(new MarketStockAttempt(task.getId(), "WRITE_STARTED", "판매용 수량 " + task.getExpectedQuantity()
				+ " 전송 시작. 응답 유실 시 먼저 재조회합니다.", now));
			return true;
		});
	}

	public void finish(Claim c, String state, String detail, MarketStockRead read, MarketTransferFailure error) {
		tx().executeWithoutResult(s -> {
			var gate = gates.lock(gateId(c.market())).orElseThrow();
			var task = tasks.findById(c.taskId()).orElseThrow();
			Instant now = now();
			Instant next = now.plusSeconds(Math.min(300, 10L << Math.min(5, Math.max(0, task.getReads() - 1)))
				+ java.util.concurrent.ThreadLocalRandom.current().nextInt(6));
			if (error != null && error.getRetryAfter() != null && error.getRetryAfter().isAfter(next))
				next = error.getRetryAfter();
			// Even an old request's 429 carries account-wide throttling evidence. Keep any newer lease intact.
			if (error != null && error.rateLimited())
				gate.deferUntil(next);
			if (!gate.owns(c.token(), now) || !task.owns(c.token(), now))
				return;
			String invalid = invalid(task);
			if (invalid == null && read != null && (!validRead(c, read)
				|| task.getResolvedOptionId() != null && !task.getResolvedOptionId().equals(read.optionId())))
				invalid = "조회 품목·계정이 변경되었습니다. 다시 검토하세요.";
			String finalState = invalid == null ? state : "STALE";
			String finalDetail = invalid == null ? detail : invalid;
			// Public completion cannot create proof without a matching, writable, exact-option observation.
			if ("CONFIRMED_QUANTITY".equals(finalState)
				&& (read == null || !read.writable() || read.quantity() != c.quantity())) {
				finalState = "UNKNOWN";
				finalDetail = "수량 일치 재조회 증거가 없어 완료 처리할 수 없습니다.";
			}
			if (read != null && validRead(c, read))
				task.observed(read.quantity(), read.optionId(), now);
			if ("VERIFY".equals(finalState)) {
				task.receipt(detail);
				if (rejected(error))
					task.rejectWrite();
				if (task.getReads() >= 9) {
					finalState = "UNKNOWN";
					finalDetail = "재조회 한도에 도달했습니다. " + detail;
				}
			}
			task.finish(finalState, finalDetail, now, next);
			updateTargets(task);
			attempts.save(new MarketStockAttempt(task.getId(), finalState, task.getDetail(), now));
			gate.release(error != null && error.rateLimited() ? next : now.plusSeconds(2));
		});
	}

	private boolean validRead(Claim c, MarketStockRead read) {
		return read != null && read.quantity() != null && read.quantity() >= 0
			&& Objects.equals(c.account(), read.accountReference()) && read.optionId() != null
			&& !read.optionId().isBlank()
			&& (c.optionId() == null || c.optionId().equals(read.optionId()));
	}

	private String invalid(MarketStockTask t) {
		var p = products.findForEdit(t.getProductId()).orElse(null);
		var r = t.getRegistrationId() == null ? null
			: registrations.findForConnectionUpdate(t.getRegistrationId()).orElse(null);
		Plan current = plan(t.getProductId(), p, r, MarketType.valueOf(t.getMarket()));
		if (current.reason() != null)
			return current.reason();
		return current.revision() != t.getProductRevision()
			|| current.registrationRevision() != t.getRegistrationRevision()
			|| !Objects.equals(current.sbCode(), t.getSbCode())
			|| !Objects.equals(current.registrationId(), t.getRegistrationId())
			|| !Objects.equals(current.identifiers(), t.getIdentifiers())
			|| !Objects.equals(current.account(), t.getAccountReference())
			|| !Objects.equals(current.quantity(), t.getExpectedQuantity())
				? "상품·수량·연결·계정이 변경되어 이 작업을 중지했습니다. 최신 값으로 다시 검토하세요." : null;
	}

	private Plan plan(Long id, Product p, MarketRegistration r, MarketType market) {
		String account = account(market), reason = null;
		Integer quantity = null;
		if (p == null || p.isDeleted())
			reason = "상품이 없거나 폐기되었습니다.";
		else if (!SUPPORTED.contains(market))
			reason = "수량 단독 수정·재조회 API 계약 확인이 필요합니다.";
		else if (p.isSourceGone())
			reason = "소싱처 상품 소실 상태입니다. 재고 상태를 확인한 뒤 검토하세요.";
		else if (p.getLastCrawlError() != null && !p.getLastCrawlError().isBlank())
			reason = "최근 수집에 실패했습니다. 재고 상태를 다시 수집한 뒤 검토하세요.";
		else if (p.getStockStatus() == null)
			reason = "소싱처 재고 상태가 확인되지 않았습니다.";
		else if (p.getSalesQuantity() == null || p.getSalesQuantity() < 0 || p.getSalesQuantity() > 999999)
			reason = "유효한 판매용 수량을 먼저 설정하세요.";
		else if (r == null || r.getConnectionState().detached())
			reason = "현재 연결된 마켓 상품이 없습니다. 등록 후보에서 확인하세요.";
		else if (!id.equals(r.getProductId()) || market != r.getMarketType())
			reason = "상품·마켓 연결 대상이 일치하지 않습니다.";
		else if (r.connectionWriteBlock() != null)
			reason = r.connectionWriteBlock();
		else if (r.extractLiveLookupId() == null)
			reason = "등록 상품번호를 확인할 수 없습니다.";
		else if (market == MarketType.COUPANG && r.identifier("vendorItemId") == null)
			reason = "쿠팡 옵션 번호를 확인할 수 없습니다.";
		else if (account == null || account.isBlank())
			reason = "마켓 계정 설정을 확인할 수 없습니다.";
		else if (p.getStockStatus() == StockStatus.IN_STOCK)
			quantity = p.getSalesQuantity();
		else if (p.getStockStatus() == StockStatus.OUT_OF_STOCK)
			quantity = 0;
		else
			reason = "소싱처 재고 상태가 확인되지 않았습니다.";
		if (reason == null && market == MarketType.ELEVEN_STREET && quantity != null && quantity == 0)
			reason = "11번가 0개 반영과 품절·판매 상태 전환 계약 확인이 필요합니다. 판매 재개·중지를 자동 실행하지 않고 보류합니다.";
		String optionId = r == null ? null : r.identifier(switch (market) {
			case COUPANG -> "vendorItemId";
			case ELEVEN_STREET -> "prdStckNo";
			default -> "variant_code";
		});
		return new Plan(id, p == null ? null : p.getSbCode(), r == null ? null : r.getId(),
			p == null ? 0 : p.getRevision(),
			r == null ? 0 : r.getRevision(), market.name(), r == null ? null : r.extractLiveLookupId(),
			optionId,
			r == null ? null : r.getMarketIdentifiers(), account, quantity, reason);
	}

	private boolean same(Plan a, Plan b) {
		return b.reason() == null && a.revision() == b.revision()
			&& a.registrationRevision() == b.registrationRevision()
			&& Objects.equals(a.sbCode(), b.sbCode()) && Objects.equals(a.registrationId(), b.registrationId())
			&& Objects.equals(a.identifiers(), b.identifiers()) && Objects.equals(a.account(), b.account())
			&& Objects.equals(a.quantity(), b.quantity());
	}

	private Review view(MarketStockReview r) {
		List<Item> items = r.getCommittedAt() == null ? plans(r).stream()
			.map(p -> new Item(null, p.productId(), p.sbCode(), p.market(), p.listingId(), p.revision(), p.quantity(),
				null,
				p.reason() == null ? "READY" : "SKIPPED", p.reason() == null
					? (p.quantity() == 0 ? "목표 0개를 검토합니다. 실행 시 현재 판매 상태·품목을 확인하며 판매 재개는 하지 않습니다."
						: "소싱처 실재고와 별도인 판매용 수량을 전송합니다. 현재 판매 상태·품목을 먼저 확인합니다.")
					: p.reason(),
				0, 0, null, null))
			.toList()
			: tasks.findByReviewIdOrderById(r.getId()).stream()
				.map(t -> new Item(t.getId(), t.getProductId(), t.getSbCode(), t.getMarket(), t.getListingId(),
					t.getProductRevision(), t.getExpectedQuantity(), t.getObservedQuantity(), t.getState(),
					t.getDetail(),
					t.getWrites(), t.getReads(), t.getNextRunAt(), t.getCheckedAt()))
				.toList();
		return new Review(r.getId(), r.getActor(), r.getCreatedAt(), r.getExpiresAt(), r.getCommittedAt() != null,
			items, items.size());
	}

	private List<Plan> plans(MarketStockReview r) {
		try {
			return mapper.readValue(r.getSnapshot(), new TypeReference<List<Plan>>() {});
		} catch (Exception e) {
			throw new IllegalStateException("저장된 수량 검토 내용을 읽지 못했습니다.", e);
		}
	}

	private String json(Object o) {
		try {
			return mapper.writeValueAsString(o);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private String account(MarketType market) {
		try {
			return clients.hasClient(market) ? clients.getClient(market).inspectionAccountReference() : null;
		} catch (Exception e) {
			return null;
		}
	}

	private static String gateId(MarketType market) {
		return market.name() + "_ORIGIN_READ";
	}

	private void ensureGate(MarketType market) {
		if (gates.existsById(gateId(market)))
			return;
		try {
			tx().executeWithoutResult(s -> gates.saveAndFlush(new MarketInspectionGate(gateId(market), now())));
		} catch (DataIntegrityViolationException race) {
			if (!gates.existsById(gateId(market)))
				throw race;
		}
	}

	private Instant now() {
		return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", java.sql.Timestamp.class).toInstant();
	}

	private TransactionTemplate tx() {
		var tx = new TransactionTemplate(transactions);
		tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		return tx;
	}

	private MarketTransferFailure failure(Exception e) {
		return e instanceof MarketTransferFailure f ? f : null;
	}

	private boolean rejected(MarketTransferFailure error) {
		return error != null && error.getCode() != null && !error.rateLimited()
			&& (error.getCode().matches("HTTP_4[0-9]{2}")
				|| Set.of("ELEVENST_BUSINESS_400", "ELEVENST_BUSINESS_404", "ELEVENST_BUSINESS_500")
					.contains(error.getCode()));
	}

	private String message(Exception e) {
		return e instanceof MarketTransferFailure || e instanceof UnsupportedOperationException ? e.getMessage()
			: "처리 오류. 원본 작업 로그를 확인하세요.";
	}

	private void requireActor(MarketStockReview review, String actor) {
		requireActorName(actor);
		if (!review.getActor().equals(actor))
			throw new ProductEditConflictException("검토한 작업자만 조회·반영할 수 있습니다.");
	}

	private void requireActorName(String actor) {
		if (actor == null || actor.isBlank() || actor.length() > 200)
			throw new IllegalArgumentException("인증된 작업자가 필요합니다.");
	}

	private void validate(List<Long> ids, Set<MarketType> markets, String actor) {
		requireActorName(actor);
		if (ids == null || ids.isEmpty() || ids.size() > 500 || ids.stream().anyMatch(i -> i == null || i <= 0))
			throw new IllegalArgumentException("상품을 1~500개 선택하세요.");
		if (markets == null || markets.isEmpty() || markets.stream().anyMatch(Objects::isNull))
			throw new IllegalArgumentException("마켓을 선택하세요.");
	}

	private static final class WriteAborted extends RuntimeException {}
}
