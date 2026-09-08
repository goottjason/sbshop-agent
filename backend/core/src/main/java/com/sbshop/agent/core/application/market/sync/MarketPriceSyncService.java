package com.sbshop.agent.core.application.market.sync;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.product.MarketSalePriceResolver;
import com.sbshop.agent.core.application.product.dto.MarketSalePriceOverrides;
import com.sbshop.agent.core.application.product.edit.ProductEditConflictException;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.client.MarketClientRouter;
import com.sbshop.agent.core.domain.market.client.dto.MarketPriceRead;
import com.sbshop.agent.core.domain.market.inspection.*;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.market.sync.*;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Immutable reviewed prices, durable write intent, read-before-retry, field-specific proof. */
@Service
@RequiredArgsConstructor
public class MarketPriceSyncService {
	public static final Set<MarketType> SUPPORTED = Set.of(MarketType.SMART_STORE, MarketType.COUPANG,
		MarketType.CAFE24);
	private final MarketPriceReviewRepository reviews;
	private final MarketPriceTaskRepository tasks;
	private final MarketPriceAttemptRepository attempts;
	private final MarketInspectionGateRepository gates;
	private final ProductRepository products;
	private final MarketRegistrationRepository registrations;
	private final MarketClientRouter clients;
	private final MarketSalePriceResolver prices;
	private final ObjectMapper mapper;
	private final PlatformTransactionManager transactions;
	private final JdbcTemplate jdbc;
	private final com.sbshop.agent.core.domain.product.edit.ProductChangeTargetRepository changeTargets;

	/** Saved, reviewed price edits need no second approval. Unsupported fields remain visible. */
	public void dispatchSavedPrices() {
		for (var candidate : changeTargets.findTop50ByStateOrderById("PENDING_DISPATCH")) {
			tx().executeWithoutResult(s -> {
				var product = products.findForEdit(candidate.getProductId()).orElse(null);
				var target = changeTargets.findById(candidate.getId()).orElseThrow();
				if (!"PENDING_DISPATCH".equals(target.getState()))
					return;
				MarketType market = MarketType.valueOf(target.getMarket());
				if (MarketStockSyncService.handlesSavedQuantityTarget(target, mapper)
					|| MarketFieldSyncService.handlesSavedFieldsTarget(target, mapper))
					return;
				if (!SUPPORTED.contains(market) || !priceOnly(target)) {
					target.priceOutcome("ACTION_REQUIRED");
					return;
				}
				if (tasks.active(target.getProductId(), target.getMarket()) > 0)
					return;
				var reg = registrations.findForConnectionUpdate(target.getRegistrationId()).orElse(null);
				var plan = plan(target.getProductId(), product, reg, market);
				Instant now = now();
				var review = reviews.save(new MarketPriceReview(UUID.randomUUID().toString(), "SYSTEM_SAVED_PRICE",
					json(List.of(plan)), now));
				var task = tasks.saveAndFlush(new MarketPriceTask(review.getId(), plan.productId(), plan.sbCode(),
					plan.registrationId(), plan.revision(), plan.market(), plan.listingId(), plan.optionId(),
					plan.identifiers(), plan.account(), plan.price(), plan.reason(), now));
				review.commit(now);
				target.dispatchedToPrice(task.getId());
				if (plan.reason() != null)
					target.priceOutcome("ACTION_REQUIRED");
			});
		}
	}

	private boolean priceOnly(com.sbshop.agent.core.domain.product.edit.ProductChangeTarget target) {
		try {
			var changes = mapper.readTree(target.getSnapshot()).path("changes");
			if (!changes.isArray() || changes.isEmpty())
				return false;
			boolean price = false;
			for (var change : changes) {
				String field = change.path("field").asText();
				if (field.equals("memo"))
					continue;
				if (!com.sbshop.agent.core.application.product.edit.ProductEditPolicy.PRICE_FIELDS.contains(field))
					return false;
				price = true;
			}
			return price;
		} catch (Exception e) {
			return false;
		}
	}

	private void updateTargets(MarketPriceTask task) {
		if (Set.of("CHECK", "VERIFY").contains(task.getState()))
			return;
		if ("CONFIRMED_PRICE".equals(task.getState())) {
			for (var target : changeTargets.findByProductIdAndMarket(task.getProductId(), task.getMarket())) {
				if (Set.of("PENDING_DISPATCH", "BATCH_MANAGED", "DISPATCHED", "ACTION_REQUIRED")
					.contains(target.getState())
					&& priceOnly(target) && target.getProductRevision() <= task.getProductRevision())
					target.priceOutcome(target.getProductRevision() == task.getProductRevision() ? "CONFIRMED_PRICE"
						: "SUPERSEDED_BY_CURRENT");
			}
		} else
			for (var target : changeTargets.findByPriceTaskId(task.getId())) {
				if ("DISPATCHED".equals(target.getState()))
					target.priceOutcome("STALE".equals(task.getState()) ? "PENDING_DISPATCH" : "ACTION_REQUIRED");
			}
	}

	public record Plan(Long productId, String sbCode, Long registrationId, long revision, String market,
		String listingId,
		String optionId, String identifiers, String account, BigDecimal price, String reason) {
	}
	public record Item(Long id, Long productId, String sbCode, String market, String listingId, long revision,
		BigDecimal expectedPrice,
		BigDecimal observedPrice, String state, String detail, int writes, int reads, Instant nextRunAt,
		Instant checkedAt) {
	}
	public record Review(String id, String actor, Instant createdAt, Instant expiresAt, boolean committed,
		List<Item> items, int total) {
	}
	public record Claim(Long taskId, String token, MarketType market, String listingId, String optionId, String account,
		BigDecimal price) {
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
			var review = new MarketPriceReview(UUID.randomUUID().toString(), actor, json(plans), now());
			reviews.save(review);
			return view(review);
		});
	}

	public Review commit(String id, String actor) {
		return tx().execute(s -> {
			var review = reviews.lock(id).orElseThrow(() -> new IllegalArgumentException("가격 검토가 없습니다."));
			if (!review.getActor().equals(actor))
				throw new ProductEditConflictException("검토한 작업자만 반영할 수 있습니다.");
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
					skip = "검토 후 상품·가격·연결·계정이 변경되었습니다. 다시 검토하세요.";
				if (skip == null && tasks.active(p.productId(), p.market()) > 0)
					skip = "이 상품·마켓의 가격 작업이 이미 진행 중입니다.";
				tasks.save(new MarketPriceTask(id, p.productId(), p.sbCode(), p.registrationId(), p.revision(),
					p.market(), p.listingId(), p.optionId(), p.identifiers(), p.account(), p.price(), skip, now));
			}
			review.commit(now);
			return view(review);
		});
	}

	public Review get(String id) {
		return tx()
			.execute(s -> view(reviews.findById(id).orElseThrow(() -> new IllegalArgumentException("가격 작업이 없습니다."))));
	}

	public List<Review> recent() {
		return tx().execute(s -> reviews.findTop20ByOrderByCreatedAtDesc().stream()
			.map(
				r -> new Review(r.getId(), r.getActor(), r.getCreatedAt(), r.getExpiresAt(), r.getCommittedAt() != null,
					List.of(),
					r.getCommittedAt() == null ? plans(r).size() : Math.toIntExact(tasks.countByReviewId(r.getId()))))
			.toList());
	}

	public List<MarketPriceAttempt> history(Long id) {
		return attempts.findByTaskIdOrderById(id);
	}

	public void processOne(MarketType market) {
		Claim c = claim(market);
		if (c == null)
			return;
		try {
			var client = clients.getClient(market);
			if (!Objects.equals(c.account(), client.inspectionAccountReference())) {
				finish(c, "STALE", "연동 계정이 변경되었습니다.", null, null);
				return;
			}
			MarketPriceRead read = client.readSalePrice(c.listingId(), c.optionId());
			if (read == null || read.value() == null || !Objects.equals(c.account(), read.accountReference())
				|| !Objects.equals(c.account(), client.inspectionAccountReference())) {
				finish(c, "UNKNOWN", "정확한 계정의 가격 응답을 확인하지 못했습니다.", null, null);
				return;
			}
			// A stopped/prohibited product is not sent again, even if a former write was uncertain.
			if (!read.writable()) {
				finish(c, "BLOCKED", read.reason(), read.value(), null);
				return;
			}
			if (read.value().compareTo(c.price()) == 0) {
				finish(c, "CONFIRMED_PRICE", "마켓에서 재조회한 판매가가 목표 가격과 일치합니다. 다른 필드의 일치를 뜻하지 않습니다.", read.value(), null);
				return;
			}
			if (!beginWrite(c, read.value()))
				return;
			try {
				client.writeSalePrice(c.listingId(), c.optionId(), c.price());
				finish(c, "VERIFY", "전송 요청이 종료되었습니다. 실제 가격을 다시 조회합니다.", read.value(), null);
			} catch (Exception e) {
				finish(c, "VERIFY", "전송 결과를 재조회합니다. " + message(e), read.value(), failure(e));
			}
		} catch (Exception e) {
			finish(c, "VERIFY", "가격 조회 실패: " + message(e), null, failure(e));
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
			if (task.getReads() >= 9) {
				task.finish("UNKNOWN", "재조회 한도에 도달했습니다. 새 검토로 재시도할 수 있습니다.", now, now);
				updateTargets(task);
				return null;
			}
			String token = UUID.randomUUID().toString();
			Instant until = now.plusSeconds(180);
			gate.claim(token, until);
			task.claim(token, until);
			attempts.save(new MarketPriceAttempt(task.getId(), "READ_STARTED", "마켓 실가격 조회 시작", now));
			return new Claim(task.getId(), token, market, task.getListingId(), task.getOptionId(),
				task.getAccountReference(), task.getExpectedPrice());
		});
	}

	/** Recheck identity and revision while committing the write intent, before any outbound write. */
	public boolean beginWrite(Claim c, BigDecimal observed) {
		return tx().execute(s -> {
			var gate = gates.lock(gateId(c.market())).orElseThrow();
			Instant now = now();
			var task = tasks.findById(c.taskId()).orElseThrow();
			if (!gate.owns(c.token(), now) || !task.owns(c.token(), now))
				return false;
			if (!SupplierBatchMarketGate.mayStart(jdbc, task.getReviewId())) {
				task.finish("VERIFY", "배치가 일시정지 중입니다. 재개 후 현재 값을 다시 확인합니다.", now, now);
				attempts.save(new MarketPriceAttempt(task.getId(), "BATCH_PAUSED", task.getDetail(), now));
				gate.release(now.plusSeconds(2));
				return false;
			}
			if (gate.getNextAllowedAt().isAfter(now)) {
				task.finish("VERIFY", "공유 호출 제한 대기 중입니다. 제한 해제 후 가격을 다시 조회합니다.", now, gate.getNextAllowedAt());
				attempts.save(new MarketPriceAttempt(task.getId(), "THROTTLED", task.getDetail(), now));
				gate.release(gate.getNextAllowedAt());
				return false;
			}
			String invalid = invalid(task);
			if (invalid != null || task.getWrites() >= 3) {
				task.observed(observed, now);
				task.finish(invalid == null ? "FAILED_MISMATCH" : "STALE",
					invalid == null ? "3회 전송 후에도 가격이 일치하지 않습니다. 마켓의 가격 제한·심사 상태를 확인하세요." : invalid, now, now);
				attempts.save(new MarketPriceAttempt(task.getId(), task.getState(), task.getDetail(), now));
				updateTargets(task);
				gate.release(now.plusSeconds(2));
				return false;
			}
			task.observed(observed, now);
			task.beginWrite(now);
			gate.claim(c.token(), now.plusSeconds(180));
			attempts.save(new MarketPriceAttempt(task.getId(), "WRITE_STARTED",
				"가격 " + task.getExpectedPrice() + " 전송 시작. 응답 유실 시 먼저 재조회합니다.", now));
			return true;
		});
	}

	public void finish(Claim c, String state, String detail, BigDecimal observed, MarketTransferFailure error) {
		tx().executeWithoutResult(s -> {
			var gate = gates.lock(gateId(c.market())).orElseThrow();
			var task = tasks.findById(c.taskId()).orElseThrow();
			Instant now = now();
			if (error != null && error.rateLimited()) {
				Instant cooldown = now.plusSeconds(Math.min(300, 10L << Math.min(5, Math.max(0, task.getReads() - 1))));
				if (error.getRetryAfter() != null && error.getRetryAfter().isAfter(cooldown))
					cooldown = error.getRetryAfter();
				gate.deferUntil(cooldown);
			}
			if (!gate.owns(c.token(), now) || !task.owns(c.token(), now))
				return;
			String invalid = invalid(task), finalState = invalid == null ? state : "STALE",
				finalDetail = invalid == null ? detail : invalid;
			if (observed != null)
				task.observed(observed, now);
			if ("VERIFY".equals(finalState))
				task.receipt(detail);
			long delay = Math.min(300, 10L << Math.min(5, task.getReads() - 1));
			Instant next = now.plusSeconds(delay + java.util.concurrent.ThreadLocalRandom.current().nextInt(6));
			if (error != null && error.getRetryAfter() != null && error.getRetryAfter().isAfter(next))
				next = error.getRetryAfter();
			if ("VERIFY".equals(finalState) && task.getReads() >= 9) {
				finalState = "UNKNOWN";
				finalDetail = "재조회 한도에 도달했습니다. " + detail;
			}
			task.finish(finalState, finalDetail, now, next);
			attempts.save(new MarketPriceAttempt(task.getId(), finalState, task.getDetail(), now));
			updateTargets(task);
			gate.release(error != null && error.rateLimited() ? next : now.plusSeconds(2));
		});
	}

	private String invalid(MarketPriceTask t) {
		var p = products.findForEdit(t.getProductId()).orElse(null);
		var r = registrations.findForConnectionUpdate(t.getRegistrationId()).orElse(null);
		Plan current = plan(t.getProductId(), p, r, MarketType.valueOf(t.getMarket()));
		if (current.reason() != null)
			return current.reason();
		return current.revision() != t.getProductRevision()
			|| !Objects.equals(current.identifiers(), t.getIdentifiers())
			|| !Objects.equals(current.account(), t.getAccountReference())
			|| current.price().compareTo(t.getExpectedPrice()) != 0
				? "상품·연결·가격 정책·계정이 변경되어 이 작업을 중지했습니다. 최신 값으로 다시 검토하세요." : null;
	}

	private Plan plan(Long id, Product p, MarketRegistration r, MarketType market) {
		String account = account(market), reason = null;
		BigDecimal price = null;
		if (p == null || p.isDeleted())
			reason = "상품이 없거나 폐기되었습니다.";
		else if (!SUPPORTED.contains(market))
			reason = "가격 단독 수정·재조회 API 계약 확인이 필요합니다.";
		else if (r == null || r.getConnectionState().detached())
			reason = "현재 연결된 마켓 상품이 없습니다. 등록 후보에서 확인하세요.";
		else if (r.connectionWriteBlock() != null)
			reason = r.connectionWriteBlock();
		else if (r.extractLiveLookupId() == null)
			reason = "등록 상품번호를 확인할 수 없습니다.";
		else if (market == MarketType.COUPANG && r.identifier("vendorItemId") == null)
			reason = "쿠팡 옵션 번호를 확인할 수 없습니다.";
		else if (account == null)
			reason = "마켓 계정 설정을 확인할 수 없습니다.";
		else {
			var quote = prices.explainForProduct(p, market, MarketSalePriceOverrides.EMPTY);
			if (quote.basis() != MarketSalePriceResolver.Basis.CALCULATED || quote.salePrice() == null)
				reason = "최소마진을 포함한 가격을 계산할 수 없습니다.";
			else {
				price = quote.salePrice();
				try {
					if (price.intValueExact() <= 0)
						throw new ArithmeticException();
				} catch (ArithmeticException e) {
					reason = "마켓 판매가의 정수 범위를 확인하세요.";
				}
			}
		}
		return new Plan(id, p == null ? null : p.getSbCode(), r == null ? null : r.getId(),
			p == null ? 0 : p.getRevision(), market.name(), r == null ? null : r.extractLiveLookupId(),
			r == null ? null : r.identifier("vendorItemId"), r == null ? null : r.getMarketIdentifiers(), account,
			price, reason);
	}

	private boolean same(Plan a, Plan b) {
		return b.reason() == null && a.revision() == b.revision()
			&& Objects.equals(a.registrationId(), b.registrationId())
			&& Objects.equals(a.identifiers(), b.identifiers()) && Objects.equals(a.account(), b.account())
			&& a.price().compareTo(b.price()) == 0;
	}

	private Review view(MarketPriceReview r) {
		List<Item> items = r.getCommittedAt() == null
			? plans(r).stream()
				.map(p -> new Item(null, p.productId(), p.sbCode(), p.market(), p.listingId(), p.revision(), p.price(),
					null, p.reason() == null ? "READY" : "SKIPPED",
					p.reason() == null ? "마켓별 계산 가격을 전송합니다. 실행 시 현재 가격·판매 상태를 먼저 확인합니다." : p.reason(), 0, 0, null,
					null))
				.toList()
			: tasks.findByReviewIdOrderById(r.getId()).stream()
				.map(t -> new Item(t.getId(), t.getProductId(), t.getSbCode(), t.getMarket(), t.getListingId(),
					t.getProductRevision(), t.getExpectedPrice(), t.getObservedPrice(), t.getState(), t.getDetail(),
					t.getWrites(), t.getReads(), t.getNextRunAt(), t.getCheckedAt()))
				.toList();
		return new Review(r.getId(), r.getActor(), r.getCreatedAt(), r.getExpiresAt(), r.getCommittedAt() != null,
			items, items.size());
	}

	private List<Plan> plans(MarketPriceReview r) {
		try {
			return mapper.readValue(r.getSnapshot(), new TypeReference<List<Plan>>() {});
		} catch (Exception e) {
			throw new IllegalStateException("저장된 검토 내용을 읽지 못했습니다.", e);
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
		return new TransactionTemplate(transactions);
	}

	private MarketTransferFailure failure(Exception e) {
		return e instanceof MarketTransferFailure f ? f : null;
	}

	private String message(Exception e) {
		return e instanceof MarketTransferFailure || e instanceof UnsupportedOperationException ? e.getMessage()
			: "처리 오류. 원본 작업 로그를 확인하세요.";
	}

	private void validate(List<Long> ids, Set<MarketType> markets, String actor) {
		if (actor == null || actor.isBlank() || actor.length() > 200)
			throw new IllegalArgumentException("인증된 작업자가 필요합니다.");
		if (ids == null || ids.isEmpty() || ids.size() > 500 || ids.stream().anyMatch(i -> i == null || i <= 0))
			throw new IllegalArgumentException("상품을 1~500개 선택하세요.");
		if (markets == null || markets.isEmpty() || markets.stream().anyMatch(Objects::isNull))
			throw new IllegalArgumentException("마켓을 선택하세요.");
	}
}
