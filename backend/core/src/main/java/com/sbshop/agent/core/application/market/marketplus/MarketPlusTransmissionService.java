package com.sbshop.agent.core.application.market.marketplus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.marketplus.*;
import com.sbshop.agent.core.domain.market.repository.*;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@lombok.extern.slf4j.Slf4j
public class MarketPlusTransmissionService {
	public record Row(MarketType market, String sellerAccount, String cafe24ProductNo, String cafe24ProductCode,
		String externalId, String transferType, String outcome, String detail, Instant requestedAt,
		Instant completedAt) {
		public Row {
			if (market != MarketType.GMARKET && market != MarketType.AUCTION)
				throw new IllegalArgumentException("지마켓·옥션 전송 이력만 지원합니다.");
			bounded(sellerAccount, 200);
			bounded(externalId, 200);
			bounded(transferType, 100);
			bounded(detail, 4000);
			if (cafe24ProductNo == null || !cafe24ProductNo.matches("[1-9][0-9]{0,18}")
				|| cafe24ProductCode == null || !cafe24ProductCode.matches("P[A-Z0-9]{7,29}"))
				throw new IllegalArgumentException("카페24 상품 식별자를 확인하세요.");
			if (!("SUCCESS".equals(outcome) && detail.startsWith("[성공]"))
				&& !("FAILURE".equals(outcome) && detail.startsWith("[실패]")))
				throw new IllegalArgumentException("전송 결과와 원문이 일치하지 않습니다.");
			if (requestedAt == null || completedAt == null || completedAt.isBefore(requestedAt))
				throw new IllegalArgumentException("전송 요청·완료 시각을 확인하세요.");
		}
	}
	public record Batch(int schemaVersion, String source, String coverage, String mallId, int shopNo,
		Instant capturedAt, List<Row> rows) {
		public Batch {
			bounded(mallId, 100);
			if (schemaVersion != 1 || !"LIVE_CHROME_MARKETPLUS".equals(source) || !"CURRENT_PAGE".equals(coverage)
				|| shopNo != 1 || capturedAt == null || capturedAt.isAfter(Instant.now().plusSeconds(300))
				|| rows == null || rows.isEmpty() || rows.size() > 100 || rows.stream().anyMatch(Objects::isNull))
				throw new IllegalArgumentException("수집 형식·쇼핑몰 번호·시각·행 수(1~100)를 확인하세요.");
			rows = List.copyOf(rows);
			if (rows.stream().anyMatch(r -> r.completedAt().isAfter(capturedAt)))
				throw new IllegalArgumentException("수집 시각보다 늦은 전송 이력입니다.");
		}
	}
	public record Item(int row, String externalId, Long productId, String result, String detail, boolean retryable) {
		public Item(int row, String externalId, Long productId, String result, String detail) {
			this(row, externalId, productId, result, detail, false);
		}
	}
	public record ImportResult(int saved, int duplicate, int rejected, List<Item> items) {
	}
	public record HistoryItem(MarketPlusTransmission observation, boolean currentConnection) {
	}
	public record Summary(String outcome, String reasonCode, String detail, Instant completedAt, Instant capturedAt) {
	}

	private final MarketRegistrationRepository registrations;
	private final MarketCredentialRepository credentials;
	private final MarketPlusTransmissionRepository events;
	private final ObjectMapper mapper;
	private final TransactionTemplate tx;
	private final String gmarketAccount;
	private final String auctionAccount;

	public MarketPlusTransmissionService(MarketRegistrationRepository registrations,
		MarketCredentialRepository credentials,
		MarketPlusTransmissionRepository events, ObjectMapper mapper, PlatformTransactionManager manager,
		@Value("${marketplus.gmarket-account:}")
		String gmarketAccount,
		@Value("${marketplus.auction-account:}")
		String auctionAccount) {
		this.registrations = registrations;
		this.credentials = credentials;
		this.events = events;
		this.mapper = mapper;
		this.gmarketAccount = gmarketAccount;
		this.auctionAccount = auctionAccount;
		this.tx = new TransactionTemplate(manager);
		this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	public ImportResult ingest(Batch batch, String actor) {
		bounded(actor, 200);
		var credential = credentials.findByMarketType(MarketType.CAFE24)
			.orElseThrow(() -> new IllegalArgumentException("카페24 계정이 설정되지 않았습니다."));
		if (!Boolean.TRUE.equals(credential.getIsActive()))
			throw new IllegalStateException("카페24 계정의 활성 상태 확인이 필요합니다. 전송 이력을 가져올 수 없습니다.");
		if (!batch.mallId().equals(credential.getClientId()))
			throw new IllegalArgumentException("수집한 쇼핑몰과 시스템 카페24 계정이 일치하지 않습니다.");
		List<Item> items = new ArrayList<>();
		for (int i = 0; i < batch.rows().size(); i++) {
			int number = i + 1;
			Row row = batch.rows().get(i);
			try {
				items.add(Objects.requireNonNull(tx.execute(status -> save(batch, row, actor, number))));
			} catch (RuntimeException e) {
				// Each row commits separately. A database error must never be reported as saved.
				log.warn("MarketPlus history import failed: row={}, market={}, errorType={}", number, row.market(),
					e.getClass().getSimpleName());
				items.add(
					new Item(number, row.externalId(), null, "REJECTED", "저장 결과 미확인: 같은 파일로 재시도하세요. 중복 저장은 방지됩니다.",
						true));
			}
		}
		return new ImportResult(count(items, "SAVED"), count(items, "DUPLICATE"), count(items, "REJECTED"),
			List.copyOf(items));
	}

	private Item save(Batch batch, Row row, String actor, int number) {
		String expected = row.market() == MarketType.GMARKET ? gmarketAccount : auctionAccount;
		if (expected.isBlank() || !expected.equals(row.sellerAccount()))
			return rejected(number, row, "서버에 확인된 판매 계정이 없거나 수집 계정과 다릅니다.");
		var candidates = registrations.findIdentifierCandidates(MarketType.CAFE24, row.cafe24ProductNo()).stream()
			.filter(r -> matches(r, row)).toList();
		if (candidates.size() != 1)
			return rejected(number, row, "카페24 번호·코드와 마켓 상품번호가 일치하는 연결이 없거나 여러 개입니다.");
		var reg = registrations.findForConnectionUpdate(candidates.getFirst().getId()).orElse(null);
		if (reg == null || !matches(reg, row))
			return rejected(number, row, "수집 후 연결의 상품번호가 변경되었습니다. 다시 확인하세요.");
		String hash = fingerprint(batch, row, reg.getId());
		if (events.existsByFingerprint(hash))
			return new Item(number, row.externalId(), reg.getProductId(), "DUPLICATE", "이미 저장된 동일 내용의 관측 이력입니다.");
		events.saveAndFlush(MarketPlusTransmission.builder().registrationId(reg.getId()).productId(reg.getProductId())
			.fingerprint(hash).mallId(batch.mallId()).shopNo(batch.shopNo()).market(row.market().name())
			.sellerAccount(row.sellerAccount()).cafe24ProductNo(row.cafe24ProductNo())
			.cafe24ProductCode(row.cafe24ProductCode())
			.externalId(row.externalId()).transferType(row.transferType()).outcome(row.outcome())
			.reasonCode(reason(row))
			.detail(row.detail()).requestedAt(row.requestedAt()).completedAt(row.completedAt())
			.capturedAt(batch.capturedAt())
			.actor(actor).build());
		return new Item(number, row.externalId(), reg.getProductId(), "SAVED",
			"전송 이력 저장 완료. 현재 판매 상태·필드 일치는 별도 확인이 필요합니다.");
	}

	public List<HistoryItem> history(Long productId) {
		var current = registrations.findByProductId(productId);
		credentials.findByMarketType(MarketType.CAFE24).ifPresent(c -> {
			if (!Boolean.TRUE.equals(c.getIsActive()))
				throw new IllegalStateException("카페24 계정의 활성 상태 확인이 필요합니다. 현재 연결의 전송 이력을 분류할 수 없습니다.");
		});
		String currentMall = credentials.findByMarketType(MarketType.CAFE24)
			.filter(c -> Boolean.TRUE.equals(c.getIsActive())).map(c -> c.getClientId()).orElse("");
		return events.findTop100ByProductIdOrderByCompletedAtDescIdDesc(productId).stream().map(e -> new HistoryItem(e,
			e.getShopNo() == 1 && e.getMallId().equals(currentMall)
				&& e.getSellerAccount().equals("GMARKET".equals(e.getMarket()) ? gmarketAccount : auctionAccount)
				&& current.stream()
					.anyMatch(r -> r.getId().equals(e.getRegistrationId()) && r.getProductId().equals(e.getProductId())
						&& r.getMarketType() == MarketType.CAFE24
						&& !r.getConnectionState().detached()
						&& !r.connectionStateFor(MarketType.valueOf(e.getMarket())).detached()
						&& matches(r, MarketType.valueOf(e.getMarket()), e.getCafe24ProductNo(),
							e.getCafe24ProductCode(), e.getExternalId()))))
			.toList();
	}

	/** One batch query per product page; successes of other operation types do not clear a failure. */
	public Map<Long, Map<MarketType, Summary>> summaries(List<MarketRegistration> current) {
		String mall = credentials.findByMarketType(MarketType.CAFE24)
			.filter(c -> Boolean.TRUE.equals(c.getIsActive())).map(c -> c.getClientId()).orElse("");
		if (mall.isBlank())
			return Map.of();
		Map<Long, MarketRegistration> byId = new HashMap<>();
		for (var r : current) {
			if (r.getId() != null && r.getMarketType() == MarketType.CAFE24 && !r.getConnectionState().detached())
				byId.put(r.getId(), r);
		}
		if (byId.isEmpty())
			return Map.of();
		Map<Long, Map<MarketType, List<MarketPlusTransmission>>> grouped = new HashMap<>();
		for (var e : events.findLatestObservations(List.copyOf(byId.keySet()))) {
			var r = byId.get(e.getRegistrationId());
			var market = MarketType.valueOf(e.getMarket());
			String account = market == MarketType.GMARKET ? gmarketAccount : auctionAccount;
			if (r == null || e.getShopNo() != 1 || !r.getProductId().equals(e.getProductId())
				|| !e.getMallId().equals(mall) || !e.getSellerAccount().equals(account)
				|| r.connectionStateFor(market).detached()
				|| !matches(r, market, e.getCafe24ProductNo(), e.getCafe24ProductCode(), e.getExternalId()))
				continue;
			grouped.computeIfAbsent(r.getProductId(), k -> new HashMap<>())
				.computeIfAbsent(market, k -> new ArrayList<>()).add(e);
		}
		Map<Long, Map<MarketType, Summary>> result = new HashMap<>();
		grouped.forEach((product, markets) -> {
			Map<MarketType, Summary> summaries = new HashMap<>();
			markets.forEach((market, observations) -> summaries.put(market, summarize(observations)));
			result.put(product, summaries);
		});
		return result;
	}

	public record Readiness(boolean ready, List<String> reasons) {
	}

	public Readiness readiness() {
		List<String> reasons = new ArrayList<>();
		var c = credentials.findByMarketType(MarketType.CAFE24).orElse(null);
		if (c == null || c.getClientId() == null || c.getClientId().isBlank())
			reasons.add("카페24 쇼핑몰 계정이 설정되지 않았습니다.");
		else if (!Boolean.TRUE.equals(c.getIsActive()))
			reasons.add("카페24 계정의 활성 상태 확인이 필요합니다.");
		if (gmarketAccount.isBlank())
			reasons.add("G마켓 판매 계정의 연결 설정이 필요합니다.");
		if (auctionAccount.isBlank())
			reasons.add("옥션 판매 계정의 연결 설정이 필요합니다.");
		return new Readiness(reasons.isEmpty(), List.copyOf(reasons));
	}

	public com.sbshop.agent.core.domain.market.marketplus.MarketPlusSearchScope requireSearchScope() {
		var status = readiness();
		if (!status.ready())
			throw new IllegalStateException("마켓플러스 전송 이슈를 조회할 수 없습니다. " + String.join(" ", status.reasons()));
		var c = credentials.findByMarketType(MarketType.CAFE24)
			.orElseThrow(() -> new IllegalStateException("카페24 계정이 변경되었습니다. 다시 조회하세요."));
		if (!Boolean.TRUE.equals(c.getIsActive()))
			throw new IllegalStateException("카페24 계정의 활성 상태가 변경되었습니다. 다시 조회하세요.");
		return new com.sbshop.agent.core.domain.market.marketplus.MarketPlusSearchScope(c.getClientId(), gmarketAccount,
			auctionAccount);
	}

	private static Summary summarize(List<MarketPlusTransmission> rows) {
		for (var failure : rows) {
			if (!"FAILURE".equals(failure.getOutcome()))
				continue;
			boolean conflict = rows.stream().anyMatch(e -> "SUCCESS".equals(e.getOutcome())
				&& e.getTransferType().equals(failure.getTransferType())
				&& e.getCompletedAt().equals(failure.getCompletedAt()));
			if (conflict)
				return new Summary("CONFLICT", "MARKETPLUS_CONFLICTING_RESULTS",
					"같은 분의 같은 전송 종류에 성공·실패가 함께 있습니다. 최종 순서를 확인해야 합니다. " + failure.getDetail(),
					failure.getCompletedAt(), failure.getCapturedAt());
		}
		var selected = rows.stream().filter(e -> "FAILURE".equals(e.getOutcome())).findFirst().orElse(rows.getFirst());
		return new Summary(selected.getOutcome(), selected.getReasonCode(), selected.getDetail(),
			selected.getCompletedAt(), selected.getCapturedAt());
	}

	private boolean matches(MarketRegistration r, Row row) {
		return matches(r, row.market(), row.cafe24ProductNo(), row.cafe24ProductCode(), row.externalId());
	}

	/** Match the same scalar top-level IDs as the SQL search, never boolean/container/archive values. */
	private boolean matches(MarketRegistration r, MarketType market, String productNo, String productCode,
		String externalId) {
		if (r.getMarketType() != MarketType.CAFE24)
			return false;
		try {
			var ids = mapper.readTree(r.getMarketIdentifiers());
			String marketKey = market == MarketType.GMARKET ? MarketRegistration.GMARKET_IDENTIFIER_KEY
				: MarketRegistration.AUCTION_IDENTIFIER_KEY;
			return productNo.equals(scalarIdentifier(ids, "product_no"))
				&& productCode.equals(scalarIdentifier(ids, "product_code"))
				&& externalId.equals(scalarIdentifier(ids, marketKey));
		} catch (Exception ignored) {
			return false;
		}
	}

	private static String scalarIdentifier(com.fasterxml.jackson.databind.JsonNode document, String key) {
		var value = document.path(key);
		return value.isTextual() || value.isNumber() ? value.asText() : null;
	}

	private static String reason(Row row) {
		if ("SUCCESS".equals(row.outcome()))
			return "TRANSFERRED_UNVERIFIED";
		if (row.detail().contains("'진열/판매'상태일 때만 판매중지를 해제"))
			return "SOURCE_STATE_REVIEW_REQUIRED";
		return "TRANSFER_FAILED_REVIEW_REQUIRED";
	}

	private String fingerprint(Batch batch, Row row, Long registrationId) {
		try {
			// No tab-relative row ordinal or capture time: the UI has no stable remote job ID.
			String value = mapper
				.writeValueAsString(List.of(registrationId, batch.mallId(), batch.shopNo(), row.market(),
					row.sellerAccount(), row.cafe24ProductNo(), row.cafe24ProductCode(), row.externalId(),
					row.transferType(),
					row.outcome(), row.detail(), row.requestedAt().toString(), row.completedAt().toString()));
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception e) {
			throw new IllegalStateException("전송 이력 식별값 생성 실패", e);
		}
	}

	private static Item rejected(int number, Row row, String detail) {
		return new Item(number, row.externalId(), null, "REJECTED", detail);
	}

	private static int count(List<Item> items, String result) {
		return (int)items.stream().filter(i -> i.result().equals(result)).count();
	}

	private static void bounded(String value, int max) {
		if (value == null || value.isBlank() || value.length() > max)
			throw new IllegalArgumentException("필수 값 또는 길이를 확인하세요.");
	}
}
