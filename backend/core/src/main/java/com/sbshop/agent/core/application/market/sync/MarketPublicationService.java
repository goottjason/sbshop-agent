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
	public static final Set<MarketType> SUPPORTED = Set.of(MarketType.SMART_STORE, MarketType.COUPANG,
		MarketType.CAFE24);
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

	public record Pair(Long productId, MarketType market,
		com.sbshop.agent.core.domain.market.client.dto.MarketPublishContext context) {
		public Pair {
			context = context == null ? com.sbshop.agent.core.domain.market.client.dto.MarketPublishContext.empty()
				: context;
		}

		public Pair(Long productId, MarketType market) {
			this(productId, market, null);
		}
	}
	public record Candidate(Long productId, String sbCode, String market, String oldListingId, String connectionState,
		String reason, boolean selectable) {
	}
	public record View(String id, Long productId, String sbCode, String market, String actor, String state,
		String detail,
		String name, String categoryId, String categoryPath, BigDecimal price, int quantity, String image,
		String listingId, Instant expiresAt, Instant createdAt, Instant checkedAt, boolean committed,
		Map<String, String> shippingSummary) {
	}
	public record PreparedResult(List<View> prepared, List<Candidate> excluded) {
	}
	public record Claim(String id, String token, Product product, PreparedMarketPublication prepared, boolean post,
		String listingId, MarketType market) {
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
		else if (p.isSourceGone() || p.getStockStatus() == null
			|| p.getLastCrawlError() != null && !p.getLastCrawlError().isBlank())
			reason = "소싱처의 정상 재고 관측을 확인할 수 없습니다. 최신 수집 결과를 검토하세요.";
		else if (p.getSalesQuantity() == null || p.getSalesQuantity() <= 0 || p.getSalesQuantity() > 999999)
			reason = "신규 등록 판매용 수량은 1~999,999 정수여야 합니다.";
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
		if (eligible && !SUPPORTED.contains(market)) {
			reason += " 등록 요청 고정·결과 검증 연결을 준비 중입니다.";
			eligible = false;
		}
		return new Candidate(id, p == null ? null : p.getSbCode(), market.name(), old,
			state == null ? "MISSING" : state.name(), reason, eligible);
	}

	@org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
	public PreparedResult prepare(List<Pair> selected, String actor) {
		requireNoTransaction();
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
			PreparationLease reservation = null;
			try {
				reservation = reservePreparation(pair.market());
				final PreparationLease lease = reservation;
				try (var scope = MarketPreparationRequestScope.open(pair.market(), () -> checkPreparation(lease),
					retry -> deferPreparation(lease, retry))) {
					MarketPreparationRequestScope.beforeRequest(pair.market());
					var quote = prices.explainForProduct(product, pair.market(), MarketSalePriceOverrides.EMPTY);
					if (quote.basis() != MarketSalePriceResolver.Basis.CALCULATED || quote.salePrice() == null)
						throw new IllegalStateException("최소마진을 확인할 수 없습니다.");
					var payload = clients.getClient(pair.market()).preparePublication(product, quote.salePrice(),
						pair.context());
					MarketPreparationRequestScope.beforeRequest(pair.market());
					if (payload == null || payload.account() == null || payload.categoryId() == null
						|| payload.categoryId().isBlank() || payload.payload() == null || payload.payload().isBlank()
						|| payload.quantity() != product.getSalesQuantity() || payload.price() == null
						|| payload.price().compareTo(quote.salePrice()) != 0
						|| !Objects.equals(payload.account(),
							clients.getClient(pair.market()).inspectionAccountReference()))
						throw new IllegalStateException("계정·카테고리를 확인할 수 없습니다.");
					View view = tx().execute(s -> {
						var p = products.findForEdit(pair.productId()).orElseThrow();
						var r = registrations.findByProductIdAndMarketType(pair.productId(), pair.market())
							.orElse(null);
						if (p.getRevision() != revision || !fingerprint.equals(fingerprint(r))
							|| !candidate(pair.productId(), pair.market()).selectable())
							throw new ProductEditConflictException("등록 준비 중 상품·연결이 변경되었습니다.");
						var task = tasks.save(new MarketPublicationTask(UUID.randomUUID().toString(), p.getId(),
							r == null ? null : r.getId(), revision, pair.market().name(), actor, p.getSbCode(),
							fingerprint,
							json(payload), candidate.reason(), now()));
						return view(task);
					});
					prepared.add(view);
				}
			} catch (Exception e) {
				if (reservation != null && e instanceof MarketTransferFailure f && f.rateLimited())
					deferPreparation(reservation, f.getRetryAfter());
				excluded.add(new Candidate(pair.productId(), candidate.sbCode(), candidate.market(),
					candidate.oldListingId(), candidate.connectionState(), "등록 준비 실패: " + safe(e), false));
			} finally {
				if (reservation != null)
					releasePreparation(reservation);
			}
		}
		return new PreparedResult(prepared, excluded);
	}

	private record PreparationLease(MarketType market, String token, String account) {
	}

	private PreparationLease reservePreparation(MarketType market) {
		ensureGate(market);
		return tx().execute(s -> {
			var gate = gates.lock(gateId(market)).orElseThrow();
			Instant now = now();
			if (!gate.available(now))
				throw new MarketPreparationRequestScope.Blocked("다른 마켓 작업 또는 공용 호출 제한 대기 중입니다. 잠시 후 등록 내용을 다시 준비하세요.");
			String account = clients.getClient(market).inspectionAccountReference();
			if (account == null || account.isBlank())
				throw new MarketPreparationRequestScope.Blocked("등록 준비 계정을 확인할 수 없습니다.");
			String token = UUID.randomUUID().toString();
			gate.claim(token, now.plusSeconds(180));
			return new PreparationLease(market, token, account);
		});
	}

	private void checkPreparation(PreparationLease lease) {
		requireNoTransaction();
		tx().executeWithoutResult(s -> {
			var gate = gates.lock(gateId(lease.market())).orElseThrow();
			Instant now = now();
			if (!gate.owns(lease.token(), now) || gate.getNextAllowedAt().isAfter(now))
				throw new MarketPreparationRequestScope.Blocked(
					"등록 준비의 실행권 또는 공용 호출 제한이 변경되었습니다. 다음 API 요청을 보내지 않았습니다.");
			if (!Objects.equals(lease.account(), clients.getClient(lease.market()).inspectionAccountReference()))
				throw new MarketPreparationRequestScope.Blocked("등록 준비 중 계정이 변경되어 다음 API 요청을 중지했습니다.");
			gate.claim(lease.token(), now.plusSeconds(180));
		});
	}

	private void deferPreparation(PreparationLease lease, Instant retryAfter) {
		tx().executeWithoutResult(s -> {
			var gate = gates.lock(gateId(lease.market())).orElseThrow();
			Instant minimum = now().plusSeconds(60);
			gate.deferUntil(retryAfter != null && retryAfter.isAfter(minimum) ? retryAfter : minimum);
		});
	}

	private void releasePreparation(PreparationLease lease) {
		tx().executeWithoutResult(s -> {
			var gate = gates.lock(gateId(lease.market())).orElseThrow();
			if (Objects.equals(lease.token(), gate.getLeaseToken())) {
				Instant now = now();
				gate.release(gate.getNextAllowedAt().isAfter(now) ? gate.getNextAllowedAt() : now);
			}
		});
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
		processOne(MarketType.SMART_STORE);
	}

	@org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
	public void processOne(MarketType market) {
		requireNoTransaction();
		Claim c = claim(market);
		if (c == null)
			return;
		var client = clients.getClient(market);
		if (!Objects.equals(c.prepared().account(), client.inspectionAccountReference())) {
			if (c.post())
				beginPost(c);
			else
				finish(c, "ACTION_REQUIRED", "계정이 변경되었습니다. 등록을 재전송하지 않습니다.", null, null);
			return;
		}
		try {
			if (c.post()) {
				Map<String, String> result = client.submitPreparedPublication(c.product(), c.id(),
					c.prepared().payload(), () -> {
						if (!beginPost(c))
							throw new WriteAborted();
					});
				String id = result == null ? null : result.get(listingKey(market));
				if (!validId(id))
					throw new IllegalStateException("등록 응답에 정확한 상품번호가 없습니다.");
				finish(c, "VERIFY", "등록 번호를 수신했습니다. 상품과 품목·심사 결과를 재조회해 검증합니다.", result, null);
			} else {
				var proof = client.readPreparedPublication(c.listingId(), c.product().getSbCode(),
					c.prepared().payload());
				if (!Objects.equals(c.prepared().account(), client.inspectionAccountReference())) {
					finish(c, "ACTION_REQUIRED", "재조회 중 계정이 변경되었습니다.", null, null);
					return;
				}
				if (proof == null)
					throw new IllegalStateException("등록 재조회 증거가 없습니다.");
				if (proof.setupRequired()) {
					if (!validVerifiedIdentifiers(c, proof.identifiers())) {
						finish(c, "ACTION_REQUIRED", "설정 전 재조회 상품·품목 식별자가 생성 대상과 일치하지 않습니다.", null, null);
						return;
					}
					client.finalizePreparedPublication(c.listingId(), c.product().getSbCode(), c.prepared().payload(),
						() -> {
							if (!beginSetup(c))
								throw new WriteAborted();
						});
					finish(c, "VERIFY", "새 상품 설정을 전송했습니다. 판매용 수량·판매 시작 결과를 별도 조회합니다.", null, null);
					return;
				}
				String state = proof.approvalPending() ? "AWAITING_APPROVAL"
					: proof.verified() ? "REGISTERED" : "VERIFY";
				finishVerified(c, state, proof.detail(), proof.identifiers().isEmpty() ? null : proof.identifiers(),
					null, proof);
			}
		} catch (WriteAborted stopped) {} catch (Exception e) {
			finish(c, c.post() ? "UNKNOWN_CREATE" : definiteWriteRejection(c, e) ? "ACTION_REQUIRED" : "VERIFY",
				(c.post() ? "등록 응답 미확인. 자동 재등록 중지: " : "등록 결과 조회 실패: ") + safe(e), null,
				e instanceof MarketTransferFailure f ? f : null);
		}
	}

	public Claim claim() {
		return claim(MarketType.SMART_STORE);
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
				var currentPrice = prices.explainForProduct(p, market, MarketSalePriceOverrides.EMPTY).salePrice();
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
			return new Claim(task.getId(), token, p, payload(task), post, task.getListingId(), market);
		});
	}

	public boolean beginPost(Claim c) {
		return tx().execute(s -> {
			var gate = gates.lock(gateId(c.market())).orElseThrow();
			var product = products.findForEdit(c.product().getId()).orElseThrow();
			var task = tasks.lock(c.id()).orElseThrow();
			Instant now = now();
			if (!gate.owns(c.token(), now) || !task.owns(c.token(), now))
				return false;
			var reg = registrations.findForConnectionUpdate(task.getRegistrationId()).orElseThrow();
			if (task.isPostAuthorized())
				return false;
			if (gate.getNextAllowedAt().isAfter(now)) {
				task.finish("QUEUED", "공유 호출 제한 해제 후 미전송 등록을 다시 검사합니다.", now, gate.getNextAllowedAt());
				gate.release(gate.getNextAllowedAt());
				return false;
			}
			var quote = prices.explainForProduct(product, c.market(), MarketSalePriceOverrides.EMPTY);
			if (!c.post() || !"POST_STARTED".equals(task.getState()) || product.isDeleted() || product.isSourceGone()
				|| product.getStockStatus() != com.sbshop.agent.core.domain.product.enums.StockStatus.IN_STOCK
				|| product.getLastCrawlError() != null && !product.getLastCrawlError().isBlank()
				|| product.getRevision() != task.getProductRevision()
				|| !task.getId().equals(reg.getPublicationOperationId())
				|| reg.getConnectionState() == MarketConnectionState.DETACHED_PROHIBITED
				|| !Objects.equals(payload(task).account(), clients.getClient(c.market()).inspectionAccountReference())
				|| quote.basis() != MarketSalePriceResolver.Basis.CALCULATED || quote.salePrice() == null
				|| quote.salePrice().compareTo(payload(task).price()) != 0) {
				reg.cancelUnsentPublication(task.getId());
				task.finish("STALE", "등록 직전 상품·계정·연결·소싱 재고·가격이 변경되어 전송하지 않았습니다.", now, now);
				gate.release(now.plusSeconds(2));
				return false;
			}
			task.authorizePost();
			gate.claim(c.token(), now.plusSeconds(180));
			return true;
		});
	}

	public boolean beginSetup(Claim c) {
		return tx().execute(s -> {
			var gate = gates.lock(gateId(c.market())).orElseThrow();
			var product = products.findForEdit(c.product().getId()).orElseThrow();
			var task = tasks.lock(c.id()).orElseThrow();
			Instant now = now();
			if (!gate.owns(c.token(), now) || !task.owns(c.token(), now))
				return false;
			var reg = registrations.findForConnectionUpdate(task.getRegistrationId()).orElseThrow();
			if (gate.getNextAllowedAt().isAfter(now)) {
				task.finish("VERIFY", "공유 호출 제한 해제 후 새 상품 설정을 재조회합니다.", now, gate.getNextAllowedAt());
				gate.release(gate.getNextAllowedAt());
				return false;
			}
			var ids = returnedIdentifiers(task);
			boolean ownedReceipt = task.getId().equals(ids.get("_sbshop_receipt_operation"))
				&& Objects.equals(c.listingId(), ids.get(listingKey(c.market())));
			var quote = prices.explainForProduct(product, c.market(), MarketSalePriceOverrides.EMPTY);
			if (c.post() || c.market() != MarketType.CAFE24 || !ownedReceipt || task.getSetupWrites() >= 3
				|| product.isDeleted() || product.isSourceGone()
				|| product.getStockStatus() != com.sbshop.agent.core.domain.product.enums.StockStatus.IN_STOCK
				|| product.getLastCrawlError() != null && !product.getLastCrawlError().isBlank()
				|| product.getRevision() != task.getProductRevision()
				|| !task.getId().equals(reg.getPublicationOperationId())
				|| reg.getConnectionState() == MarketConnectionState.DETACHED_PROHIBITED
				|| !Objects.equals(payload(task).account(), clients.getClient(c.market()).inspectionAccountReference())
				|| quote.basis() != MarketSalePriceResolver.Basis.CALCULATED || quote.salePrice() == null
				|| quote.salePrice().compareTo(payload(task).price()) != 0) {
				task.finish("ACTION_REQUIRED",
					!ownedReceipt ? "원본 생성 응답으로 귀속이 확인되지 않은 상품번호입니다. 자동 수량 설정·판매 시작 없이 재조회만 허용합니다."
						: "등록 후 상품·계정·연결·가격 변경 또는 설정 전송 한도에 도달했습니다. 변경 내용을 확인하세요.",
					now, now);
				gate.release(now.plusSeconds(2));
				return false;
			}
			task.authorizeSetup();
			gate.claim(c.token(), now.plusSeconds(180));
			return true;
		});
	}

	private boolean definiteWriteRejection(Claim c, Exception e) {
		return !c.post() && tasks.findById(c.id()).map(t -> t.getSetupWrites() > 0).orElse(false)
			&& e instanceof MarketTransferFailure f
			&& Set.of("HTTP_400", "HTTP_401", "HTTP_403", "HTTP_404", "HTTP_422").contains(f.getCode());
	}

	public void finish(Claim c, String state, String detail, Map<String, String> ids, MarketTransferFailure error) {
		finishVerified(c, state, detail, ids, error, null);
	}

	private void finishVerified(Claim c, String state, String detail, Map<String, String> ids,
		MarketTransferFailure error,
		com.sbshop.agent.core.domain.market.client.dto.VerifiedMarketPublication proof) {
		tx().executeWithoutResult(s -> {
			var gate = gates.lock(gateId(c.market())).orElseThrow();
			var product = products.findForEdit(c.product().getId()).orElseThrow();
			var task = tasks.lock(c.id()).orElseThrow();
			Instant now = now();
			Instant next = now.plusSeconds(Math.min(300, 30L * Math.max(1, task.getAttempts())));
			if (error != null && error.getRetryAfter() != null && error.getRetryAfter().isAfter(next))
				next = error.getRetryAfter();
			if (error != null && error.rateLimited())
				gate.deferUntil(next);
			if (!gate.owns(c.token(), now) || !task.owns(c.token(), now))
				return;
			var reg = registrations.findForConnectionUpdate(task.getRegistrationId()).orElseThrow();
			String finalState = state, finalDetail = detail == null ? "등록 결과를 확인하지 못했습니다." : detail;
			if (ids != null) {
				String returned = ids.get(listingKey(c.market()));
				if (!validId(returned) || c.listingId() != null && !c.listingId().equals(returned)) {
					finalState = "ACTION_REQUIRED";
					finalDetail = "조회된 등록 상품번호가 요청과 일치하지 않습니다.";
				} else {
					var merged = returnedIdentifiers(task);
					merged.putAll(ids);
					if (c.post() && task.isPostAuthorized())
						merged.put("_sbshop_receipt_operation", task.getId());
					task.identifiers(json(merged), returned);
				}
			}
			if (!Objects.equals(payload(task).account(), clients.getClient(c.market()).inspectionAccountReference())) {
				finalState = "ACTION_REQUIRED";
				finalDetail = "등록 처리 중 계정이 변경되었습니다. 생성된 상품의 귀속을 확인하세요.";
			}
			if ("REGISTERED".equals(finalState)) {
				if (c.post() || proof == null || !proof.verified() || proof.approvalPending()
					|| !validVerifiedIdentifiers(c, proof.identifiers())
					|| product.getRevision() != task.getProductRevision() || product.isDeleted()
					|| !task.getId().equals(reg.getPublicationOperationId())
					|| reg.getConnectionState() == MarketConnectionState.DETACHED_PROHIBITED) {
					finalState = "ACTION_REQUIRED";
					finalDetail = "상품·품목·계정·심사 완료의 별도 조회 증거가 없거나 연결이 변경되어 등록을 확정하지 않았습니다.";
				} else {
					var linkedIds = returnedIdentifiers(task);
					linkedIds.remove("_sbshop_receipt_operation");
					reg.acceptReviewedPublication(task.getId(), json(linkedIds));
					events.save(new MarketConnectionEvent(reg.getId(), product.getId(), task.getMarket(),
						task.getListingId(), task.getActor(), "REVIEWED_REGISTRATION", "REGISTERED", "PRESENT", now,
						reg.getRevision(), json(Map.of("detail", finalDetail, "operationId", task.getId()))));
				}
			}
			if ("AWAITING_APPROVAL".equals(finalState))
				next = now.plusSeconds(900);
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
			if (!Objects.equals(payload(task).account(),
				clients.getClient(MarketType.valueOf(task.getMarket())).inspectionAccountReference()))
				throw new IllegalStateException("등록을 요청한 계정으로 연결한 후 다시 확인하세요.");
			var previous = returnedIdentifiers(task);
			if (!listingId.equals(task.getListingId()))
				previous.clear();
			previous.put(listingKey(MarketType.valueOf(task.getMarket())), listingId);
			task.identifiers(json(previous), listingId);
			if (task.getId().equals(previous.get("_sbshop_receipt_operation")))
				task.retrySetup();
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
			t.getListingId(), t.getExpiresAt(), t.getCreatedAt(), t.getCheckedAt(), t.getCommittedAt() != null,
			p.shippingSummary());
	}

	private String json(Object value) {
		try {
			return mapper.writeValueAsString(value);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private String safe(Exception e) {
		for (Throwable cause = e; cause != null; cause = cause.getCause()) {
			if (cause instanceof MarketPreparationRequestScope.Blocked blocked)
				return blocked.getMessage();
			if (cause == cause.getCause())
				break;
		}
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
		var tx = new TransactionTemplate(transactions);
		tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		return tx;
	}

	private void ensureGate(MarketType market) {
		String id = gateId(market);
		if (gates.existsById(id))
			return;
		try {
			tx().executeWithoutResult(s -> gates.saveAndFlush(new MarketInspectionGate(id, now())));
		} catch (DataIntegrityViolationException e) {
			if (!gates.existsById(id))
				throw e;
		}
	}

	private static String gateId(MarketType market) {
		return market.name() + "_ORIGIN_READ";
	}

	private static String listingKey(MarketType market) {
		return switch (market) {
			case SMART_STORE -> "originProductNo";
			case COUPANG -> "sellerProductId";
			case CAFE24 -> "product_no";
			default -> throw new IllegalArgumentException("미지원 등록 마켓");
		};
	}

	private static boolean validId(String id) {
		return id != null && id.matches("[1-9][0-9]{0,17}");
	}

	private boolean validVerifiedIdentifiers(Claim c, Map<String, String> ids) {
		return Objects.equals(c.listingId(), ids.get(listingKey(c.market())))
			&& (c.market() != MarketType.COUPANG || validId(ids.get("vendorItemId")))
			&& (c.market() != MarketType.CAFE24
				|| ids.get("product_code") != null && ids.get("product_code").matches("P[A-Z0-9]{7}")
					&& ids.get("variant_code") != null && ids.get("variant_code").matches("P[A-Z0-9]{11}")
					&& ids.get("variant_code").startsWith(ids.get("product_code")));
	}

	private Map<String, String> returnedIdentifiers(MarketPublicationTask task) {
		try {
			return task.getReturnedIdentifiers() == null ? new HashMap<>()
				: new HashMap<>(mapper.readValue(task.getReturnedIdentifiers(),
					new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {}));
		} catch (Exception e) {
			throw new IllegalStateException("등록 식별자 이력을 읽지 못했습니다.", e);
		}
	}

	private void requireNoTransaction() {
		if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
			throw new IllegalStateException("등록 마켓 호출은 DB 트랜잭션 밖에서 실행해야 합니다.");
	}

	private static final class WriteAborted extends RuntimeException {}
}
