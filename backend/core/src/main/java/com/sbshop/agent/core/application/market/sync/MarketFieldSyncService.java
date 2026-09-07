package com.sbshop.agent.core.application.market.sync;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.product.edit.*;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.client.MarketClientRouter;
import com.sbshop.agent.core.domain.market.client.dto.*;
import com.sbshop.agent.core.domain.market.inspection.*;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.market.sync.*;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.*;
import com.sbshop.agent.core.domain.product.edit.*;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;

/** Fixed field deltas, explicit review consent, durable write intent and independent field/approval proof. */
@Service
@RequiredArgsConstructor
public class MarketFieldSyncService {
	public static final Set<MarketType> SUPPORTED = Set.of(MarketType.SMART_STORE, MarketType.COUPANG,
		MarketType.CAFE24, MarketType.ELEVEN_STREET);
	private final MarketFieldReviewRepository reviews;
	private final MarketFieldTaskRepository tasks;
	private final MarketFieldAttemptRepository attempts;
	private final MarketInspectionGateRepository gates;
	private final ProductRepository products;
	private final MarketRegistrationRepository registrations;
	private final ProductChangeTargetRepository targets;
	private final ProductChangeHistoryRepository histories;
	private final ProductEditPlanner editPlanner;
	private final MarketClientRouter clients;
	private final ObjectMapper mapper;
	private final PlatformTransactionManager transactions;
	private final JdbcTemplate jdbc;
	private final jakarta.persistence.EntityManager entityManager;

	public record Item(Long id, Long productId, String sbCode, String market, String listingId, long revision,
		Set<String> fields, Map<String, String> expectedValues, Map<String, String> observedValues, String state,
		String detail,
		boolean requiresApproval, int writes, int reads, Instant nextRunAt, Instant checkedAt) {
	}
	public record Review(String id, String actor, Instant createdAt, Instant expiresAt, boolean committed,
		boolean preparing,
		List<Item> items, int total) {
	}
	public record Claim(Long taskId, String token, MarketType market, String phase, String sbCode, String listingId,
		String optionId, String account, Set<String> fields, Product product, PreparedMarketFields prepared) {
	}

	/** Preparation is queued, so image hosting and provider reads never run inside the request transaction. */
	public Review preview(List<Long> ids, Set<MarketType> markets, Set<String> fields, String actor) {
		validate(ids, markets, fields, actor);
		return tx().execute(s -> {
			Instant now = now();
			var review = reviews.save(new MarketFieldReview(UUID.randomUUID().toString(), actor, false, now));
			for (Long id : ids.stream().distinct().sorted().toList()) {
				var product = products.findForEdit(id).orElse(null);
				for (var market : markets.stream().sorted().toList()) {
					var reg = registrations.findByProductIdAndMarketType(id, market).orElse(null);
					String reason = localBlock(id, product, reg, market);
					if (reason == null && tasks.active(id, market.name()) > 0)
						reason = "이 상품·마켓의 필드 검토 또는 전송이 이미 진행 중입니다.";
					tasks.save(newTask(review.getId(), null, id, product, reg, market, fields, reason, now));
				}
			}
			return view(review);
		});
	}

	public Review commit(String id, boolean acceptApproval, String actor) {
		return tx().execute(s -> {
			var review = reviews.lock(id).orElseThrow(() -> new IllegalArgumentException("필드 검토가 없습니다."));
			requireActor(review, actor);
			if (review.getCommittedAt() != null)
				return view(review);
			var items = tasks.findByReviewIdOrderById(id);
			Instant now = now();
			if (items.stream().anyMatch(t -> "PREPARE".equals(t.getState())))
				throw new ProductEditConflictException("마켓별 전송값을 준비 중입니다. 준비 완료 후 다시 검토하세요.");
			if (!review.getExpiresAt().isAfter(now))
				throw new ProductEditConflictException("검토가 만료되었습니다. 최신 값으로 새 검토를 만드세요.");
			if (!acceptApproval && items.stream().anyMatch(t -> "DRAFT".equals(t.getState()) && t.isRequiresApproval()))
				throw new ProductEditConflictException("마켓 심사가 필요한 필드가 있습니다. 준비된 내용과 심사 요청을 명시적으로 승인하세요.");
			for (var task : items) {
				if (!"DRAFT".equals(task.getState()))
					continue;
				String invalid = invalid(task);
				if (invalid != null)
					end(task, "STALE", invalid, now, now);
				else {
					task.queue(now);
					record(task, "QUEUED", "검토한 필드와 심사 동의가 저장되었습니다.", null, now);
					markDispatched(task);
				}
			}
			review.commit(acceptApproval, now);
			return view(review);
		});
	}

	public Review get(String id, String actor) {
		return tx().execute(s -> {
			var r = reviews.findById(id).orElseThrow(() -> new IllegalArgumentException("필드 작업이 없습니다."));
			requireActor(r, actor);
			return view(r);
		});
	}

	public List<Review> recent(String actor) {
		requireActorName(actor);
		return tx().execute(s -> reviews.findTop20ByActorOrderByCreatedAtDesc(actor).stream().map(this::view).toList());
	}

	public List<MarketFieldAttempt> history(Long id, String actor) {
		return tx().execute(s -> {
			var t = tasks.findById(id).orElseThrow(() -> new IllegalArgumentException("필드 작업이 없습니다."));
			requireActor(reviews.findById(t.getReviewId()).orElseThrow(), actor);
			return attempts.findByTaskIdOrderById(id);
		});
	}

	/** Only disjoint non-price/non-quantity target snapshots belong to this dispatcher. */
	public static boolean handlesSavedFieldsTarget(ProductChangeTarget target, ObjectMapper mapper) {
		if (target == null || SUPPORTED.stream().noneMatch(m -> m.name().equals(target.getMarket())))
			return false;
		try {
			return !targetFields(target, mapper).isEmpty();
		} catch (RuntimeException ignored) {
			return false;
		}
	}

	private static Set<String> targetFields(ProductChangeTarget target, ObjectMapper mapper) {
		try {
			var changes = mapper.readTree(target.getSnapshot()).path("changes");
			if (!changes.isArray() || changes.isEmpty())
				return Set.of();
			Set<String> result = new TreeSet<>();
			for (var change : changes) {
				String field = change.path("field").asText();
				if ("memo".equals(field))
					continue;
				if (!validField(field) || ProductEditPolicy.PRICE_FIELDS.contains(field)
					|| Set.of("salesQuantity", "stock", "stockStatus").contains(field))
					return Set.of();
				result.add(field);
			}
			return Set.copyOf(result);
		} catch (Exception e) {
			return Set.of();
		}
	}

	public void dispatchSavedFields() {
		for (var candidate : targets.findTop50ByStateOrderById("PENDING_DISPATCH")) {
			tx().executeWithoutResult(s -> {
				var product = products.findForEdit(candidate.getProductId()).orElse(null);
				var target = targets.findById(candidate.getId()).orElseThrow();
				if (!"PENDING_DISPATCH".equals(target.getState()) || !handlesSavedFieldsTarget(target, mapper))
					return;
				if (product == null || product.getRevision() != target.getProductRevision()) {
					target.fieldOutcome("ACTION_REQUIRED");
					return;
				}
				if (tasks.active(target.getProductId(), target.getMarket()) > 0)
					return;
				var reg = registrations.findForConnectionUpdate(target.getRegistrationId()).orElse(null);
				var market = MarketType.valueOf(target.getMarket());
				String reason = localBlock(target.getProductId(), product, reg, market);
				if (reason == null && !savedConnectionMatches(target))
					reason = "저장 후 마켓 연결이 변경되었습니다. 최신 연결로 다시 검토하세요.";
				var owner = histories.findById(target.getHistoryId()).orElseThrow().getActor();
				Instant now = now();
				var review = reviews.save(new MarketFieldReview(UUID.randomUUID().toString(), owner, true, now));
				var task = tasks
					.saveAndFlush(newTask(review.getId(), target.getId(), target.getProductId(), product, reg,
						market, targetFields(target, mapper), reason, now));
				target.dispatchedToFields(task.getId());
				if (reason != null)
					target.fieldOutcome("ACTION_REQUIRED");
			});
		}
	}

	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public void processOne(MarketType market) {
		if (TransactionSynchronizationManager.isActualTransactionActive())
			throw new IllegalStateException("필드 마켓 호출은 DB 트랜잭션 밖에서 실행해야 합니다.");
		Claim claim = claim(market);
		if (claim == null)
			return;
		if ("PREPARE".equals(claim.phase())) {
			try {
				var client = clients.getClient(market);
				if (!Objects.equals(claim.account(), client.inspectionAccountReference())) {
					finishPreparation(claim, null, new UnsupportedOperationException("연동 계정이 변경되었습니다."));
					return;
				}
				var prepared = client.prepareProductFields(claim.product(), claim.listingId(), claim.optionId(),
					claim.fields());
				finishPreparation(claim, prepared, null);
			} catch (Exception e) {
				finishPreparation(claim, null, e);
			}
			return;
		}
		try {
			var client = clients.getClient(market);
			if (!Objects.equals(claim.account(), client.inspectionAccountReference())) {
				finish(claim, "STALE", "연동 계정이 변경되었습니다.", null, null);
				return;
			}
			var read = client.readProductFields(claim.listingId(), claim.prepared().resolvedOptionId(), claim.sbCode(),
				claim.fields());
			if (!validRead(claim, read) || !Objects.equals(claim.account(), client.inspectionAccountReference())) {
				finish(claim, "VERIFY", "정확한 계정·품목·필드 전체의 현재 값을 확인하지 못했습니다.", null,
					new IllegalStateException("유효하지 않은 필드 관측"));
				return;
			}
			if (read.approval() == MarketFieldsRead.Approval.REJECTED) {
				finish(claim, "REJECTED", "마켓 심사가 반려되었습니다. 사유를 확인한 뒤 새 검토가 필요합니다.", read, null);
				return;
			}
			if (read.approval() == MarketFieldsRead.Approval.PENDING) {
				finish(claim, "AWAITING_APPROVAL", "마켓 심사가 진행 중입니다. 재전송 없이 심사 결과를 다시 조회합니다.", read, null);
				return;
			}
			if (read.approval() == MarketFieldsRead.Approval.UNKNOWN
				|| claim.prepared().requiresApproval() && read.approval() != MarketFieldsRead.Approval.APPROVED) {
				finish(claim, "VERIFY", "마켓 심사 완료 여부가 불명확합니다. 필드 일치만으로 성공 처리하지 않습니다.", read,
					new IllegalStateException("심사 상태 불명"));
				return;
			}
			if (read.writeBlockReason() != null && !read.writeBlockReason().isBlank()) {
				finish(claim, "BLOCKED", read.writeBlockReason(), read, null);
				return;
			}
			if (read.values().equals(claim.prepared().expectedValues())) {
				finish(claim, "CONFIRMED_FIELDS", "별도 마켓 조회에서 요청한 모든 필드와 필요한 심사 완료를 확인했습니다. 다른 필드의 일치를 뜻하지 않습니다.", read,
					null);
				return;
			}
			try {
				client.writePreparedProductFields(claim.listingId(), claim.prepared().resolvedOptionId(),
					claim.sbCode(), claim.prepared(), () -> {
						if (!beginWrite(claim, read))
							throw new WriteAborted();
					});
				finish(claim, "VERIFY", "필드 전송 요청이 종료되었습니다. 실제 필드와 심사 결과를 별도로 재조회합니다.", read, null);
			} catch (WriteAborted aborted) {} catch (UnsupportedOperationException blocked) {
				finish(claim, "BLOCKED", message(blocked), read, null);
			} catch (Exception e) {
				finish(claim, "VERIFY", "필드 전송 결과를 재조회합니다. " + message(e), read, e);
			}
		} catch (UnsupportedOperationException blocked) {
			finish(claim, "BLOCKED", message(blocked), null, null);
		} catch (Exception e) {
			finish(claim, rejected(e) ? "BLOCKED" : "VERIFY", "필드 조회 실패: " + message(e), null, e);
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
			var review = reviews.lock(task.getReviewId()).orElseThrow();
			// A concurrent user commit may have changed DRAFT while the worker waited for this review.
			entityManager.refresh(task);
			if (!task.active() || task.getNextRunAt().isAfter(now))
				return null;
			String invalid = invalid(task);
			if (invalid != null) {
				end(task, "STALE", invalid, now, now);
				return null;
			}
			if ("DRAFT".equals(task.getState())) {
				if (!review.getExpiresAt().isAfter(now))
					end(task, "EXPIRED", "필드 검토가 만료되었습니다. 최신 값으로 다시 검토하세요.", now, now);
				else
					task.schedule(now.plusSeconds(60));
				return null;
			}
			if (task.getFailures() >= 9) {
				end(task, "UNKNOWN", "연속 재조회 실패 한도에 도달했습니다. 새 검토로 재시도하세요.", now, now);
				return null;
			}
			String token = UUID.randomUUID().toString();
			Instant until = now.plusSeconds(180);
			gate.claim(token, until);
			task.claim(token, until);
			record(task, "PREPARE".equals(task.getState()) ? "PREPARE_STARTED" : "READ_STARTED", "마켓 필드 준비/관측 시작", null,
				now);
			return new Claim(task.getId(), token, market, task.getState(), task.getSbCode(), task.getListingId(),
				task.getOptionId(), task.getAccountReference(), fields(task),
				"PREPARE".equals(task.getState()) ? products.findById(task.getProductId()).orElseThrow() : null,
				"PREPARE".equals(task.getState()) ? null : prepared(task));
		});
	}

	public void finishPreparation(Claim claim, PreparedMarketFields prepared, Exception error) {
		tx().executeWithoutResult(s -> {
			var gate = gates.lock(gateId(claim.market())).orElseThrow();
			var task = tasks.findById(claim.taskId()).orElseThrow();
			var review = reviews.lock(task.getReviewId()).orElseThrow();
			Instant now = now();
			Instant next = retryAt(task, error, now);
			if (rateLimited(error))
				gate.deferUntil(next);
			if (!gate.owns(claim.token(), now) || !task.owns(claim.token(), now))
				return;
			String invalid = invalid(task);
			if (invalid != null)
				end(task, "STALE", invalid, now, now);
			else if (error != null) {
				task.failure();
				String state = error instanceof UnsupportedOperationException
					|| error instanceof IllegalArgumentException || rejected(error) ? "BLOCKED"
						: task.getFailures() >= 9 ? "UNKNOWN" : "PREPARE";
				end(task, state, "마켓 전송값 준비 실패: " + message(error), now, next);
			} else if (!validPrepared(claim, prepared) || !Objects.equals(claim.account(), account(claim.market())))
				end(task, "BLOCKED", "준비된 계정·품목·필드 목록이 요청과 일치하지 않습니다. 계약 확인이 필요합니다.", now, now);
			else {
				task.prepared(prepared.resolvedOptionId(), json(prepared.expectedValues()), prepared.payload(),
					prepared.requiresApproval(), review.isAutomatic(), now);
				review.prepared(now);
				if (review.isAutomatic() && !prepared.requiresApproval())
					review.commit(false, now);
				record(task, task.getState(), task.getDetail(), task.getExpectedValues(), now);
				if ("DRAFT".equals(task.getState()))
					markTarget(task, "AWAITING_REVIEW");
			}
			gate.release(rateLimited(error) ? next : now.plusSeconds(2));
		});
	}

	public boolean beginWrite(Claim claim, MarketFieldsRead read) {
		return tx().execute(s -> {
			var gate = gates.lock(gateId(claim.market())).orElseThrow();
			var task = tasks.findById(claim.taskId()).orElseThrow();
			var review = reviews.lock(task.getReviewId()).orElseThrow();
			Instant now = now();
			if (!gate.owns(claim.token(), now) || !task.owns(claim.token(), now))
				return false;
			if (gate.getNextAllowedAt().isAfter(now)) {
				end(task, "VERIFY", "공유 호출 제한 해제 후 필드를 다시 조회합니다.", now, gate.getNextAllowedAt());
				gate.release(gate.getNextAllowedAt());
				return false;
			}
			String invalid = invalid(task);
			if (invalid == null
				&& (review.getCommittedAt() == null || task.isRequiresApproval() && !review.isApprovalConsent()))
				invalid = "필드 반영/마켓 심사 요청 동의가 저장되지 않았습니다.";
			if (invalid == null && (!validRead(claim, read) || read.approval() == MarketFieldsRead.Approval.PENDING
				|| read.approval() == MarketFieldsRead.Approval.REJECTED
				|| read.approval() == MarketFieldsRead.Approval.UNKNOWN
				|| task.isRequiresApproval() && read.approval() != MarketFieldsRead.Approval.APPROVED
				|| read.writeBlockReason() != null && !read.writeBlockReason().isBlank()))
				invalid = "마켓 계정·품목·판매/심사 상태가 변경되었습니다.";
			if (invalid != null || task.isWriteRejected() || task.getWrites() >= 3) {
				end(task, invalid != null ? "STALE" : task.isWriteRejected() ? "BLOCKED" : "FAILED_MISMATCH",
					invalid != null ? invalid
						: task.isWriteRejected() ? "앞선 필드 전송이 명시 거절되었고 재조회도 불일치합니다. 자동 재전송을 중지합니다."
							: "3회 전송 후에도 요청 필드가 일치하지 않습니다. 마켓 제한/심사 결과를 확인하세요.",
					now, now);
				gate.release(now.plusSeconds(2));
				return false;
			}
			task.observed(json(read.values()), read.approval().name(), now);
			task.beginWrite(now);
			gate.claim(claim.token(), now.plusSeconds(180));
			record(task, "WRITE_STARTED", "검토된 필드 전송 의도 기록. 요청 종료 후 별도로 재조회합니다.", task.getObservedValues(), now);
			return true;
		});
	}

	public void finish(Claim claim, String state, String detail, MarketFieldsRead read, Exception error) {
		tx().executeWithoutResult(s -> {
			var gate = gates.lock(gateId(claim.market())).orElseThrow();
			var task = tasks.findById(claim.taskId()).orElseThrow();
			reviews.lock(task.getReviewId()).orElseThrow();
			Instant now = now();
			Instant next = retryAt(task, error, now);
			if (rateLimited(error))
				gate.deferUntil(next);
			if (!gate.owns(claim.token(), now) || !task.owns(claim.token(), now))
				return;
			String invalid = invalid(task);
			String result = invalid == null ? state : "STALE";
			String reason = invalid == null ? detail : invalid;
			if (read != null && !validRead(claim, read)) {
				result = "UNKNOWN";
				reason = "정확한 계정·품목·필드 전체의 재조회 증거가 없습니다.";
			}
			if (read != null && validRead(claim, read))
				task.observed(json(read.values()), read.approval().name(), now);
			if ("CONFIRMED_FIELDS".equals(result) && !confirmed(claim, read)) {
				result = "UNKNOWN";
				reason = "필드 전체 일치와 필요한 심사 완료를 확인하지 못했습니다.";
			}
			if (error != null) {
				task.failure();
				if (rejected(error) && task.getWrites() > 0)
					task.rejectWrite();
			} else
				task.resetFailures();
			if ("VERIFY".equals(result)) {
				task.receipt(detail);
				if (task.getFailures() >= 9) {
					result = "UNKNOWN";
					reason = "연속 재조회 실패 한도에 도달했습니다. " + detail;
				}
			}
			if ("AWAITING_APPROVAL".equals(result))
				next = now.plusSeconds(900);
			end(task, result, reason, now, next);
			gate.release(rateLimited(error) ? next : now.plusSeconds(2));
		});
	}

	private boolean validPrepared(Claim c, PreparedMarketFields p) {
		return p != null && Objects.equals(c.account(), p.accountReference()) && p.resolvedOptionId() != null
			&& !p.resolvedOptionId().isBlank()
			&& p.resolvedOptionId().length() <= 255 && p.expectedValues().keySet().equals(c.fields())
			&& p.expectedValues().values().stream().allMatch(Objects::nonNull)
			&& p.payload() != null && !p.payload().isBlank();
	}

	private boolean validRead(Claim c, MarketFieldsRead r) {
		return r != null && r.approval() != null && Objects.equals(c.account(), r.accountReference())
			&& Objects.equals(c.prepared().resolvedOptionId(), r.resolvedOptionId())
			&& r.values().keySet().equals(c.fields())
			&& r.values().values().stream().allMatch(Objects::nonNull);
	}

	private boolean confirmed(Claim c, MarketFieldsRead r) {
		return validRead(c, r) && r.values().equals(c.prepared().expectedValues())
			&& (r.writeBlockReason() == null || r.writeBlockReason().isBlank())
			&& (r.approval() == MarketFieldsRead.Approval.APPROVED
				|| !c.prepared().requiresApproval() && r.approval() == MarketFieldsRead.Approval.NOT_REQUIRED);
	}

	private String invalid(MarketFieldTask task) {
		var product = products.findForEdit(task.getProductId()).orElse(null);
		var reg = task.getRegistrationId() == null ? null
			: registrations.findForConnectionUpdate(task.getRegistrationId()).orElse(null);
		var market = MarketType.valueOf(task.getMarket());
		String block = localBlock(task.getProductId(), product, reg, market);
		if (block != null)
			return block;
		return product.getRevision() != task.getProductRevision() || reg.getRevision() != task.getRegistrationRevision()
			|| !Objects.equals(product.getSbCode(), task.getSbCode())
			|| !Objects.equals(reg.getMarketIdentifiers(), task.getIdentifiers())
			|| !Objects.equals(reg.extractLiveLookupId(), task.getListingId())
			|| !Objects.equals(account(market), task.getAccountReference())
				? "상품 revision·마켓 연결·계정이 변경되어 기존 필드 작업을 중지했습니다. 최신 값으로 다시 검토하세요." : null;
	}

	private String localBlock(Long id, Product product, MarketRegistration reg, MarketType market) {
		if (product == null || product.isDeleted())
			return "상품이 없거나 폐기되었습니다.";
		if (!SUPPORTED.contains(market))
			return "이 마켓의 필드별 준비·전송·재조회 계약이 확인되지 않았습니다.";
		if (reg == null || reg.getConnectionState().detached())
			return "현재 연결된 마켓 상품이 없습니다.";
		if (!id.equals(reg.getProductId()) || market != reg.getMarketType())
			return "상품·마켓 연결 대상이 일치하지 않습니다.";
		if (reg.connectionWriteBlock() != null)
			return reg.connectionWriteBlock();
		if (reg.extractLiveLookupId() == null)
			return "마켓 상품번호가 확인되지 않았습니다.";
		if (account(market) == null)
			return "마켓 연동 계정이 확인되지 않았습니다.";
		return null;
	}

	private MarketFieldTask newTask(String reviewId, Long targetId, Long id, Product product, MarketRegistration reg,
		MarketType market, Set<String> fields, String reason, Instant now) {
		return new MarketFieldTask(reviewId, targetId, id, product == null ? null : product.getSbCode(),
			reg == null ? null : reg.getId(),
			product == null ? 0 : product.getRevision(), reg == null ? 0 : reg.getRevision(), market.name(),
			reg == null ? null : reg.extractLiveLookupId(),
			reg == null ? null : reg.identifier(market == MarketType.COUPANG ? "vendorItemId" : "variant_code"),
			reg == null ? null : reg.getMarketIdentifiers(), account(market), json(new TreeSet<>(fields)), reason, now);
	}

	private boolean savedConnectionMatches(ProductChangeTarget target) {
		try {
			return mapper.readTree(target.getSnapshot()).path("connectionFingerprint").asText()
				.equals(editPlanner.fingerprint(registrations.findByProductId(target.getProductId())));
		} catch (Exception e) {
			return false;
		}
	}

	private void end(MarketFieldTask task, String state, String detail, Instant now, Instant next) {
		task.finish(state, detail, now, next);
		record(task, state, task.getDetail(), task.getObservedValues(), now);
		updateTargets(task);
	}

	private void record(MarketFieldTask task, String phase, String detail, String observed, Instant now) {
		attempts.save(new MarketFieldAttempt(task.getId(), phase, detail, observed, now));
	}

	private void markTarget(MarketFieldTask task, String state) {
		if (task.getChangeTargetId() != null)
			targets.findById(task.getChangeTargetId()).ifPresent(t -> t.fieldOutcome(state));
	}

	private void markDispatched(MarketFieldTask task) {
		markTarget(task, "DISPATCHED");
	}

	private void updateTargets(MarketFieldTask task) {
		if (task.active())
			return;
		if ("CONFIRMED_FIELDS".equals(task.getState())) {
			for (var target : targets.findByProductIdAndMarket(task.getProductId(), task.getMarket())) {
				if (Set.of("PENDING_DISPATCH", "DISPATCHED", "ACTION_REQUIRED", "AWAITING_REVIEW")
					.contains(target.getState())
					&& Objects.equals(target.getRegistrationId(), task.getRegistrationId())
					&& handlesSavedFieldsTarget(target, mapper)
					&& fields(task).containsAll(targetFields(target, mapper))
					&& target.getProductRevision() <= task.getProductRevision())
					target.fieldOutcome(target.getProductRevision() == task.getProductRevision() ? "CONFIRMED_FIELDS"
						: "SUPERSEDED_BY_CURRENT");
			}
		} else
			markTarget(task, "ACTION_REQUIRED");
	}

	private Review view(MarketFieldReview review) {
		var items = tasks.findByReviewIdOrderById(review.getId()).stream()
			.map(t -> new Item(t.getId(), t.getProductId(), t.getSbCode(), t.getMarket(), t.getListingId(),
				t.getProductRevision(), fields(t), values(t.getExpectedValues()), values(t.getObservedValues()),
				t.getState(), t.getDetail(), t.isRequiresApproval(), t.getWrites(), t.getReads(), t.getNextRunAt(),
				t.getCheckedAt()))
			.toList();
		return new Review(review.getId(), review.getActor(), review.getCreatedAt(), review.getExpiresAt(),
			review.getCommittedAt() != null,
			items.stream().anyMatch(i -> "PREPARE".equals(i.state())), items, items.size());
	}

	private Set<String> fields(MarketFieldTask task) {
		try {
			return mapper.readValue(task.getFields(), new TypeReference<Set<String>>() {});
		} catch (Exception e) {
			throw new IllegalStateException("저장된 필드 목록을 읽지 못했습니다.", e);
		}
	}

	private Map<String, String> values(String json) {
		if (json == null)
			return Map.of();
		try {
			return mapper.readValue(json, new TypeReference<Map<String, String>>() {});
		} catch (Exception e) {
			throw new IllegalStateException("저장된 필드 값을 읽지 못했습니다.", e);
		}
	}

	private PreparedMarketFields prepared(MarketFieldTask t) {
		return new PreparedMarketFields(t.getAccountReference(), t.getResolvedOptionId(), t.isRequiresApproval(),
			values(t.getExpectedValues()), t.getPreparedPayload());
	}

	private String json(Object value) {
		try {
			return mapper.writeValueAsString(value);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private String account(MarketType market) {
		try {
			String a = clients.hasClient(market) ? clients.getClient(market).inspectionAccountReference() : null;
			return a == null || a.isBlank() ? null : a;
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
		var t = new TransactionTemplate(transactions);
		t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		return t;
	}

	private Instant retryAt(MarketFieldTask task, Exception error, Instant now) {
		Instant next = now.plusSeconds(Math.min(300, 10L << Math.min(5, task.getFailures()))
			+ java.util.concurrent.ThreadLocalRandom.current().nextInt(6));
		if (error instanceof MarketTransferFailure f && f.getRetryAfter() != null && f.getRetryAfter().isAfter(next))
			next = f.getRetryAfter();
		return next;
	}

	private boolean rateLimited(Exception e) {
		return e instanceof MarketTransferFailure f && f.rateLimited();
	}

	private boolean rejected(Exception e) {
		return e instanceof MarketTransferFailure f && f.getCode() != null && f.getCode().matches("HTTP_4[0-9]{2}")
			&& !f.rateLimited();
	}

	private String message(Exception e) {
		return e instanceof MarketTransferFailure || e instanceof UnsupportedOperationException
			|| e instanceof IllegalArgumentException
			? Objects.toString(e.getMessage(), "마켓 응답 불명") : "마켓 응답/값 변환을 확인하지 못했습니다.";
	}

	private static boolean validField(String f) {
		return f != null && f.matches("[A-Za-z][A-Za-z0-9]{0,63}");
	}

	private void requireActorName(String a) {
		if (a == null || a.isBlank() || a.length() > 200)
			throw new IllegalArgumentException("인증된 작업자가 필요합니다.");
	}

	private void requireActor(MarketFieldReview r, String actor) {
		requireActorName(actor);
		if (!r.getActor().equals(actor))
			throw new ProductEditConflictException("필드 검토 작성자만 조회·반영할 수 있습니다.");
	}

	private void validate(List<Long> ids, Set<MarketType> markets, Set<String> fields, String actor) {
		requireActorName(actor);
		if (ids == null || ids.isEmpty() || ids.size() > 500 || ids.stream().anyMatch(i -> i == null || i <= 0))
			throw new IllegalArgumentException("상품을 1~500개 선택하세요.");
		if (markets == null || markets.isEmpty() || markets.stream().anyMatch(Objects::isNull))
			throw new IllegalArgumentException("마켓을 선택하세요.");
		if (fields == null || fields.isEmpty() || fields.size() > 30
			|| fields.stream().anyMatch(f -> !validField(f) || ProductEditPolicy.PRICE_FIELDS.contains(f)
				|| Set.of("memo", "salesQuantity", "stock", "stockStatus").contains(f)))
			throw new IllegalArgumentException("가격·판매수량·내부메모와 분리된 필드를 선택하세요.");
	}

	private static final class WriteAborted extends RuntimeException {}
}
