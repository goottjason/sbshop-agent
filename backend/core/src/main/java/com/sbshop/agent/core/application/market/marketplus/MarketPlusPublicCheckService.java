package com.sbshop.agent.core.application.market.marketplus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.sbshop.agent.core.domain.market.inspection.*;
import com.sbshop.agent.core.domain.market.marketplus.*;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.product.ProductRepository;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable, read-only public page requests. OBSERVED never confirms a marketplace write or listing state. */
@Service
@RequiredArgsConstructor
public class MarketPlusPublicCheckService {
	private static final String GLOBAL = "MARKETPLUS_PUBLIC_BROWSER";
	private static final Set<String> MARKETS = Set.of("GMARKET", "AUCTION");
	private final MarketPlusPublicCollectionRepository collections;
	private final MarketPlusPublicCheckRepository checks;
	private final MarketInspectionGateRepository gates;
	private final MarketPlusPublicObservationService observations;
	private final MarketPlusTransmissionService transmissions;
	private final ProductRepository products;
	private final MarketRegistrationRepository registrations;
	private final PlatformTransactionManager transactions;
	private final JdbcTemplate jdbc;
	private final ObjectMapper mapper;

	public record Request(String requestId,
		@JsonDeserialize(contentUsing = MarketPlusPublicObservationService.ExactLong.class)
		List<Long> productIds, List<String> markets) {
		public Request {
			try {
				if (!UUID.fromString(requestId).toString().equals(requestId))
					throw new IllegalArgumentException();
			} catch (Exception e) {
				throw new IllegalArgumentException("요청 UUID가 필요합니다.");
			}
			if (productIds == null || productIds.isEmpty() || productIds.size() > 50
				|| productIds.stream().anyMatch(v -> v == null || v <= 0)
				|| new HashSet<>(productIds).size() != productIds.size())
				throw new IllegalArgumentException("중복 없는 상품 1~50개를 선택하세요.");
			if (markets == null || markets.isEmpty() || markets.size() > 2 || markets.stream().anyMatch(Objects::isNull)
				|| !MARKETS.containsAll(markets) || new HashSet<>(markets).size() != markets.size())
				throw new IllegalArgumentException("G마켓·옥션 조회 대상을 선택하세요.");
			productIds = productIds.stream().sorted().toList();
			markets = markets.stream().sorted().toList();
		}
	}
	public record Item(Long id, Long productId, String sbCode, String market, String state, String reason, int attempts,
		Instant nextRunAt, Instant checkedAt, Long observationId, Map<String, String> values, List<String> events) {
	}
	public record Collection(String id, String requestId, Instant createdAt, List<Item> items) {
	}
	public record Claim(Long taskId, String leaseToken, MarketPlusPublicObservationService.Target target) {
	}
	public record ClaimResponse(Claim task, int nextPollSeconds) {
	}
	public record Report(String leaseToken, MarketPlusPublicObservationService.Request observation, String errorCode,
		@JsonDeserialize(using = ExactHttpStatus.class)
		Integer httpStatus,
		@JsonDeserialize(using = MarketPlusPublicObservationService.ExactLong.class)
		Long retryAfterSeconds) {
		public Report {
			if (leaseToken == null || !leaseToken.matches("[a-f0-9-]{36}")
				|| (observation == null) == (errorCode == null))
				throw new IllegalArgumentException("조회 임대 토큰과 관측 또는 실패 사유 하나가 필요합니다.");
			if (errorCode != null && !Set.of("PUBLIC_PAGE_UNVERIFIED", "PUBLIC_IDENTITY_UNVERIFIED",
				"PUBLIC_CONTEXT_INVALID", "BROWSER_UNAVAILABLE", "HTTP_ERROR").contains(errorCode))
				throw new IllegalArgumentException("지원하는 공개 조회 실패 코드가 필요합니다.");
			if (httpStatus != null && (httpStatus < 100 || httpStatus > 599) || retryAfterSeconds != null
				&& (httpStatus == null || httpStatus != 429 || retryAfterSeconds < 1 || retryAfterSeconds > 86400))
				throw new IllegalArgumentException("실제 HTTP 상태와 Retry-After 범위를 확인하세요.");
			if (observation != null && (httpStatus != null && httpStatus >= 400 || retryAfterSeconds != null))
				throw new IllegalArgumentException("실패 응답은 정상 관측으로 저장할 수 없습니다.");
		}
	}
	public static final class ExactHttpStatus extends com.fasterxml.jackson.databind.JsonDeserializer<Integer> {
		@Override
		public Integer deserialize(com.fasterxml.jackson.core.JsonParser parser,
			com.fasterxml.jackson.databind.DeserializationContext context) throws java.io.IOException {
			if (!parser.hasToken(com.fasterxml.jackson.core.JsonToken.VALUE_NUMBER_INT))
				throw com.fasterxml.jackson.databind.JsonMappingException.from(parser, "HTTP 상태는 JSON 정수여야 합니다.");
			return parser.getIntValue();
		}
	}

	public Collection create(Request request, String actor) {
		actor(actor);
		ensureGates();
		return tx().execute(s -> {
			gates.lock(GLOBAL).orElseThrow(); // serializes request idempotency across API instances
			String hash = hash(request.productIds(), request.markets());
			var existing = collections.findByActorAndRequestId(actor, request.requestId());
			if (existing.isPresent()) {
				if (!existing.get().getRequestHash().equals(hash))
					throw new IllegalArgumentException("같은 요청 ID의 상품·마켓 목록을 변경할 수 없습니다.");
				return view(existing.get());
			}
			Instant now = now();
			var collection = collections.saveAndFlush(
				new MarketPlusPublicCollection(UUID.randomUUID().toString(), request.requestId(), actor, hash, now));
			for (Long id : request.productIds()) {
				List<MarketPlusPublicObservationService.Target> contexts = List.of();
				String mall = null, error = null;
				var product = products.findById(id).filter(p -> !p.isDeleted());
				try {
					if (product.isPresent())
						contexts = observations.context(id);
					else
						error = "상품을 찾을 수 없습니다.";
					mall = transmissions.requireSearchScope().mallId();
				} catch (RuntimeException e) {
					error = "상품 또는 현재 마켓플러스 계정·연결 설정을 확인할 수 없습니다.";
				}
				for (String market : request.markets()) {
					var matching = contexts.stream().filter(c -> market.equals(c.market())).toList();
					var target = matching.size() == 1 ? matching.getFirst() : null;
					String skip = error != null ? error : target == null ? "연결된 해당 마켓 상품이 없거나 연결이 모호합니다." : null;
					Long regRevision = target == null ? null
						: registrations.findById(target.registrationId()).map(r -> r.getRevision()).orElse(null);
					if (target != null && regRevision == null)
						skip = "현재 상품 연결을 확인할 수 없습니다.";
					checks.save(new MarketPlusPublicCheck(collection.getId(), id,
						product.map(p -> p.getSbCode()).orElse(null), market,
						target == null ? null : json(target), mall, regRevision, now, skip));
				}
			}
			checks.flush();
			return view(collection);
		});
	}

	public Collection get(String id, String actor) {
		actor(actor);
		return tx().execute(s -> view(owned(id, actor)));
	}

	public List<Collection> recent(String actor) {
		actor(actor);
		return tx()
			.execute(s -> collections.findTop20ByActorOrderByCreatedAtDesc(actor).stream().map(this::view).toList());
	}

	public ClaimResponse claim(String worker) {
		actor(worker);
		ensureGates();
		return tx().execute(s -> {
			Instant now = now();
			var global = gates.lock(GLOBAL).orElseThrow();
			if (!global.available(now))
				return new ClaimResponse(null, 5);
			var eligible = MARKETS.stream().sorted().filter(m -> gates.lock(marketGate(m)).orElseThrow().available(now))
				.toList();
			if (eligible.isEmpty())
				return new ClaimResponse(null, 5);
			for (var candidate : checks.due(now, eligible, PageRequest.of(0, 100))) {
				var task = checks.lock(candidate.getId()).orElseThrow();
				if (task.getAttempts() >= 3) {
					task.finish("FAILED_UNVERIFIED", "세 차례 조회를 마치지 못했습니다. 결과를 확인할 수 없어 종료했습니다.", now, null, null, null);
					continue;
				}
				var gate = gates.lock(marketGate(task.getMarket())).orElseThrow();
				if (!gate.available(now))
					continue;
				if (!current(task)) {
					task.finish("STALE", "요청 후 상품 버전·판매 계정·마켓 연결이 변경되었습니다. 새 조회를 요청하세요.", now, null, null, null);
					continue;
				}
				if ("RUNNING".equals(task.getState()))
					task.finish("RETRY_WAIT", "이전 조회의 응답을 받지 못해 임대 만료 후 다시 조회합니다.", now, now, null, null);
				String token = UUID.randomUUID().toString();
				var leases = leases(task);
				leases.put(token, worker);
				task.claim(token, worker, now, json(leases));
				global.claim(token, task.getLeaseUntil());
				gate.claim(token, task.getLeaseUntil());
				return new ClaimResponse(new Claim(task.getId(), token, target(task)), 5);
			}
			return new ClaimResponse(null, 5);
		});
	}

	public Item report(Long id, Report report, String worker) {
		actor(worker);
		ensureGates();
		return tx().execute(s -> {
			Instant now = now();
			var global = gates.lock(GLOBAL).orElseThrow();
			var task = checks.lock(id).orElseThrow(() -> new IllegalArgumentException("조회 작업을 찾을 수 없습니다."));
			boolean owns = task.owns(report.leaseToken(), worker);
			boolean issued = worker.equals(leases(task).get(report.leaseToken()));
			boolean rateLimited = Objects.equals(report.httpStatus(), 429);
			if (!owns && !(issued && rateLimited))
				throw new IllegalArgumentException("현재 조회 임대와 작업자가 일치하지 않습니다.");
			var gate = gates.lock(marketGate(task.getMarket())).orElseThrow();
			Instant next = now.plusSeconds(
				rateLimited ? Math.max(60, report.retryAfterSeconds() == null ? 60 : report.retryAfterSeconds()) : 5);
			// Only an authenticated worker/token issued for this exact task may supply late rate-limit evidence.
			// A previous lease can defer this account gate but cannot alter the newer task or browser lease.
			if (rateLimited)
				gate.deferUntil(next);
			if (!owns || !"RUNNING".equals(task.getState()))
				return item(task); // response loss: exact task completion recovery
			products.findForEdit(task.getProductId());
			var expected = target(task);
			registrations.findForConnectionUpdate(expected.registrationId());
			if (!current(task))
				task.finish("STALE", "요청 후 상품 버전·판매 계정·마켓 연결이 변경되었습니다. 새 조회를 요청하세요.", now, null, null, null);
			else if (report.observation() != null) {
				var value = report.observation();
				var capture = value.observation();
				if (!expected.registrationId().equals(value.registrationId())
					|| expected.expectedRevision() != value.expectedRevision()
					|| !expected.cafe24ProductNo().equals(value.cafe24ProductNo())
					|| !expected.cafe24ProductCode().equals(value.cafe24ProductCode())
					|| !expected.market().equals(capture.market().name())
					|| !expected.externalId().equals(capture.externalId())
					|| !expected.sellerAccount().equals(capture.sellerAccount()))
					throw new IllegalArgumentException("관측값이 요청한 상품·계정·연결과 다릅니다.");
				if (capture.capturedAt().isBefore(now.minusSeconds(300))
					|| capture.capturedAt().isAfter(now.plusSeconds(60)))
					task.finish("FAILED_UNVERIFIED", "관측 시각이 유효 범위를 벗어났습니다. 새 조회를 요청하세요.", now, null, null, null);
				else {
					var collection = collections.findById(task.getCollectionId()).orElseThrow();
					var result = observations.ingest(task.getProductId(), value, collection.getActor());
					task.finish("OBSERVED", "공개 표시값을 확인했습니다. 목표값 일치·전송 완료·판매 상태 확정은 별도입니다.", capture.capturedAt(), null,
						result.observationId(), json(capture.values()));
				}
			} else {
				boolean retry = task.getAttempts() < 3;
				String reason = rateLimited ? "실제 HTTP 429 응답: 마켓 공통 대기 후 재조회합니다."
					: report.httpStatus() != null ? "공개 페이지 HTTP " + report.httpStatus() + ": 가격을 확인할 수 없습니다."
						: "BROWSER_UNAVAILABLE".equals(report.errorCode()) ? "브라우저에 연결하지 못해 가격을 확인할 수 없습니다."
							: "공개 상품번호·판매 계정·표시가격을 확인할 수 없습니다. 삭제로 판단하지 않습니다.";
				if (!retry)
					reason += " 세 차례 확인을 마쳐 자동 조회를 종료했습니다.";
				task.finish(retry ? "RETRY_WAIT" : "FAILED_UNVERIFIED", reason, now,
					retry ? (rateLimited ? next : now.plusSeconds(30L * task.getAttempts())) : null, null, null);
			}
			if (report.leaseToken().equals(global.getLeaseToken()))
				global.release(now.plusSeconds(5));
			if (report.leaseToken().equals(gate.getLeaseToken()))
				gate.release(next);
			return item(task);
		});
	}

	private boolean current(MarketPlusPublicCheck task) {
		try {
			return Objects.equals(task.getMallId(), transmissions.requireSearchScope().mallId())
				&& observations.context(task.getProductId()).contains(target(task))
				&& registrations.findById(target(task).registrationId())
					.map(r -> Objects.equals(r.getRevision(), task.getRegistrationRevision())).orElse(false);
		} catch (RuntimeException e) {
			return false;
		}
	}

	private Map<String, String> leases(MarketPlusPublicCheck task) {
		try {
			return mapper.readValue(task.getLeaseHistory(),
				new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, String>>() {});
		} catch (Exception e) {
			throw new IllegalStateException("조회 임대 이력을 읽지 못했습니다.", e);
		}
	}

	private MarketPlusPublicObservationService.Target target(MarketPlusPublicCheck task) {
		try {
			return mapper.readValue(task.getTargetJson(), MarketPlusPublicObservationService.Target.class);
		} catch (Exception e) {
			throw new IllegalStateException("조회 대상 기록을 읽지 못했습니다.", e);
		}
	}

	private MarketPlusPublicCollection owned(String id, String actor) {
		return collections.findById(id).filter(c -> actor.equals(c.getActor()))
			.orElseThrow(() -> new IllegalArgumentException("조회 요청을 찾을 수 없습니다."));
	}

	private Collection view(MarketPlusPublicCollection collection) {
		return new Collection(collection.getId(), collection.getRequestId(), collection.getCreatedAt(),
			checks.findByCollectionIdOrderById(collection.getId()).stream().map(this::item).toList());
	}

	private Item item(MarketPlusPublicCheck t) {
		Map<String, String> values = Map.of();
		try {
			if (t.getObservedValues() != null)
				values = mapper.readValue(t.getObservedValues(),
					new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {});
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
		return new Item(t.getId(), t.getProductId(), t.getSbCode(), t.getMarket(), t.getState(), t.getReason(),
			t.getAttempts(), t.getNextRunAt(), t.getCheckedAt(), t.getObservationId(), values,
			t.getEvents().lines().toList());
	}

	private void actor(String actor) {
		if (actor == null || actor.isBlank() || actor.length() > 200)
			throw new IllegalArgumentException("인증된 작업자가 필요합니다.");
	}

	private String json(Object value) {
		try {
			return mapper.writeValueAsString(value);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private String hash(Object... value) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(value)));
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private static String marketGate(String market) {
		return market + "_PUBLIC_READ";
	}

	private void ensureGates() {
		for (String id : List.of(GLOBAL, marketGate("GMARKET"), marketGate("AUCTION"))) {
			if (gates.existsById(id))
				continue;
			try {
				tx().executeWithoutResult(s -> gates.saveAndFlush(new MarketInspectionGate(id, now())));
			} catch (DataIntegrityViolationException race) {
				if (!gates.existsById(id))
					throw race;
			}
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
}
