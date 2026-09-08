package com.sbshop.agent.core.application.product.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.product.batch.ProductSupplierBatchService.*;
import com.sbshop.agent.core.application.product.edit.*;
import com.sbshop.agent.core.application.product.source.ProductSourceData;
import com.sbshop.agent.core.application.product.source.ProductSourceService;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.ProductRepository;
import com.sbshop.agent.core.domain.product.edit.ProductChangeHistoryRepository;
import com.sbshop.agent.core.domain.product.source.*;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Internal batch bridge: persisted source evidence plus the run's immutable approved pricing policy. */
@Service
@RequiredArgsConstructor
public class ProductSupplierBatchSource {
	private final ProductRepository products;
	private final MarketRegistrationRepository registrations;
	private final ProductSourceSnapshotRepository snapshots;
	private final ProductSourceCollectionRepository collections;
	private final ProductSourceReviewRepository reviews;
	private final ProductChangeHistoryRepository histories;
	private final com.sbshop.agent.core.application.pricing.VendorPricePolicyService vendorPolicies;
	private final ProductEditPlanner planner;
	private final ProductEditService edits;
	private final ObjectMapper mapper;
	private final PlatformTransactionManager transactions;

	public record Prepared(String reviewId, ProductEditPlanner.Plan plan, Calculation calculation) {
	}
	public record Saved(String state, String detail, Long historyId, Long revision) {
	}
	public record BatchReview(String kind, ProductSourceService.Reviewed reviewed) {
	}

	private static final String REVIEW_KIND = "SUPPLIER_BATCH_SOURCE";

	@Transactional
	public Prepared review(String snapshotId, Set<Field> selected, Policy policy, String actor) {
		var snapshot = owned(snapshotId, actor);
		var product = products.findById(snapshot.getProductId())
			.orElseThrow(() -> new ProductEditConflictException("상품이 없습니다."));
		verify(snapshot, product, selected);
		var links = registrations.findByProductId(product.getId());
		if (product.getRevision() != snapshot.getRevision()
			|| !planner.fingerprint(links).equals(snapshot.getConnectionFingerprint()))
			throw new ProductEditConflictException("수집 이후 상품 또는 마켓 연결이 변경되었습니다.");
		var proposal = read(snapshot.getProposed(), ProductSourceData.Proposed.class);
		var values = mapper.createObjectNode();
		var appliedCouponRate = policy.couponRate();
		var couponNotices = new ArrayList<String>();
		if (selected.contains(Field.PRICE)) {
			var discount = proposal.pricingEvidence() == null ? null : proposal.pricingEvidence().iherbDiscount();
			if (product.getVendor() == com.sbshop.agent.core.domain.product.enums.VendorType.IHB
				&& discount != null && discount.excludesCoupon()) {
				appliedCouponRate = java.math.BigDecimal.ZERO;
				couponNotices.add("아이허브 할인 제외 상품: discountType=0, discountDisplayType=0. 입력 쿠폰율 "
					+ policy.couponRate().stripTrailingZeros().toPlainString() + "% 대신 0%를 적용합니다.");
			}
			if (!proposal.priceAvailable())
				throw new ProductEditConflictException("가격 수집 또는 환율·배송비 계산이 완료되지 않았습니다.");
			values.put("costPrice", proposal.values().costPrice());
			values.put("exchangeRate", proposal.values().exchangeRate());
			values.put("marginRate", policy.marginRate());
			values.put("couponRate", appliedCouponRate);
			values.put("minMarginPrice", policy.minMarginPrice());
		}
		if (selected.contains(Field.STOCK)) {
			if (!proposal.stockAvailable())
				throw new ProductEditConflictException("재고 상태가 확인되지 않았습니다.");
			values.put("stockStatus", proposal.values().stockStatus().name());
			if (proposal.values().stock() != null)
				values.put("stock", proposal.values().stock());
		}
		var plan = planner.planBatchSourceObservation(product, values, links);
		var fields = selected.stream().sorted().map(f -> ProductSourceData.Field.valueOf(f.name())).toList();
		Instant now = Instant.now(), expires = now.plusSeconds(1800).isBefore(snapshot.getExpiresAt())
			? now.plusSeconds(1800) : snapshot.getExpiresAt();
		var review = reviews.save(new ProductSourceReview(UUID.randomUUID().toString(), actor, now, expires,
			json(new BatchReview(REVIEW_KIND, new ProductSourceService.Reviewed(snapshotId, fields, plan)))));
		var calculation = new Calculation(proposal.values().costPrice(), proposal.values().exchangeRate(), policy,
			proposal.pricingEvidence(), plan.prices(),
			concat(concat(proposal.notices(), plan.notices()), couponNotices),
			selected.contains(Field.PRICE) ? appliedCouponRate : null);
		return new Prepared(review.getId(), plan, calculation);
	}

	public Saved commit(String reviewId, String actor) {
		var review = reviews.findById(reviewId).filter(r -> r.getActor().equals(actor))
			.orElseThrow(() -> new ProductEditConflictException("배치 소싱 검토 기록을 찾을 수 없습니다."));
		BatchReview batch;
		try {
			batch = mapper.readValue(review.getPayload(), BatchReview.class);
		} catch (Exception e) {
			throw new ProductEditConflictException("배치 전용 소싱 검토 기록이 아닙니다.");
		}
		if (batch == null || !REVIEW_KIND.equals(batch.kind()) || batch.reviewed() == null
			|| batch.reviewed().plan() == null || batch.reviewed().fields() == null
			|| batch.reviewed().fields().isEmpty())
			throw new ProductEditConflictException("배치 DB 저장은 정확히 한 상품의 배치 검토만 사용합니다.");
		var row = batch.reviewed();
		var tx = new TransactionTemplate(transactions);
		tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		return tx.execute(status -> {
			var snapshot = snapshots.findLocked(row.snapshotId())
				.orElseThrow(() -> new ProductEditConflictException("소싱 수집 기록이 없습니다."));
			if (!Objects.equals(snapshot.getProductId(), row.plan().productId()))
				throw new ProductEditConflictException("배치 검토와 수집 상품이 일치하지 않습니다.");
			var saved = histories.findByReviewIdAndProductId(reviewId, row.plan().productId());
			if (saved.isEmpty()) {
				var product = products.findForEdit(snapshot.getProductId())
					.orElseThrow(() -> new ProductEditConflictException("상품 없음"));
				verify(snapshot, product, row.fields().stream().map(f -> Field.valueOf(f.name()))
					.collect(java.util.stream.Collectors.toSet()));
			}
			var result = edits.commitReviewedBatchSource(reviewId, actor, review.getExpiresAt(),
				snapshot.getCollectedAt(), row.fields().contains(ProductSourceData.Field.STOCK), row.plan());
			if (!result.state().equals("SAVED"))
				return new Saved(result.state(), result.reason(), result.historyId(), null);
			snapshot.applied(row.fields().contains(ProductSourceData.Field.PRICE),
				row.fields().contains(ProductSourceData.Field.STOCK), Instant.now());
			var history = histories.findById(result.historyId()).orElseThrow();
			return new Saved(result.state(), result.reason(), result.historyId(), history.getAfterRevision());
		});
	}

	private ProductSourceSnapshot owned(String id, String actor) {
		var s = snapshots.findById(id).orElseThrow(() -> new ProductEditConflictException("수집 결과 없음"));
		collections.findById(s.getCollectionId()).filter(c -> c.getActor().equals(actor))
			.orElseThrow(() -> new ProductEditConflictException("다른 계정의 소싱 수집 결과입니다."));
		return s;
	}

	private void verify(ProductSourceSnapshot snapshot, Product product, Set<Field> selected) {
		if (selected == null || selected.isEmpty())
			throw new IllegalArgumentException("배치 저장 항목이 없습니다.");
		if (product.isDeleted() || !Objects.equals(product.getSourcingUrl(), snapshot.getSourceUrl())
			|| !Objects.equals(product.getVendor() == null ? null : product.getVendor().name(), snapshot.getVendor()))
			throw new ProductEditConflictException("상품 또는 소싱 URL·소싱처가 변경되었습니다.");
		if (snapshot.getExpiresAt() == null || !Instant.now().isBefore(snapshot.getExpiresAt())
			|| snapshot.getCollectedAt() == null)
			throw new ProductEditConflictException("소싱 수집 결과가 없거나 만료되었습니다. 새 수집이 필요합니다.");
		if (selected.contains(Field.PRICE)
			&& !Objects.equals(read(snapshot.getCaptured(), ProductSourceData.Captured.class).shipping(),
				ProductSourceData.Shipping.from(vendorPolicies.find(product.getVendor()).orElse(null))))
			throw new ProductEditConflictException("수집 이후 소싱 배송비 정책이 변경되어 다시 수집해야 합니다.");
	}

	private <T> T read(String value, Class<T> type) {
		try {
			return mapper.readValue(value, type);
		} catch (Exception e) {
			throw new IllegalStateException("소싱 증거 읽기 오류", e);
		}
	}

	private String json(Object value) {
		try {
			return mapper.writeValueAsString(value);
		} catch (Exception e) {
			throw new IllegalStateException("소싱 검토 기록 직렬화 오류", e);
		}
	}

	private List<String> concat(List<String> a, List<String> b) {
		var all = new ArrayList<String>();
		if (a != null)
			all.addAll(a);
		if (b != null)
			all.addAll(b);
		return List.copyOf(all);
	}
}
