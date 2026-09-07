package com.sbshop.agent.core.application.product.edit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sbshop.agent.core.domain.common.exception.ResourceNotFoundException;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.ProductRepository;
import com.sbshop.agent.core.domain.product.dto.ProductUpdateCommand;
import com.sbshop.agent.core.domain.product.edit.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductEditService {
	private final ProductRepository products;
	private final MarketRegistrationRepository registrations;
	private final ProductEditReviewRepository reviews;
	private final ProductChangeHistoryRepository histories;
	private final ProductChangeTargetRepository targets;
	private final ProductEditPlanner planner;
	private final ProductEditPolicy policy;
	private final ObjectMapper mapper;
	private final PlatformTransactionManager transactions;

	public record Review(String reviewId, Instant expiresAt, List<ProductEditPlanner.Plan> items) {
	}
	public record Workspace(Long productId, long revision, List<ProductEditPolicy.Rule> fields,
		List<ProductEditPolicy.Connection> connections) {
	}
	public record CommitItem(Long productId, String sbCode, String state, Long historyId, String reason) {
	}
	public record CommitResult(String reviewId, List<CommitItem> items) {
	}
	public record Target(Long id, String market, String state) {
	}
	public record History(Long id, long beforeRevision, long afterRevision, String actor, Instant createdAt,
		List<ProductEditPlanner.Change> changes, List<Target> targets) {
	}

	@Transactional(readOnly = true)
	public Workspace workspace(Long productId) {
		Product product = product(productId);
		var links = registrations.findByProductId(productId);
		var fields = ProductEditValues.read(product, mapper).properties().stream()
			.map(e -> policy.rule(e.getKey(), links)).toList();
		return new Workspace(productId, product.getRevision(), fields, policy.connections(links));
	}

	@Transactional
	public Review previewNumeric(ProductNumericPreviewUseCase.Request request, String actor) {
		var byId = products.findAllById(request.productIds()).stream().filter(p -> !p.isDeleted())
			.collect(Collectors.toMap(Product::getId, p -> p));
		var links = registrations.findByProductIdIn(request.productIds()).stream()
			.collect(Collectors.groupingBy(MarketRegistration::getProductId));
		var plans = request.productIds().stream().map(id -> byId.containsKey(id)
			? planner.numeric(byId.get(id), request, links.getOrDefault(id, List.of())) : planner.missing(id)).toList();
		return store(plans, actor);
	}

	@Transactional
	public Review previewSingle(Long productId, long expectedRevision, ObjectNode values, String actor) {
		Product product = product(productId);
		if (product.getRevision() != expectedRevision)
			throw new ProductEditConflictException("상품 정보가 변경되었습니다. 새로 조회한 뒤 검토하세요.");
		return store(List.of(planner.plan(product, values, registrations.findByProductId(productId))), actor);
	}

	@Transactional
	public Review previewValues(ProductBulkValuesRequest request, String actor) {
		requireActor(actor);
		var byId = products.findAllById(request.productIds()).stream().filter(p -> !p.isDeleted())
			.collect(Collectors.toMap(Product::getId, p -> p));
		var links = registrations.findByProductIdIn(request.productIds()).stream()
			.collect(Collectors.groupingBy(MarketRegistration::getProductId));
		var plans = new ArrayList<ProductEditPlanner.Plan>();
		long payloadLength = 2;
		for (Long id : request.productIds()) {
			var plan = byId.containsKey(id)
				? planner.plan(byId.get(id), request.values(), links.getOrDefault(id, List.of())) : planner.missing(id);
			payloadLength += json(plan).length() + 1;
			if (payloadLength > 5_000_000)
				throw new IllegalArgumentException("검토 내용이 너무 큽니다. 상품 선택 범위를 줄이세요.");
			plans.add(plan);
		}
		return store(plans, actor);
	}

	private Review store(List<ProductEditPlanner.Plan> plans, String actor) {
		requireActor(actor);
		String payload = json(plans);
		if (payload.length() > 5_000_000)
			throw new IllegalArgumentException("검토 내용이 너무 큽니다. 상품 선택 범위를 줄이세요.");
		var review = reviews.save(new ProductEditReview(UUID.randomUUID().toString(), actor, Instant.now(), payload));
		return new Review(review.getId(), review.getExpiresAt(), plans);
	}

	public CommitResult commit(String reviewId, String actor) {
		requireActor(actor);
		ProductEditReview review = reviews.findById(reviewId)
			.orElseThrow(() -> new ProductEditConflictException("검토 기록이 없습니다. 다시 미리보기 하세요."));
		if (!review.getActor().equals(actor))
			throw new ProductEditConflictException("다른 사용자의 검토 기록으로 저장할 수 없습니다.");
		var plans = readPlans(review.getPayload());
		var result = new ArrayList<CommitItem>();
		TransactionTemplate tx = new TransactionTemplate(transactions);
		tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		for (var plan : plans) {
			if (plan.state() != ProductEditPlanner.State.READY) {
				result.add(new CommitItem(plan.productId(), plan.sbCode(), plan.state().name(), null,
					String.join(" / ", plan.reasons())));
				continue;
			}
			try {
				result.add(tx.execute(status -> commitOne(review, plan, actor)));
			} catch (ProductEditConflictException | org.springframework.dao.OptimisticLockingFailureException e) {
				result.add(new CommitItem(plan.productId(), plan.sbCode(), "CONFLICT", null,
					"상품·마켓 연결·가격 정책이 변경되었거나 검토가 만료됐습니다. 새로 검토하세요."));
			} catch (Exception e) {
				log.error("상품 변경 저장 실패: review={}, product={}", reviewId, plan.productId(), e);
				result.add(new CommitItem(plan.productId(), plan.sbCode(), "FAILED", null,
					"저장에 실패했습니다. 같은 검토 기록으로 재시도할 수 있습니다."));
			}
		}
		return new CommitResult(reviewId, List.copyOf(result));
	}

	private CommitItem commitOne(ProductEditReview review, ProductEditPlanner.Plan plan, String actor) {
		Product product = products.findForEdit(plan.productId())
			.orElseThrow(() -> new ProductEditConflictException("상품 없음"));
		var saved = histories.findByReviewIdAndProductId(review.getId(), product.getId());
		if (saved.isPresent())
			return success(plan, saved.get().getId(), "이미 저장된 변경입니다. 중복 적용하지 않았습니다.");
		if (product.isDeleted() || Instant.now().isAfter(review.getExpiresAt())
			|| product.getRevision() != plan.revision())
			throw new ProductEditConflictException("상품 버전 또는 검토 만료");
		var links = registrations.findByProductId(product.getId());
		if (!planner.fingerprint(links).equals(plan.connectionFingerprint()))
			throw new ProductEditConflictException("연결 상태 변경");
		var current = planner.plan(product, mapper.valueToTree(plan.command()), links);
		if (current.state() != ProductEditPlanner.State.READY
			|| !comparable(current.changes()).equals(comparable(plan.changes()))
			|| !current.prices().equals(plan.prices()))
			throw new ProductEditConflictException("정책 또는 파생값 변경");
		var history = persist(review.getId(), actor, product, plan);
		return success(plan, history.getId(), hasMarketChanges(plan) ? "DB 저장 완료 · 마켓 미반영 대상으로 기록됨" : "DB 저장 완료");
	}

	/** The content workflow stores its own immutable review and joins history/timestamps in the caller's transaction. */
	@Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
	public CommitItem commitReviewedContent(String reviewId, String actor, Instant expiresAt,
		ProductEditPlanner.Plan plan) {
		requireActor(actor);
		if (plan.state() != ProductEditPlanner.State.READY)
			return new CommitItem(plan.productId(), plan.sbCode(), plan.state().name(), null,
				String.join(" / ", plan.reasons()));
		return commitOne(new ProductEditReview(reviewId, actor, expiresAt.minusSeconds(1800), ""), plan, actor);
	}

	private boolean hasMarketChanges(ProductEditPlanner.Plan plan) {
		return !plan.connections().isEmpty() && plan.changes().stream().anyMatch(c -> !c.field().equals("memo"));
	}

	private ProductChangeHistory persist(String reviewId, String actor, Product product, ProductEditPlanner.Plan plan) {
		long beforeRevision = product.getRevision();
		product.update(plan.command());
		products.flush();
		var history = histories.save(new ProductChangeHistory(reviewId, product.getId(), beforeRevision,
			product.getRevision(), actor, json(plan.changes()), json(plan)));
		if (hasMarketChanges(plan)) {
			var snapshots = targetSnapshots(plan);
			for (var connection : plan.connections()) {
				for (String snapshot : snapshots)
					targets.save(new ProductChangeTarget(history.getId(), product.getId(), connection.registrationId(),
						product.getRevision(), connection.market(), snapshot));
			}
		}
		return history;
	}

	/** Independent dispatchers need disjoint commands while the reviewed database edit remains atomic. */
	private List<String> targetSnapshots(ProductEditPlanner.Plan plan) {
		Set<String> fields = plan.changes().stream().map(ProductEditPlanner.Change::field)
			.filter(field -> !field.equals("memo")).collect(Collectors.toSet());
		boolean split = fields.contains("salesQuantity")
			&& fields.stream().anyMatch(ProductEditPolicy.PRICE_FIELDS::contains)
			&& fields.stream()
				.allMatch(field -> field.equals("salesQuantity") || ProductEditPolicy.PRICE_FIELDS.contains(field));
		if (!split)
			return List.of(json(plan));
		return List.of(targetSnapshot(plan, ProductEditPolicy.PRICE_FIELDS),
			targetSnapshot(plan, Set.of("salesQuantity")));
	}

	private String targetSnapshot(ProductEditPlanner.Plan plan, Set<String> fields) {
		ObjectNode snapshot = mapper.valueToTree(plan);
		var changes = plan.changes().stream().filter(change -> fields.contains(change.field())).toList();
		ObjectNode command = mapper.createObjectNode();
		for (var change : changes)
			command.set(change.field(), snapshot.path("command").get(change.field()));
		snapshot.set("changes", mapper.valueToTree(changes));
		snapshot.set("command", command);
		if (!fields.stream().anyMatch(ProductEditPolicy.PRICE_FIELDS::contains))
			snapshot.putArray("prices");
		return json(snapshot);
	}

	@Transactional(readOnly = true)
	public void requireWritable(Long productId, List<String> fields) {
		product(productId);
		var links = registrations.findByProductId(productId);
		for (String field : fields) {
			var rule = policy.rule(field, links);
			if (!rule.editable())
				throw new IllegalArgumentException(rule.reason());
		}
	}

	/** Legacy full-field and backfill routes use the same policy and atomic history. */
	@Transactional
	public void saveExisting(Long productId, ProductUpdateCommand command, Long expectedRevision, String actor) {
		Product product = products.findForEdit(productId)
			.orElseThrow(() -> new ResourceNotFoundException("상품을 찾을 수 없습니다: " + productId));
		if (product.isDeleted())
			throw new ResourceNotFoundException("폐기된 상품입니다: " + productId);
		if (expectedRevision != null && expectedRevision != product.getRevision())
			throw new ProductEditConflictException("상품이 변경되었습니다. 새로 조회하세요.");
		var plan = planner.plan(product, mapper.valueToTree(command), registrations.findByProductId(productId));
		if (plan.state() == ProductEditPlanner.State.EXCLUDED)
			throw new IllegalArgumentException(String.join(" / ", plan.reasons()));
		if (plan.state() == ProductEditPlanner.State.READY)
			persist(UUID.randomUUID().toString(), actor, product, plan);
	}

	@Transactional(readOnly = true)
	public List<History> history(Long productId) {
		product(productId);
		var rows = histories.findTop50ByProductIdOrderByIdDesc(productId);
		if (rows.isEmpty())
			return List.of();
		var byHistory = targets.findByHistoryIdIn(rows.stream().map(ProductChangeHistory::getId).toList()).stream()
			.collect(Collectors.groupingBy(ProductChangeTarget::getHistoryId));
		return rows.stream()
			.map(h -> new History(h.getId(), h.getBeforeRevision(), h.getAfterRevision(), h.getActor(),
				h.getCreatedAt(), readChanges(h.getChanges()), byHistory.getOrDefault(h.getId(), List.of()).stream()
					.map(t -> new Target(t.getId(), t.getMarket(), t.getState())).toList()))
			.toList();
	}

	private Product product(Long id) {
		return products.findById(id).filter(p -> !p.isDeleted())
			.orElseThrow(() -> new ResourceNotFoundException("상품을 찾을 수 없습니다: " + id));
	}

	private Map<String, List<String>> comparable(List<ProductEditPlanner.Change> changes) {
		return changes.stream()
			.collect(Collectors.toMap(ProductEditPlanner.Change::field, c -> Arrays.asList(c.before(), c.after())));
	}

	private CommitItem success(ProductEditPlanner.Plan p, Long historyId, String reason) {
		return new CommitItem(p.productId(), p.sbCode(), "SAVED", historyId, reason);
	}

	private String json(Object value) {
		try {
			return mapper.writeValueAsString(value);
		} catch (Exception e) {
			throw new IllegalStateException("검토 기록 직렬화 실패", e);
		}
	}

	private List<ProductEditPlanner.Plan> readPlans(String value) {
		try {
			return mapper.readValue(value, new TypeReference<>() {});
		} catch (Exception e) {
			throw new IllegalStateException("검토 기록 읽기 실패", e);
		}
	}

	private List<ProductEditPlanner.Change> readChanges(String value) {
		try {
			return mapper.readValue(value, new TypeReference<>() {});
		} catch (Exception e) {
			throw new IllegalStateException("변경 이력 읽기 실패", e);
		}
	}

	private static void requireActor(String actor) {
		if (actor == null || actor.isBlank() || actor.length() > 200)
			throw new IllegalArgumentException("인증된 작업자가 필요합니다.");
	}
}
