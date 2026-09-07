package com.sbshop.agent.core.application.product;

import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.product.dto.StockCheckResult;
import com.sbshop.agent.core.application.product.port.ProductStockCrawlerPort;
import com.sbshop.agent.core.domain.actionlog.ActionLogConstants;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.ProductRepository;
import com.sbshop.agent.core.domain.product.enums.StockStatus;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductSyncService {
	private final ProductRepository productRepository;
	private final ProductStockCrawlerPort productStockCrawlerPort;
	private final OrderLineItemRepository orderLineItemRepository;
	private final ActionLogService actionLogService;
	private final PlatformTransactionManager transactions;

	public enum State {
		UPDATED, FAILED, CONFLICT, SKIPPED, CANCELLED
	}
	public record Item(Long productId, State state, String reason) {
	}
	public record Result(List<Item> items) {
		public Result {
			items = List.copyOf(items);
		}

		public long updatedCount() {
			return items.stream().filter(i -> i.state() == State.UPDATED).count();
		}

		public boolean complete() {
			return updatedCount() == items.size();
		}

		public String summary() {
			return "소싱 재고 수집 결과: 성공 " + updatedCount() + " / 미반영 "
				+ (items.size() - updatedCount()) + " / 대상 " + items.size() + "개";
		}
	}
	private record Target(Long id, long revision, String url) {
	}

	@Async("syncTaskExecutor")
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public void syncStockForPreparingOrdersAsync() {
		try {
			var ids = new LinkedHashSet<Long>();
			ids.addAll(orderLineItemRepository.findProductIdsByShippingStatus(ShippingStatus.NEW));
			ids.addAll(orderLineItemRepository.findProductIdsByShippingStatus(ShippingStatus.PREPARING));
			Result result = syncStockForPreparingOrders(new ArrayList<>(ids));
			actionLogService.record(ActionLogConstants.STOCK_SYNC, null,
				result.complete() ? ActionStatus.SUCCESS : ActionStatus.FAILED, result.summary());
		} catch (Exception e) {
			log.error("재고 수집 오케스트레이션 실패", e);
			actionLogService.record(ActionLogConstants.STOCK_SYNC, null,
				ActionStatus.FAILED, "재고 수집 실패: " + failureMessage(e));
		}
	}

	/** Read and write in separate short transactions. Crawling never holds a product lock. */
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public Item syncProductStock(Long productId) {
		Target target;
		try {
			target = transaction(true).execute(s -> productRepository.findById(productId)
				.filter(p -> !p.isDeleted())
				.map(p -> new Target(productId, p.getRevision(), p.getSourcingUrl())).orElse(null));
		} catch (Exception e) {
			return new Item(productId, State.FAILED, "상품 조회 실패: " + failureMessage(e));
		}
		if (target == null)
			return new Item(productId, State.SKIPPED, "상품이 없거나 폐기되었습니다.");
		if (target.url() == null || target.url().isBlank())
			return new Item(productId, State.SKIPPED, "소싱 URL이 없어 수집하지 않았습니다.");
		StockCheckResult observed;
		try {
			observed = productStockCrawlerPort.checkStockWithDetails(target.url());
			validate(observed);
		} catch (Exception e) {
			String reason = "소싱 조회 실패: " + failureMessage(e);
			recordFailure(target, reason);
			return new Item(productId, State.FAILED, reason);
		}
		try {
			return transaction(false).execute(s -> {
				Product current = productRepository.findForEdit(productId).orElse(null);
				if (!matches(current, target))
					return new Item(productId, State.CONFLICT, "수집 중 상품 정보가 변경되어 반영하지 않았습니다. 다시 수집하세요.");
				current.updateStockStatus(observed.status());
				// An absent observation does not erase an existing cost or sourcing quantity.
				if (observed.costPrice() != null)
					current.updateCostPrice(observed.costPrice());
				if (observed.stock() != null)
					current.updateSourcingStock(observed.stock());
				if (observed.status() == StockStatus.IN_STOCK || observed.restockDate() != null)
					current.updateRestockDate(observed.restockDate());
				current.recordCrawlSuccess();
				productRepository.saveAndFlush(current);
				return new Item(productId, State.UPDATED, "확인된 소싱 재고 정보를 DB에 반영했습니다.");
			});
		} catch (Exception e) {
			return new Item(productId, State.FAILED, "수집 결과 DB 저장 실패: " + failureMessage(e));
		}
	}

	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public Result syncStockForPreparingOrders(List<Long> productIds) {
		if (productIds == null || productIds.isEmpty())
			return new Result(List.of());
		var ids = new ArrayList<>(new LinkedHashSet<>(productIds));
		var results = new ArrayList<Item>();
		for (int i = 0; i < ids.size(); i++) {
			Long id = ids.get(i);
			if (Thread.currentThread().isInterrupted()) {
				Item cancelled = new Item(id, State.CANCELLED, "수집이 중단되어 실행하지 않았습니다.");
				results.add(cancelled);
				recordUnapplied(cancelled);
				continue;
			}
			Item item = syncProductStock(id);
			results.add(item);
			recordUnapplied(item);
			if (i + 1 < ids.size()) {
				try {
					Thread.sleep(500);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
		}
		Result result = new Result(results);
		log.info("{}", result.summary());
		return result;
	}

	private void recordUnapplied(Item item) {
		if (item.state() != State.UPDATED) {
			actionLogService.record(ActionLogConstants.STOCK_SYNC, null,
				item.state() == State.CANCELLED ? ActionStatus.WARNING : ActionStatus.FAILED,
				"상품 " + item.productId() + " · " + item.state() + " · " + item.reason());
		}
	}

	private TransactionTemplate transaction(boolean readOnly) {
		var tx = new TransactionTemplate(transactions);
		tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		tx.setReadOnly(readOnly);
		return tx;
	}

	private boolean matches(Product product, Target target) {
		return product != null && !product.isDeleted() && product.getRevision() == target.revision()
			&& Objects.equals(product.getSourcingUrl(), target.url());
	}

	private void recordFailure(Target target, String reason) {
		try {
			transaction(false).executeWithoutResult(s -> {
				Product current = productRepository.findForEdit(target.id()).orElse(null);
				if (matches(current, target)) {
					current.recordCrawlFailure(reason);
					productRepository.saveAndFlush(current);
				}
			});
		} catch (Exception e) {
			log.warn("상품 {} 수집 실패 기록 저장 실패: {}", target.id(), e.getClass().getSimpleName());
		}
	}

	private void validate(StockCheckResult observed) {
		if (observed == null || observed.status() == null)
			throw new IllegalArgumentException("판매 가능 상태가 없는 응답입니다. 기존 값은 유지합니다.");
		if (observed.sourceGone())
			throw new IllegalArgumentException("소싱 상품 부재 응답입니다. 원본 소멸 점검이 필요하며 기존 값은 유지합니다.");
		if (observed.costPrice() != null && observed.costPrice().signum() < 0
			|| observed.stock() != null && observed.stock() < 0)
			throw new IllegalArgumentException("가격 또는 재고가 음수인 응답입니다. 기존 값은 유지합니다.");
	}

	private String failureMessage(Exception e) {
		return e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName()
			: ProductMarketSyncService.sanitizeMarketMessage(e.getMessage());
	}
}
