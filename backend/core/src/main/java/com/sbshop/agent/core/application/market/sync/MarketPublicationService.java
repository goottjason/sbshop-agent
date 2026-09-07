package com.sbshop.agent.core.application.market.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.product.MarketSalePriceResolver;
import com.sbshop.agent.core.application.product.dto.MarketSalePriceOverrides;
import com.sbshop.agent.core.application.product.edit.ProductEditConflictException;
import com.sbshop.agent.core.domain.market.*;
import com.sbshop.agent.core.domain.market.client.*;
import com.sbshop.agent.core.domain.market.client.dto.PreparedMarketPublication;
import com.sbshop.agent.core.domain.market.inspection.*;
import com.sbshop.agent.core.domain.market.publication.*;
import com.sbshop.agent.core.domain.market.repository.*;
import com.sbshop.agent.core.domain.market.sync.MarketTransferFailure;
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

@Service
@RequiredArgsConstructor
public class MarketPublicationService {
	private static final MarketType SUPPORTED = MarketType.SMART_STORE;
	private final ProductRepository products;
	private final MarketRegistrationRepository registrations;
	private final MarketConnectionEventRepository events;
	private final MarketPublicationTaskRepository tasks;
	private final MarketInspectionGateRepository gates;
	private final MarketClientRouter clients;
	private final MarketSalePriceResolver prices;
	private final ObjectMapper mapper;
	private final PlatformTransactionManager transactions;
	private final JdbcTemplate jdbc;

	public record Pair(Long productId, MarketType market) {
	}
	public record Candidate(Long productId, String sbCode, String market, String oldListingId, String connectionState,
		String reason, boolean selectable) {
	}
	public record View(String id, Long productId, String sbCode, String market, String actor, String state,
		String detail,
		String name, String categoryId, String categoryPath, BigDecimal price, int quantity, String image,
		String listingId, Instant expiresAt, Instant createdAt, Instant checkedAt, boolean committed) {
	}
	public record PreparedResult(List<View> prepared, List<Candidate> excluded) {
	}
	public record Claim(String id, String token, Product product, PreparedMarketPublication prepared, boolean post,
		String listingId) {
	}

	public List<Candidate> candidates(List<Long> ids, Set<MarketType> markets) {
		if (ids == null || ids.isEmpty() || ids.size() > 500 || ids.stream().anyMatch(i -> i == null || i <= 0)
			|| markets == null || markets.isEmpty() || markets.stream().anyMatch(Objects::isNull))
			throw new IllegalArgumentException("상품을 1~500개 선택하고 마켓을 지정하세요.");
		return tx().execute(s -> {
			var result = new ArrayList<Candidate>();
			for (var id : ids.stream().distinct().sorted().toList())
				for (var market : markets.stream().sorted().toList())
					result.add(candidate(id, market));
			return result;
		});
	}

	private Candidate candidate(Long id, MarketType market) {
		var p = products.findById(id).orElse(null);
		var reg = registrations.findByProductIdAndMarketType(id, market).orElse(null);
		// Gmarket/Auction may have independent rows or Cafe24 child links: either blocks duplication.
		if ((market == MarketType.GMARKET || market == MarketType.AUCTION) && reg == null)
			reg = registrations.findByProductIdAndMarketType(id, MarketType.CAFE24).orElse(null);
		String old = reg == null ? null : reg.connectionIdentifier(market);
		var state = reg == null ? null : reg.connectionStateFor(market);
		String reason;
		boolean eligible = false;
		if (p == null || p.isDeleted())
			reason = "상품이 없거나 폐기되었습니다.";
		else if (reg != null && reg.getPublicationOperationId() != null)
			reason = "이전 등록 결과를 확인 중입니다. 중복 등록하지 않습니다.";
		else if (state == MarketConnectionState.DETACHED_PROHIBITED)
			reason = "영구 판매금지 이력: 재등록 대상에서 제외합니다.";
		else if (old != null && state == MarketConnectionState.LINKED)
			reason = "연결 기록이 있습니다. 마켓 코드로 현재 상태를 먼저 확인하세요.";
		else if (reg != null && reg.getMarketType() == market && state == MarketConnectionState.LINKED && old == null)
			reason = "상품번호 없는 기존 등록 요청이 있습니다. 생성 여부 확인이 필요합니다.";
		else if (p.getStockStatus() == com.sbshop.agent.core.domain.product.enums.StockStatus.OUT_OF_STOCK)
			reason = "소싱처 품절 상품입니다. 재입고 확인 후 등록하세요.";
		else {
			reason = state == MarketConnectionState.DETACHED_DELETED ? "삭제가 확인된 상품입니다. 삭제 사유를 확인하고 선택하세요."
				: "연결 기록이 없는 마켓입니다.";
			eligible = true;
		}
		if (reg != null && state != null && state.detached()) {
			for (var event : events.findTop100ByProductIdOrderByIdDesc(id))
				if (event.getMarket().equals(market.name())
					&& Set.of("DETACHED", "ALREADY_DETACHED").contains(event.getResult())) {
					try {
						reason += " 근거: " + mapper.readTree(event.getEvidence()).path("detail").asText("세부 사유 미제공");
					} catch (Exception ignored) {}
					break;
				}
		}
		if (eligible && market != SUPPORTED) {
			reason += " 등록 요청 고정·결과 검증 연결을 준비 중입니다.";
			eligible = false;
		}
		return new Candidate(id, p == null ? null : p.getSbCode(), market.name(), old,
			state == null ? "MISSING" : state.name(), reason, eligible);
	}

	public PreparedResult prepare(List<Pair> selected, String actor) {
		validatePairs(selected, actor);
		var prepared = new ArrayList<View>();
		var excluded = new ArrayList<Candidate>();
		for (Pair pair : selected.stream().distinct()
			.sorted(Comparator.comparing(Pair::productId).thenComparing(Pair::market)).toList()) {
			Candidate candidate = tx().execute(s -> candidate(pair.productId(), pair.market()));
			if (!candidate.selectable()) {
				excluded.add(candidate);
				continue;
			}
			var product = products.findById(pair.productId()).orElseThrow();
			var reg = registrations.findByProductIdAndMarketType(pair.productId(), pair.market()).orElse(null);
			String fingerprint = fingerprint(reg);
			long revision = product.getRevision();
			try {
				var quote = prices.explainForProduct(product, pair.market(), MarketSalePriceOverrides.EMPTY);
				if (quote.basis() != MarketSalePriceResolver.Basis.CALCULATED || quote.salePrice() == null)
					throw new IllegalStateException("최소마진을 확인할 수 없습니다.");
				var payload = clients.getClient(pair.market()).preparePublication(product, quote.salePrice());
				if (payload == null || payload.account() == null || payload.categoryId() == null
					|| payload.categoryId().isBlank())
					throw new IllegalStateException("계정·카테고리를 확인할 수 없습니다.");
				View view = tx().execute(s -> {
					var p = products.findForEdit(pair.productId()).orElseThrow();
					var r = registrations.findByProductIdAndMarketType(pair.productId(), pair.market()).orElse(null);
					if (p.getRevision() != revision || !fingerprint.equals(fingerprint(r))
						|| !candidate(pair.productId(), pair.market()).selectable())
						throw new ProductEditConflictException("등록 준비 중 상품·연결이 변경되었습니다.");
					var task = tasks.save(new MarketPublicationTask(UUID.randomUUID().toString(), p.getId(),
						r == null ? null : r.getId(), revision, pair.market().name(), actor, p.getSbCode(), fingerprint,
						json(payload), candidate.reason(), now()));
					return view(task);
				});
				prepared.add(view);
			} catch (Exception e) {
				excluded.add(new Candidate(pair.productId(), candidate.sbCode(), candidate.market(),
					candidate.oldListingId(), candidate.connectionState(), "등록 준비 실패: " + safe(e), false));
			}
		}
		return new PreparedResult(prepared, excluded);
	}

	public View commit(String id, String actor) {
		var initial = tasks.findById(id).orElseThrow();
		return tx().execute(s -> {
			var product = products.findForEdit(initial.getProductId()).orElseThrow();
			var task = tasks.lock(id).orElseThrow();
			if (!task.getActor().equals(actor))
				throw new ProductEditConflictException("검토한 작업자만 등록할 수 있습니다.");
			if (task.getCommittedAt() != null)
				return view(task);
			Instant now = now();
			if (!task.getExpiresAt().isAfter(now))
				throw new ProductEditConflictException("등록 검토가 만료되었습니다.");
			var market = MarketType.valueOf(task.getMarket());
			var reg = registrations.findByProductIdAndMarketType(product.getId(), market).orElse(null);
			var payload = payload(task);
			if (product.isDeleted() || product.getRevision() != task.getProductRevision()
				|| !task.getConnectionSnapshot().equals(fingerprint(reg))
				|| !candidate(product.getId(), market).selectable()
				|| !Objects.equals(payload.account(), clients.getClient(market).inspectionAccountReference()))
				throw new ProductEditConflictException("상품·연결·계정이 변경되었습니다. 다시 검토하세요.");
			var quote = prices.explainForProduct(product, market, MarketSalePriceOverrides.EMPTY);
			if (quote.salePrice() == null || quote.salePrice().compareTo(payload.price()) != 0)
				throw new ProductEditConflictException("가격 정책이 변경되었습니다. 다시 검토하세요.");
			if (reg == null)
				reg = registrations.saveAndFlush(MarketRegistration.builder().productId(product.getId())
					.sbProductId(product.getId()).marketType(market).marketProductName(payload.name())
					.marketIdentifiers("{}").marketDetailedInfo("{}").build());
			else
				reg = registrations.findForConnectionUpdate(reg.getId()).orElseThrow();
			reg.beginReviewedPublication(id);
			task.commit(reg.getId(), now);
			return view(task);
		});
	}

	public List<View> recent() {
		return tasks.findTop100ByOrderByCreatedAtDesc().stream().map(this::view).toList();
	}

	public View get(String id) {
		return view(tasks.findById(id).orElseThrow());
	}

	public void processOne() {
		Claim c = claim();
		if (c == null)
			return;
		var client = clients.getClient(SUPPORTED);
		if (!Objects.equals(c.prepared().account(), client.inspectionAccountReference())) {
			finish(c, "ACTION_REQUIRED", "계정이 변경되었습니다. 등록을 재전송하지 않습니다.", null, null);
			return;
		}
		try {
			if (c.post()) {
				Map<String, String> result = client.submitPreparedPublication(c.product(), c.id(),
					c.prepared().payload());
				String id = result == null ? null : result.get("originProductNo");
				if (id == null || !id.matches("[1-9][0-9]{0,17}"))
					throw new IllegalStateException("등록 응답에 정확한 원상품 번호가 없습니다.");
				finish(c, "VERIFY", "등록 번호를 수신했습니다. 원상품을 재조회해 검증합니다.", result, null);
			} else {
				boolean verified = client.verifyPreparedPublication(c.listingId(), c.product().getSbCode(),
					c.prepared().payload());
				if (!Objects.equals(c.prepared().account(), client.inspectionAccountReference())) {
					finish(c, "ACTION_REQUIRED", "재조회 중 계정이 변경되었습니다.", null, null);
					return;
				}
				finish(c, verified ? "REGISTERED" : "VERIFY",
					verified ? "원상품의 SB코드·상품명·카테고리·가격·수량·이미지·상세정보를 재조회하여 등록을 확인했습니다."
						: "마켓 재조회 결과가 검토한 정보와 일치하지 않습니다. 신규 등록을 다시 전송하지 않습니다.",
					null, null);
			}
		} catch (Exception e) {
			finish(c, c.post() ? "UNKNOWN_CREATE" : "VERIFY",
				(c.post() ? "등록 응답 미확인. 자동 재등록 중지: " : "등록 결과 조회 실패: ") + safe(e), null,
				e instanceof MarketTransferFailure f ? f : null);
		}
	}

	public Claim claim() {
		ensureGate();
		return tx().execute(s -> {
			var gate = gates.lock(MarketInspectionGate.SMART_STORE_SCOPE).orElseThrow();
			Instant now = now();
			if (!gate.available(now))
				return null;
			var due = tasks.due(SUPPORTED.name(), now, PageRequest.of(0, 1));
			if (due.isEmpty())
				return null;
			var initial = due.getFirst();
			var p = products.findForEdit(initial.getProductId()).orElse(null);
			var task = tasks.lock(initial.getId()).orElseThrow();
			if (task.getState().equals("POST_STARTED")) {
				task.finish("UNKNOWN_CREATE", "전송 중 실행이 중단되었습니다. 생성 여부 확인 전에는 다시 등록하지 않습니다.", now, now);
				return null;
			}
			var reg = registrations.findForConnectionUpdate(task.getRegistrationId()).orElseThrow();
			if (p == null || p.isDeleted() || p.getRevision() != task.getProductRevision()
				|| !task.getId().equals(reg.getPublicationOperationId())
				|| reg.getConnectionState() == MarketConnectionState.DETACHED_PROHIBITED) {
				boolean unsent = task.getState().equals("QUEUED");
				if (unsent)
					reg.cancelUnsentPublication(task.getId());
				task.finish(unsent ? "STALE" : "ACTION_REQUIRED", "등록 준비 후 상품·연결·금지 상태가 변경되었습니다.", now, now);
				return null;
			}
			if (task.getState().equals("QUEUED")) {
				var currentPrice = prices.explainForProduct(p, SUPPORTED, MarketSalePriceOverrides.EMPTY).salePrice();
				if (currentPrice == null || currentPrice.compareTo(payload(task).price()) != 0) {
					reg.cancelUnsentPublication(task.getId());
					task.finish("STALE", "가격 정책이 변경되어 미전송 등록을 취소했습니다. 다시 검토하세요.", now, now);
					return null;
				}
			}
			if (task.getAttempts() >= 7) {
				task.finish("ACTION_REQUIRED", "등록 결과 재조회 한도에 도달했습니다. 등록 번호로 다시 확인할 수 있습니다.", now, now);
				return null;
			}
			boolean post = task.getState().equals("QUEUED");
			String token = UUID.randomUUID().toString();
			Instant until = now.plusSeconds(180);
			task.claim(token, until, post);
			gate.claim(token, until);
			return new Claim(task.getId(), token, p, payload(task), post, task.getListingId());
		});
	}

	public void finish(Claim c, String state, String detail, Map<String, String> ids, MarketTransferFailure error) {
		tx().executeWithoutResult(s -> {
			var gate = gates.lock(MarketInspectionGate.SMART_STORE_SCOPE).orElseThrow();
			var product = products.findForEdit(c.product().getId()).orElseThrow();
			var task = tasks.lock(c.id()).orElseThrow();
			Instant now = now();
			if (!gate.owns(c.token(), now) || !task.owns(c.token(), now))
				return;
			var reg = registrations.findForConnectionUpdate(task.getRegistrationId()).orElseThrow();
			if (ids != null)
				task.identifiers(json(ids), ids.get("originProductNo"));
			String finalState = state, finalDetail = detail;
			if (!Objects.equals(payload(task).account(), clients.getClient(SUPPORTED).inspectionAccountReference())) {
				finalState = "ACTION_REQUIRED";
				finalDetail = "등록 처리 중 계정이 변경되었습니다. 생성된 상품의 귀속을 확인하세요.";
			}
			if ("REGISTERED".equals(finalState)) {
				if (product.getRevision() != task.getProductRevision() || product.isDeleted()
					|| !task.getId().equals(reg.getPublicationOperationId())
					|| reg.getConnectionState() == MarketConnectionState.DETACHED_PROHIBITED) {
					finalState = "ACTION_REQUIRED";
					finalDetail = "등록 결과를 조회했으나 상품·연결 상태가 변경되어 연결을 확정하지 않았습니다.";
				} else {
					reg.acceptReviewedPublication(task.getId(), task.getReturnedIdentifiers());
					events.save(new MarketConnectionEvent(reg.getId(), product.getId(), task.getMarket(),
						task.getListingId(), task.getActor(), "REVIEWED_REGISTRATION", "REGISTERED", "PRESENT", now,
						reg.getRevision(), json(Map.of("detail", detail, "operationId", task.getId()))));
				}
			}
			Instant next = now.plusSeconds(Math.min(300, 30L * Math.max(1, task.getAttempts())));
			if (error != null && error.getRetryAfter() != null && error.getRetryAfter().isAfter(next))
				next = error.getRetryAfter();
			task.finish(finalState, finalDetail, now, next);
			gate.release(error != null && error.rateLimited() ? next : now.plusSeconds(2));
		});
	}

	/** Resolve uncertain creation by reading a user-supplied ID, never by sending another POST. */
	public View recheck(String id, String listingId, String actor) {
		if (listingId == null || !listingId.matches("[1-9][0-9]{0,17}"))
			throw new IllegalArgumentException("마켓 원상품 번호를 입력하세요.");
		var initial = tasks.findById(id).orElseThrow();
		return tx().execute(s -> {
			products.findForEdit(initial.getProductId()).orElseThrow();
			var task = tasks.lock(id).orElseThrow();
			if (!task.getActor().equals(actor))
				throw new ProductEditConflictException("등록한 작업자만 결과를 다시 확인할 수 있습니다.");
			if (!Set.of("UNKNOWN_CREATE", "ACTION_REQUIRED").contains(task.getState()))
				throw new ProductEditConflictException("현재 재조회할 수 있는 상태가 아닙니다.");
			var reg = registrations.findForConnectionUpdate(task.getRegistrationId()).orElseThrow();
			if (listingId.equals(reg.extractLiveLookupId()))
				throw new IllegalArgumentException("과거 삭제 상품번호는 새 등록 결과로 연결할 수 없습니다.");
			if (!Objects.equals(payload(task).account(), clients.getClient(SUPPORTED).inspectionAccountReference()))
				throw new IllegalStateException("등록을 요청한 계정으로 연결한 후 다시 확인하세요.");
			task.identifiers(json(Map.of("originProductNo", listingId)), listingId);
			task.resetVerification(now());
			return view(task);
		});
	}

	private String fingerprint(MarketRegistration r) {
		return r == null ? "MISSING" : r.getId() + ":" + r.getMarketIdentifiers() + ":" + r.getConnectionState() + ":"
			+ r.getPublicationOperationId();
	}

	private PreparedMarketPublication payload(MarketPublicationTask t) {
		try {
			return mapper.readValue(t.getPrepared(), PreparedMarketPublication.class);
		} catch (Exception e) {
			throw new IllegalStateException("등록 검토 기록을 읽지 못했습니다.", e);
		}
	}

	private View view(MarketPublicationTask t) {
		var p = payload(t);
		return new View(t.getId(), t.getProductId(), t.getSbCode(), t.getMarket(), t.getActor(), t.getState(),
			t.getDetail(), p.name(), p.categoryId(), p.categoryPath(), p.price(), p.quantity(), p.representativeImage(),
			t.getListingId(), t.getExpiresAt(), t.getCreatedAt(), t.getCheckedAt(), t.getCommittedAt() != null);
	}

	private String json(Object value) {
		try {
			return mapper.writeValueAsString(value);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private String safe(Exception e) {
		return e instanceof MarketTransferFailure || e instanceof IllegalStateException
			|| e instanceof UnsupportedOperationException ? Objects.toString(e.getMessage(), "원인 미제공")
				: "처리 오류. 작업 로그를 확인하세요.";
	}

	private void validatePairs(List<Pair> pairs, String actor) {
		if (actor == null || actor.isBlank() || actor.length() > 200 || pairs == null || pairs.isEmpty()
			|| pairs.size() > 100 || pairs.stream()
				.anyMatch(p -> p == null || p.productId() == null || p.productId() <= 0 || p.market() == null))
			throw new IllegalArgumentException("등록 후보를 1~100개 선택하세요.");
	}

	private Instant now() {
		return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", java.sql.Timestamp.class).toInstant();
	}

	private TransactionTemplate tx() {
		return new TransactionTemplate(transactions);
	}

	private void ensureGate() {
		if (gates.existsById(MarketInspectionGate.SMART_STORE_SCOPE))
			return;
		try {
			tx().executeWithoutResult(
				s -> gates.saveAndFlush(new MarketInspectionGate(MarketInspectionGate.SMART_STORE_SCOPE, now())));
		} catch (DataIntegrityViolationException e) {
			if (!gates.existsById(MarketInspectionGate.SMART_STORE_SCOPE))
				throw e;
		}
	}
}
