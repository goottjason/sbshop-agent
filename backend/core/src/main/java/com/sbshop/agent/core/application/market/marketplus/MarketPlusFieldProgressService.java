package com.sbshop.agent.core.application.market.marketplus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.product.edit.ProductEditPolicy;
import com.sbshop.agent.core.domain.common.exception.ResourceNotFoundException;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.client.MarketClientRouter;
import com.sbshop.agent.core.domain.market.marketplus.*;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.market.sync.*;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.ProductRepository;
import com.sbshop.agent.core.domain.product.edit.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only evidence stages. Temporal proximity is never a revision/field transmission receipt. */
@Service
@RequiredArgsConstructor
public class MarketPlusFieldProgressService {
	private final ProductRepository products;
	private final MarketRegistrationRepository registrations;
	private final MarketPlusProgressHistoryRepository histories;
	private final ProductChangeTargetRepository targets;
	private final MarketPriceTaskRepository prices;
	private final MarketStockTaskRepository stocks;
	private final MarketFieldTaskRepository fieldTasks;
	private final MarketPlusTransmissionService transmissions;
	private final MarketPlusPublicObservationService publicObservations;
	private final MarketClientRouter clients;
	private final ObjectMapper mapper;

	public record Stage(String code, String detail, Instant at, String expectedValue, String observedValue) {
	}
	public record Field(Long historyId, Long targetId, long revision, boolean currentRevision, Instant savedAt,
		String market, String field, String beforeValue, String savedValue, boolean shortened,
		Stage cafe24, Stage transmission, Stage finalMarket) {
	}
	public record Preparation(String market, String sellerAccount, String cafe24ProductNo, String cafe24ProductCode,
		String externalId, String connectionState, boolean automaticRetryAllowed, List<String> blockers,
		String historyUrl, String publicProductUrl) {
	}
	public record Workspace(Long productId, String sbCode, long currentRevision, Instant generatedAt,
		List<Field> fields, List<Preparation> preparations, List<MarketPlusPublicObservationService.Item> publicValues,
		List<String> notices, boolean truncated) {
	}

	@Transactional(readOnly = true)
	public Workspace progress(Long productId) {
		var product = products.findById(productId).filter(p -> !p.isDeleted())
			.orElseThrow(() -> new ResourceNotFoundException("상품을 찾을 수 없습니다: " + productId));
		var links = registrations.findByProductId(productId).stream()
			.filter(r -> r.getMarketType() == MarketType.CAFE24).toList();
		var readiness = transmissions.readiness();
		var scope = readiness.ready() ? transmissions.requireSearchScope() : null;
		var observations = readiness.ready() ? transmissions.history(productId)
			: List.<MarketPlusTransmissionService.HistoryItem>of();
		var publicValues = readiness.ready() ? publicObservations.history(productId)
			: List.<MarketPlusPublicObservationService.Item>of();
		var recent = histories.recent(productId, PageRequest.of(0, 10));
		var rows = recent.isEmpty() ? List.<ProductChangeTarget>of()
			: targets.findByHistoryIdIn(recent.stream().map(MarketPlusProgressHistoryRepository.Row::getId).toList());
		var priceTasks = prices
			.findAllById(
				rows.stream().map(ProductChangeTarget::getPriceTaskId).filter(Objects::nonNull).distinct().toList())
			.stream().collect(Collectors.toMap(MarketPriceTask::getId, t -> t));
		var stockTasks = stocks
			.findAllById(
				rows.stream().map(ProductChangeTarget::getStockTaskId).filter(Objects::nonNull).distinct().toList())
			.stream().collect(Collectors.toMap(MarketStockTask::getId, t -> t));
		var genericTasks = fieldTasks
			.findAllById(
				rows.stream().map(ProductChangeTarget::getFieldTaskId).filter(Objects::nonNull).distinct().toList())
			.stream().collect(Collectors.toMap(MarketFieldTask::getId, t -> t));
		String cafeAccount = account();
		var fields = new ArrayList<Field>();
		boolean truncated = false;
		for (var history : recent) {
			for (var target : rows.stream().filter(t -> history.getId().equals(t.getHistoryId())
				&& productId.equals(t.getProductId()) && Set.of("GMARKET", "AUCTION").contains(t.getMarket()))
				.toList()) {
				if (fields.size() >= 240) {
					truncated = true;
					break;
				}
				JsonNode snapshot = parse(target.getSnapshot());
				var reg = links.stream().filter(r -> r.getId().equals(target.getRegistrationId())).findFirst()
					.orElse(null);
				boolean current = product.getRevision() == history.getAfterRevision();
				boolean connected = target.getProductRevision() == history.getAfterRevision() && reg != null
					&& currentConnection(reg, target, snapshot);
				JsonNode changes = snapshot.path("changes");
				if (!changes.isArray() || changes.isEmpty()) {
					fields.add(new Field(history.getId(), target.getId(), history.getAfterRevision(), current,
						history.getCreatedAt(),
						target.getMarket(), "UNREADABLE", null, null, false,
						stage("EVIDENCE_ERROR", "저장된 변경 필드 기록을 읽지 못했습니다. 원본 이력을 확인하세요."),
						stage("UNVERIFIED", "변경 필드와 전송 이력을 연결할 수 없습니다."),
						stage("UNVERIFIED", "최종 마켓 값 확인 근거가 없습니다.")));
					continue;
				}
				for (var change : changes) {
					String key = change.path("field").asText();
					if (key.equals("memo"))
						continue;
					if (fields.size() >= 240) {
						truncated = true;
						break;
					}
					String before = nullable(change.get("before")), after = nullable(change.get("after"));
					Stage cafe = !connected ? stage("CONNECTION_CHANGED", "현재 카페24·마켓 연결과 저장 당시 식별자가 다르거나 연결이 해제되었습니다.")
						: cafeStage(history, target, key, reg, rows, priceTasks, stockTasks, genericTasks, cafeAccount);
					Stage transfer = !connected ? stage("CONNECTION_CHANGED", "과거 연결의 전송 이력을 현재 연결의 근거로 사용하지 않습니다.")
						: observedStage(history, target, observations);
					fields.add(new Field(history.getId(), target.getId(), history.getAfterRevision(), current,
						history.getCreatedAt(),
						target.getMarket(), key, shorten(before), shorten(after), longValue(before) || longValue(after),
						cafe, transfer, finalStage(history, target, key, connected, publicValues)));
				}
			}
		}
		var preparations = new ArrayList<Preparation>();
		for (var reg : links)
			for (MarketType market : List.of(MarketType.GMARKET, MarketType.AUCTION)) {
				String external = reg.identifier(market == MarketType.GMARKET
					? MarketRegistration.GMARKET_IDENTIFIER_KEY : MarketRegistration.AUCTION_IDENTIFIER_KEY);
				if (external == null)
					continue;
				var blockers = new ArrayList<>(readiness.reasons());
				if (reg.getConnectionState().detached() || reg.connectionStateFor(market).detached())
					blockers.add("연결이 해제된 상품입니다. 재전송 대상으로 선택할 수 없습니다.");
				if (reg.connectionWriteBlock() != null)
					blockers.add(reg.connectionWriteBlock());
				blockers.add("현재 상품·계정의 필드별 기본/별도 상속과 선택 재전송 범위를 확인해야 합니다.");
				blockers.add("재전송 버튼과 확인 화면의 대상 식별 및 실제 마켓 필드 재조회 경로가 아직 검증되지 않았습니다.");
				preparations.add(new Preparation(market.name(),
					scope == null ? null
						: market == MarketType.GMARKET ? scope.gmarketAccount() : scope.auctionAccount(),
					reg.identifier("product_no"), reg.identifier("product_code"), external,
					reg.connectionStateFor(market).name(), false,
					List.copyOf(new LinkedHashSet<>(blockers)), "https://mp.cafe24.com/mp/queue/productList",
					publicUrl(market, external)));
			}
		var notices = new ArrayList<>(readiness.reasons());
		notices.add("최근 DB 변경 이력 10건의 G마켓·옥션 전송 대상, 최대 240개 필드를 표시합니다. 수집하지 않은 마켓플러스 기간·페이지는 포함되지 않습니다.");
		notices.add("마켓플러스 이력은 분 단위 관측이며 변경 버전·필드·원격 작업 ID가 없습니다. 시간상 가까운 전송도 해당 변경의 성공으로 확정하지 않습니다.");
		notices.add("현재 버전과 다른 변경의 카페24 확인은 과거 증거입니다. 최종 마켓 확인과 구분합니다.");
		if (!Objects.equals(cafeAccount, account()))
			throw new IllegalStateException("조회 중 카페24 계정이 변경되었습니다. 다시 조회하세요.");
		return new Workspace(productId, product.getSbCode(), product.getRevision(), Instant.now(), List.copyOf(fields),
			List.copyOf(preparations), publicValues, List.copyOf(notices), truncated);
	}

	private Stage finalStage(MarketPlusProgressHistoryRepository.Row history, ProductChangeTarget target, String field,
		boolean connected, List<MarketPlusPublicObservationService.Item> observations) {
		if (!connected)
			return stage("CONNECTION_CHANGED", "과거 연결의 공개 표시값은 현재 연결의 근거로 사용하지 않습니다.");
		String key = ProductEditPolicy.PRICE_FIELDS.contains(field) ? "salePrice" : field;
		return observations.stream()
			.filter(item -> item.currentConnection() && item.registrationId().equals(target.getRegistrationId())
				&& item.market().equals(target.getMarket()) && item.productRevision() == history.getAfterRevision()
				&& !item.capturedAt().isBefore(history.getCreatedAt()) && item.values().containsKey(key))
			.max(Comparator.comparing(MarketPlusPublicObservationService.Item::capturedAt))
			.map(item -> new Stage("PUBLIC_VALUE_OBSERVED",
				"판매 계정·상품번호를 확인한 공개 표시값입니다. 현재 마켓플러스 필드 상속·최종 목표값을 확인하기 전에는 일치로 확정하지 않습니다.",
				item.capturedAt(), null, item.values().get(key)))
			.orElseGet(() -> stage("UNVERIFIED", "G마켓·옥션에서 판매 계정·상품·해당 필드를 직접 재조회한 근거가 없습니다."));
	}

	private Stage cafeStage(MarketPlusProgressHistoryRepository.Row history, ProductChangeTarget target, String field,
		MarketRegistration reg, List<ProductChangeTarget> all, Map<Long, MarketPriceTask> priceTasks,
		Map<Long, MarketStockTask> stockTasks, Map<Long, MarketFieldTask> genericTasks, String account) {
		var parentTargets = all.stream()
			.filter(t -> t.getHistoryId().equals(history.getId()) && t.getProductId().equals(target.getProductId())
				&& t.getRegistrationId().equals(target.getRegistrationId())
				&& t.getProductRevision() == history.getAfterRevision()
				&& t.getMarket().equals("CAFE24") && containsField(parse(t.getSnapshot()), field))
			.toList();
		if (parentTargets.size() != 1)
			return stage("UNVERIFIED", "동일 DB 변경·필드의 카페24 전송 대상 기록을 하나로 확인할 수 없습니다.");
		var parent = parentTargets.getFirst();
		if (ProductEditPolicy.PRICE_FIELDS.contains(field)) {
			var task = priceTasks.get(parent.getPriceTaskId());
			if (task == null)
				return pending(parent, "가격");
			if (!sameTask(target, history, reg, task.getProductId(), task.getRegistrationId(),
				task.getProductRevision(), task.getMarket(),
				task.getListingId(), task.getIdentifiers(), task.getAccountReference(), account))
				return stage("STALE_EVIDENCE", "카페24 가격 작업의 상품·버전·연결·계정이 이 변경과 일치하지 않습니다.");
			String expected = decimal(task.getExpectedPrice()), observed = decimal(task.getObservedPrice());
			boolean confirmed = task.getState().equals("CONFIRMED_PRICE") && expected != null
				&& expected.equals(observed) && task.getCheckedAt() != null;
			return new Stage(confirmed ? "CAFE24_CONFIRMED" : taskState(task.getState()),
				"가격 관련 DB 값으로 계산한 카페24 판매가: " + task.getDetail(), task.getCheckedAt(), expected, observed);
		}
		if (field.equals("salesQuantity")) {
			var task = stockTasks.get(parent.getStockTaskId());
			if (task == null)
				return pending(parent, "판매용 수량");
			if (!sameTask(target, history, reg, task.getProductId(), task.getRegistrationId(),
				task.getProductRevision(), task.getMarket(),
				task.getListingId(), task.getIdentifiers(), task.getAccountReference(), account))
				return stage("STALE_EVIDENCE", "카페24 수량 작업의 상품·버전·연결·계정이 이 변경과 일치하지 않습니다.");
			String expected = Objects.toString(task.getExpectedQuantity(), null),
				observed = Objects.toString(task.getObservedQuantity(), null);
			boolean confirmed = task.getState().equals("CONFIRMED_QUANTITY") && expected != null
				&& expected.equals(observed) && task.getCheckedAt() != null;
			return new Stage(confirmed ? "CAFE24_CONFIRMED" : taskState(task.getState()), task.getDetail(),
				task.getCheckedAt(), expected, observed);
		}
		var task = genericTasks.get(parent.getFieldTaskId());
		if (task == null)
			return stage("UNSUPPORTED_FIELD", "이 필드의 카페24 쓰기·재조회 결과를 연결할 수 있는 검증된 작업 기록이 없습니다.");
		if (!Objects.equals(parent.getId(), task.getChangeTargetId())
			|| !sameTask(target, history, reg, task.getProductId(), task.getRegistrationId(), task.getProductRevision(),
				task.getMarket(),
				task.getListingId(), task.getIdentifiers(), task.getAccountReference(), account))
			return stage("STALE_EVIDENCE", "카페24 필드 작업의 저장 대상·상품·버전·연결·계정이 이 변경과 일치하지 않습니다.");
		String expected = nullable(parse(task.getExpectedValues()).get(field)),
			observed = nullable(parse(task.getObservedValues()).get(field));
		boolean confirmed = task.getState().equals("CONFIRMED_FIELDS") && expected != null && expected.equals(observed)
			&& task.getCheckedAt() != null
			&& (!task.isRequiresApproval() || "APPROVED".equals(task.getObservedApproval()));
		return new Stage(confirmed ? "CAFE24_CONFIRMED" : taskState(task.getState()), task.getDetail(),
			task.getCheckedAt(), shorten(expected), shorten(observed));
	}

	private Stage observedStage(MarketPlusProgressHistoryRepository.Row history, ProductChangeTarget target,
		List<MarketPlusTransmissionService.HistoryItem> observations) {
		var candidates = observations.stream().filter(MarketPlusTransmissionService.HistoryItem::currentConnection)
			.map(MarketPlusTransmissionService.HistoryItem::observation)
			.filter(e -> e.getRegistrationId().equals(target.getRegistrationId())
				&& e.getProductId().equals(target.getProductId())
				&& e.getMarket().equals(target.getMarket()) && e.getTransferType().equals("상품수정")
				&& !e.getCompletedAt().isBefore(history.getCreatedAt().truncatedTo(ChronoUnit.MINUTES)))
			.sorted(Comparator.comparing(MarketPlusTransmission::getCompletedAt).reversed()).toList();
		if (candidates.isEmpty())
			return stage("NOT_OBSERVED", "이 변경 이후의 현재 연결 상품수정 이력이 수집되지 않았습니다. 전송하지 않았다는 뜻은 아닙니다.");
		var latest = candidates.getFirst();
		boolean conflict = candidates.stream().anyMatch(
			e -> e.getCompletedAt().equals(latest.getCompletedAt()) && !e.getOutcome().equals(latest.getOutcome()));
		String code = conflict ? "CONFLICT_OBSERVED"
			: latest.getOutcome().equals("FAILURE") ? "FAILURE_OBSERVED" : "SUCCESS_OBSERVED";
		return new Stage(code, latest.getDetail() + " · 관측된 전송 결과이며 이 DB 버전·필드와의 연결은 미확인입니다.", latest.getCompletedAt(),
			null, null);
	}

	private boolean currentConnection(MarketRegistration reg, ProductChangeTarget target, JsonNode snapshot) {
		if (!reg.getProductId().equals(target.getProductId()) || reg.getConnectionState().detached()
			|| reg.connectionStateFor(MarketType.valueOf(target.getMarket())).detached())
			return false;
		String cafe = reg.identifier("product_no"), external = reg.identifier(target.getMarket().equals("GMARKET")
			? MarketRegistration.GMARKET_IDENTIFIER_KEY : MarketRegistration.AUCTION_IDENTIFIER_KEY);
		return cafe != null && external != null && connection(snapshot, reg.getId(), "CAFE24", cafe)
			&& connection(snapshot, reg.getId(), target.getMarket(), external);
	}

	private boolean connection(JsonNode snapshot, Long id, String market, String external) {
		for (var connection : snapshot.path("connections"))
			if (connection.path("registrationId").asLong(-1) == id && market.equals(connection.path("market").asText())
				&& external.equals(connection.path("externalId").asText()))
				return true;
		return false;
	}

	private boolean sameTask(ProductChangeTarget target, MarketPlusProgressHistoryRepository.Row history,
		MarketRegistration reg,
		Long product, Long registration, long revision, String market, String listing, String identifiers,
		String taskAccount, String account) {
		return target.getProductId().equals(product) && target.getRegistrationId().equals(registration)
			&& revision == history.getAfterRevision() && "CAFE24".equals(market)
			&& Objects.equals(reg.identifier("product_no"), listing)
			&& Objects.equals(reg.getMarketIdentifiers(), identifiers) && account != null
			&& account.equals(taskAccount);
	}

	private Stage pending(ProductChangeTarget target, String kind) {
		return stage(target.getState().equals("PENDING_DISPATCH") ? "PENDING" : "REVIEW_REQUIRED",
			"카페24 " + kind + " 대상 상태: " + target.getState() + ". 쓰기·재조회 결과는 아직 확인되지 않았습니다.");
	}

	private static String taskState(String state) {
		return Set.of("CHECK", "VERIFY").contains(state) ? "PENDING" : "REVIEW_REQUIRED";
	}

	private static Stage stage(String code, String detail) {
		return new Stage(code, detail, null, null, null);
	}

	private static String nullable(JsonNode value) {
		return value == null || value.isNull() ? null : value.asText();
	}

	private static boolean longValue(String value) {
		return value != null && value.length() > 240;
	}

	private static String shorten(String value) {
		return longValue(value) ? value.substring(0, 240) + "…" : value;
	}

	private static String decimal(java.math.BigDecimal value) {
		return value == null ? null : value.stripTrailingZeros().toPlainString();
	}

	private boolean containsField(JsonNode snapshot, String field) {
		for (var change : snapshot.path("changes"))
			if (field.equals(change.path("field").asText()))
				return true;
		return false;
	}

	private JsonNode parse(String value) {
		try {
			var parsed = mapper.readTree(value);
			return parsed != null && parsed.isObject() ? parsed : mapper.createObjectNode();
		} catch (Exception e) {
			return mapper.createObjectNode();
		}
	}

	private String account() {
		try {
			return clients.hasClient(MarketType.CAFE24)
				? clients.getClient(MarketType.CAFE24).inspectionAccountReference() : null;
		} catch (Exception e) {
			return null;
		}
	}

	private String publicUrl(MarketType market, String id) {
		if (market == MarketType.GMARKET && id.matches("[1-9][0-9]{0,19}"))
			return "https://item.gmarket.co.kr/Item?goodscode=" + id;
		if (market == MarketType.AUCTION && id.matches("[A-Za-z0-9]{1,20}"))
			return "https://itempage3.auction.co.kr/DetailView.aspx?ItemNo=" + id;
		return null;
	}
}
