package com.sbshop.agent.core.application.product.source;

import com.sbshop.agent.core.application.product.content.ProductContentUrls;
import com.sbshop.agent.core.domain.product.content.ProductContentLaneRepository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.product.source.ProductSourceData.*;
import com.sbshop.agent.core.application.product.edit.*;
import com.sbshop.agent.core.domain.common.exception.ResourceNotFoundException;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.product.*;
import com.sbshop.agent.core.domain.product.source.*;
import com.sbshop.agent.core.domain.product.source.ProductSourceSnapshot.State;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductSourceService {
	private final ProductRepository products;
	private final MarketRegistrationRepository registrations;
	private final ProductSourceCollectionRepository collections;
	private final ProductSourceSnapshotRepository snapshots;
	private final ProductContentLaneRepository lanes;
	private final com.sbshop.agent.core.application.pricing.VendorPricePolicyService vendorPolicies;
	private final ProductSourceReviewRepository reviews;
	private final ProductEditPlanner planner;
	private final ProductEditPolicy policy;
	private final ProductEditService edits;
	private final com.sbshop.agent.core.domain.product.edit.ProductChangeHistoryRepository histories;
	private final ObjectMapper mapper;
	private final PlatformTransactionManager transactions;

	public record CollectionRequest(String requestId, List<Long> productIds) {
		public CollectionRequest {
			requireUuid(requestId);
			if (productIds == null || productIds.isEmpty() || productIds.size() > 50
				|| productIds.stream().anyMatch(id -> id == null || id < 1)
				|| new HashSet<>(productIds).size() != productIds.size())
				throw new IllegalArgumentException("중복 없이 1~50개 상품을 선택하세요.");
			productIds = List.copyOf(productIds);
		}
	}
	public record Selection(String snapshotId, List<Field> fields) {
		public Selection {
			requireUuid(snapshotId);
			if (fields == null || fields.isEmpty() || fields.size() > 2 || fields.stream().anyMatch(Objects::isNull)
				|| new HashSet<>(fields).size() != fields.size())
				throw new IllegalArgumentException("반영할 가격 또는 재고 상태 항목을 선택하세요.");
			fields = List.copyOf(fields);
		}
	}
	public record ReviewRequest(List<Selection> items) {
		public ReviewRequest {
			if (items == null || items.isEmpty() || items.size() > 50 || items.stream().anyMatch(Objects::isNull))
				throw new IllegalArgumentException("검토할 상품을 1~50개 선택하세요.");
			items = List.copyOf(items);
		}
	}
	public record FieldView(Field field, boolean available, boolean editable, String reason, Instant collectedAt,
		Instant appliedAt) {
	}
	public record Snapshot(String id, Long productId, String sbCode, long revision, String sourceUrl, String vendor,
		State state, String reason, Instant requestedAt, Instant collectedAt, Instant expiresAt, Instant appliedAt,
		Values current, Values proposed, List<FieldView> fields, List<String> notices) {
	}
	public record Collection(String id, Instant createdAt, List<Snapshot> items) {
	}
	public record Reviewed(String snapshotId, List<Field> fields, ProductEditPlanner.Plan plan) {
	}

	@Transactional
	public Collection collect(CollectionRequest request, String actor) {
		requireActor(actor);
		// One persisted lane serializes request idempotency and source calls across application instances.
		lanes.findLocked("PRICE_STOCK").orElseThrow(() -> new IllegalStateException("가격·재고 수집 DB 준비가 필요합니다."));
		String ids = json(request.productIds());
		var existing = collections.findByActorAndRequestId(actor, request.requestId());
		if (existing.isPresent()) {
			if (!existing.get().getProductIds().equals(ids))
				throw new ProductEditConflictException("같은 요청 ID에 다른 상품을 사용할 수 없습니다.");
			return collection(existing.get());
		}
		if (snapshots.countByStateIn(List.of(State.QUEUED, State.COLLECTING)) + request.productIds().size() > 300)
			throw new IllegalArgumentException("수집 대기 상품이 많습니다. 진행 중인 수집이 끝난 뒤 다시 요청하세요.");
		Instant now = Instant.now();
		var collection = collections.save(new ProductSourceCollection(UUID.randomUUID().toString(),
			request.requestId(), actor, now, ids));
		int capturedBytes = 0;
		for (Long id : request.productIds()) {
			Product product = products.findById(id).filter(p -> !p.isDeleted()).orElse(null);
			var source = product == null ? null : product.getSourcingInfo();
			var logistics = product == null ? null : product.getLogisticsInfo();
			var captured = new Captured(product == null ? new Values(null, null, null, null) : Values.from(product),
				logistics == null ? null : logistics.getWeight(),
				logistics == null ? null : logistics.getBundleQuantity(),
				product == null ? null : Shipping.from(vendorPolicies.find(product.getVendor()).orElse(null)));

			String capturedJson = boundedJson(captured);
			capturedBytes += capturedJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
			if (capturedBytes > 5_000_000)
				throw new IllegalArgumentException("기존 상세정보가 많아 요청이 5MB를 초과했습니다. 상품 선택 범위를 줄이세요.");
			var snapshot = new ProductSourceSnapshot(UUID.randomUUID().toString(), collection.getId(), id,
				product == null ? null : product.getSbCode(), product == null ? 0 : product.getRevision(),
				product == null ? null : planner.fingerprint(registrations.findByProductId(id)),
				source == null ? null : source.getSourceUrl(),
				source == null || source.getVendor() == null ? null : source.getVendor().name(), now, capturedJson);
			if (product == null)
				snapshot.fail(State.FAILED, "상품이 없거나 폐기되었습니다.");
			else if (source == null || !ProductContentUrls.supports(source.getVendor()))
				snapshot.fail(State.UNSUPPORTED, "이 소싱처의 가격·재고 관측 계약이 확인되지 않았습니다. 현재 IHB·VTB·FTN·COK·OCD를 지원합니다.");
			else {
				try {
					ProductContentUrls.source(source.getVendor(), source.getSourceUrl());
				} catch (IllegalArgumentException e) {
					snapshot.fail(State.FAILED, e.getMessage());
				}
			}
			snapshots.save(snapshot);
		}
		return collection(collection);
	}

	@Transactional(readOnly = true)
	public Collection collection(String id, String actor) {
		requireActor(actor);
		return collection(ownedCollection(id, actor));
	}

	@Transactional(readOnly = true)
	public List<Snapshot> history(Long productId, String actor) {
		requireActor(actor);
		return snapshots.history(productId, actor, PageRequest.of(0, 20)).stream().map(this::view).toList();
	}

	@Transactional
	public ProductEditService.Review review(ReviewRequest request, String actor) {
		requireActor(actor);
		Instant now = Instant.now();
		Instant expiresAt = now.plusSeconds(1800);
		List<Reviewed> rows = new ArrayList<>();
		Set<Long> productIds = new HashSet<>();
		for (Selection selection : request.items()) {
			var snapshot = ownedSnapshot(selection.snapshotId(), actor);
			if (!productIds.add(snapshot.getProductId()))
				throw new IllegalArgumentException("한 상품은 한 수집 결과로만 검토하세요.");
			Product product = products.findById(snapshot.getProductId()).filter(p -> !p.isDeleted()).orElse(null);
			var links = registrations.findByProductId(snapshot.getProductId());
			var proposal = proposal(snapshot);
			var reasons = new ArrayList<String>();
			if (product == null)
				reasons.add("상품이 없거나 폐기되었습니다.");
			else if (product.getRevision() != snapshot.getRevision()
				|| !Objects.equals(product.getSourcingUrl(), snapshot.getSourceUrl())
				|| !Objects.equals(product.getVendor() == null ? null : product.getVendor().name(),
					snapshot.getVendor())
				|| !planner.fingerprint(links).equals(snapshot.getConnectionFingerprint()))
				reasons.add("상품 또는 마켓 연결이 변경되었습니다. 새로 수집하세요.");
			if (snapshot.getExpiresAt() == null || !now.isBefore(snapshot.getExpiresAt()))
				reasons.add("수집 결과가 없거나 수집 후 24시간이 지나 만료됐습니다. 새로 수집하세요.");
			if (selection.fields().contains(Field.PRICE) && (proposal == null || !proposal.priceAvailable()))
				reasons.add("가격 수집·배송비 계산이 완료되지 않았습니다.");
			if (selection.fields().contains(Field.STOCK) && (proposal == null || !proposal.stockAvailable()))
				reasons.add("재고 상태 수집이 완료되지 않았습니다.");
			ProductEditPlanner.Plan plan;
			if (!reasons.isEmpty()) {
				plan = new ProductEditPlanner.Plan(snapshot.getProductId(), snapshot.getSbCode(),
					snapshot.getRevision(),
					ProductEditPlanner.State.EXCLUDED, snapshot.getConnectionFingerprint(), null, List.of(),
					policy.connections(links), List.of(), reasons, List.of());
			} else {
				var values = mapper.createObjectNode();
				if (selection.fields().contains(Field.PRICE)) {
					values.put("costPrice", proposal.values().costPrice());
					if (proposal.values().exchangeRate() != null)
						values.put("exchangeRate", proposal.values().exchangeRate());
					if (!Objects.equals(read(snapshot.getCaptured(), Captured.class).shipping(),
						Shipping.from(vendorPolicies.find(product.getVendor()).orElse(null))))
						reasons.add("수집 이후 소싱 배송비 정책이 변경되었습니다. 새로 수집하세요.");
				}
				if (selection.fields().contains(Field.STOCK)) {
					values.put("stockStatus", proposal.values().stockStatus().name());
					if (proposal.values().stock() != null)
						values.put("stock", proposal.values().stock());
				}
				plan = reasons.isEmpty() ? planner.planSourceObservation(product, values, links)
					: new ProductEditPlanner.Plan(snapshot.getProductId(), snapshot.getSbCode(), snapshot.getRevision(),
						ProductEditPlanner.State.EXCLUDED, snapshot.getConnectionFingerprint(), null, List.of(),
						policy.connections(links), List.of(), reasons, List.of());

				expiresAt = expiresAt.isBefore(snapshot.getExpiresAt()) ? expiresAt : snapshot.getExpiresAt();
			}
			rows.add(new Reviewed(snapshot.getId(), selection.fields(), plan));
		}
		var review = reviews.save(new ProductSourceReview(UUID.randomUUID().toString(), actor, now, expiresAt,
			boundedJson(rows)));
		return new ProductEditService.Review(review.getId(), review.getExpiresAt(),
			rows.stream().map(Reviewed::plan).toList());
	}

	public ProductEditService.CommitResult commit(String reviewId, String actor) {
		requireUuid(reviewId);
		requireActor(actor);
		var review = reviews.findById(reviewId)
			.orElseThrow(() -> new ProductEditConflictException("가격·재고 검토 기록이 없습니다."));
		if (!review.getActor().equals(actor))
			throw new ProductEditConflictException("다른 사용자의 검토 기록으로 저장할 수 없습니다.");
		List<Reviewed> rows = read(review.getPayload(), new TypeReference<>() {});
		var tx = new TransactionTemplate(transactions);
		tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		List<ProductEditService.CommitItem> result = new ArrayList<>();
		for (Reviewed row : rows) {
			try {
				result.add(tx.execute(status -> {
					var snapshot = snapshots.findLocked(row.snapshotId())
						.orElseThrow(() -> new ProductEditConflictException("수집 결과가 없습니다."));
					if (histories.findByReviewIdAndProductId(reviewId, row.plan().productId()).isPresent())
						return edits.commitReviewedSource(review.getId(), actor, review.getExpiresAt(),
							snapshot.getCollectedAt(), row.fields().contains(Field.STOCK), row.plan());
					Product product = products.findForEdit(snapshot.getProductId())
						.orElseThrow(() -> new ProductEditConflictException("상품 없음"));
					if (!Objects.equals(product.getSourcingUrl(), snapshot.getSourceUrl())
						|| !Objects.equals(product.getVendor() == null ? null : product.getVendor().name(),
							snapshot.getVendor()))
						throw new ProductEditConflictException("소싱 URL 또는 소싱처가 변경되었습니다.");
					if (row.fields().contains(Field.PRICE)
						&& !Objects.equals(read(snapshot.getCaptured(), Captured.class).shipping(),
							Shipping.from(vendorPolicies.find(product.getVendor()).orElse(null))))
						throw new ProductEditConflictException("소싱 배송비 정책이 변경되었습니다.");
					var item = edits.commitReviewedSource(review.getId(), actor, review.getExpiresAt(),
						snapshot.getCollectedAt(), row.fields().contains(Field.STOCK), row.plan());
					if ("SAVED".equals(item.state()))
						snapshot.applied(row.fields().contains(Field.PRICE),
							row.fields().contains(Field.STOCK), Instant.now());
					return item;
				}));
			} catch (ProductEditConflictException | org.springframework.dao.OptimisticLockingFailureException e) {
				result.add(
					new ProductEditService.CommitItem(row.plan().productId(), row.plan().sbCode(), "CONFLICT", null,
						"상품·마켓 연결·편집 정책이 변경되었거나 검토가 만료됐습니다. 새로 검토하세요."));
			} catch (Exception e) {
				log.error("가격·재고 검토 저장 실패 review={}, product={}", reviewId, row.plan().productId(), e);
				result
					.add(new ProductEditService.CommitItem(row.plan().productId(), row.plan().sbCode(), "FAILED", null,
						"저장에 실패했습니다. 같은 검토 기록으로 재시도할 수 있습니다."));
			}
		}
		return new ProductEditService.CommitResult(reviewId, result);
	}

	private Collection collection(ProductSourceCollection collection) {
		return new Collection(collection.getId(), collection.getCreatedAt(),
			snapshots.findByCollectionIdOrderByRequestedAtAscIdAsc(collection.getId()).stream().map(this::view)
				.toList());
	}

	private ProductSourceCollection ownedCollection(String id, String actor) {
		return collections.findById(id).filter(c -> c.getActor().equals(actor))
			.orElseThrow(() -> new ResourceNotFoundException("수집 요청을 찾을 수 없습니다."));
	}

	private ProductSourceSnapshot ownedSnapshot(String id, String actor) {
		var snapshot = snapshots.findById(id).orElseThrow(() -> new ResourceNotFoundException("수집 결과를 찾을 수 없습니다."));
		ownedCollection(snapshot.getCollectionId(), actor);
		return snapshot;
	}

	private Snapshot view(ProductSourceSnapshot snapshot) {
		var captured = read(snapshot.getCaptured(), Captured.class);
		var proposal = proposal(snapshot);
		var product = products.findById(snapshot.getProductId()).filter(p -> !p.isDeleted()).orElse(null);
		var links = registrations.findByProductId(snapshot.getProductId());
		String stale = product == null ? "상품이 없거나 폐기되었습니다."
			: product.getRevision() != snapshot.getRevision()
				|| !Objects.equals(product.getSourcingUrl(), snapshot.getSourceUrl())
				|| !Objects.equals(product.getVendor() == null ? null : product.getVendor().name(),
					snapshot.getVendor())
				|| !planner.fingerprint(links).equals(snapshot.getConnectionFingerprint())
					? "수집 이후 상품 또는 마켓 연결이 변경되었습니다. 새로 수집하세요."
					: snapshot.getExpiresAt() != null && !Instant.now().isBefore(snapshot.getExpiresAt())
						? "수집 결과가 만료됐습니다. 새로 수집하세요." : null;
		List<FieldView> fields = List.of(
			field(Field.PRICE, proposal != null && proposal.priceAvailable(), List.of("costPrice"),
				links,
				stale, snapshot.getPriceCollectedAt(), snapshot.getPriceAppliedAt()),
			field(Field.STOCK, proposal != null && proposal.stockAvailable(), List.of("stockStatus"), links,
				stale, snapshot.getStockCollectedAt(), snapshot.getStockAppliedAt()));
		Instant appliedAt = snapshot.getPriceAppliedAt() == null ? snapshot.getStockAppliedAt()
			: snapshot.getStockAppliedAt() == null
				|| snapshot.getPriceAppliedAt().isAfter(snapshot.getStockAppliedAt())
					? snapshot.getPriceAppliedAt() : snapshot.getStockAppliedAt();
		return new Snapshot(snapshot.getId(), snapshot.getProductId(), snapshot.getSbCode(), snapshot.getRevision(),
			snapshot.getSourceUrl(), snapshot.getVendor(), snapshot.getState(), snapshot.getReason(),
			snapshot.getRequestedAt(),
			snapshot.getCollectedAt(), snapshot.getExpiresAt(), appliedAt, captured.current(),
			proposal == null ? null : proposal.values(),
			fields, proposal == null ? List.of("현재 IHB·VTB·FTN·COK·OCD 수집을 지원합니다. 다른 소싱처는 계약 확인 후 추가합니다.")
				: proposal.notices());
	}

	private FieldView field(Field field, boolean available, List<String> keys, List<MarketRegistration> links,
		String stale, Instant collectedAt, Instant appliedAt) {
		String reason = keys.stream().map(key -> policy.sourceObservationRule(key, links))
			.filter(rule -> !rule.editable())
			.map(ProductEditPolicy.Rule::reason).findFirst().orElse(null);
		if (stale != null)
			reason = stale;
		if (!available)
			reason = "이 항목의 수집 또는 계산이 완료되지 않았습니다.";
		return new FieldView(field, available, available && reason == null,
			reason == null ? "검토 후 DB에 적용할 수 있습니다. 마켓 반영 성공을 뜻하지 않습니다." : reason, collectedAt, appliedAt);
	}

	private Proposed proposal(ProductSourceSnapshot snapshot) {
		return snapshot.getProposed() == null ? null : read(snapshot.getProposed(), Proposed.class);
	}

	private String boundedJson(Object value) {
		String payload = json(value);
		if (payload.length() > 5_000_000)
			throw new IllegalArgumentException("검토 내용이 너무 큽니다. 상품 선택 범위를 줄이세요.");
		return payload;
	}

	private String json(Object value) {
		try {
			return mapper.writeValueAsString(value);
		} catch (Exception e) {
			throw new IllegalStateException("가격·재고 검토 기록 직렬화 실패", e);
		}
	}

	private <T> T read(String value, Class<T> type) {
		try {
			return mapper.readValue(value, type);
		} catch (Exception e) {
			throw new IllegalStateException("가격·재고 검토 기록 읽기 실패", e);
		}
	}

	private <T> T read(String value, TypeReference<T> type) {
		try {
			return mapper.readValue(value, type);
		} catch (Exception e) {
			throw new IllegalStateException("가격·재고 검토 기록 읽기 실패", e);
		}
	}

	public static void requireUuid(String id) {
		try {
			if (id == null || !UUID.fromString(id).toString().equals(id))
				throw new IllegalArgumentException();
		} catch (RuntimeException e) {
			throw new IllegalArgumentException("유효한 요청 또는 검토 ID가 필요합니다.");
		}
	}

	private static void requireActor(String actor) {
		if (actor == null || actor.isBlank() || actor.length() > 200)
			throw new IllegalArgumentException("인증된 작업자가 필요합니다.");
	}
}
