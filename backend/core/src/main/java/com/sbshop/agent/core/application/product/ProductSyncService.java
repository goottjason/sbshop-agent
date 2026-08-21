package com.sbshop.agent.core.application.product;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.domain.actionlog.ActionLogConstants;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.ProductRepository;
import com.sbshop.agent.core.domain.product.enums.StockStatus;
import com.sbshop.agent.core.application.product.dto.StockCheckResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.sbshop.agent.core.application.product.port.ProductStockCrawlerPort;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductSyncService {
	private final ProductRepository productRepository;
	private final ProductStockCrawlerPort productStockCrawlerPort;

	private final OrderLineItemRepository orderLineItemRepository;

	private final ActionLogService actionLogService;

	@Async("syncTaskExecutor")
	public void syncStockForPreparingOrdersAsync() {
		try {
			List<Long> productIdsNew = orderLineItemRepository
				.findProductIdsByShippingStatus(ShippingStatus.NEW);
			List<Long> productIdsPreparing = orderLineItemRepository
				.findProductIdsByShippingStatus(ShippingStatus.PREPARING);

			Set<Long> mergedIds = new LinkedHashSet<>(productIdsNew);
			mergedIds.addAll(productIdsPreparing);

			syncStockForPreparingOrders(new ArrayList<>(mergedIds));

			actionLogService.record(ActionLogConstants.STOCK_SYNC, null,
				ActionStatus.SUCCESS,
				"재고 동기화 완료 (대상 " + mergedIds.size() + "개 상품)");
		} catch (Exception e) {
			log.error("재고 동기화 오케스트레이션 실패", e);
			actionLogService.record(ActionLogConstants.STOCK_SYNC, null,
				ActionStatus.FAILED, "재고 동기화 실패: " + e.getMessage());
		}
	}

	@Transactional
	public void syncProductStock(Long productId) {
		Product product = productRepository.findById(productId)
			.orElseThrow(() -> new IllegalArgumentException("Product not found"));

		if (product.getSourcingUrl() != null) {
			String sourceUrl = product.getSourcingUrl();
			try {
				StockCheckResult result = productStockCrawlerPort
					.checkStockWithDetails(sourceUrl);

				product.updateStockStatus(result.status());

				product.updateCostPrice(result.costPrice());

				product.updateSourcingStock(result.stock());

				if (result.status() == StockStatus.IN_STOCK || result.restockDate() != null) {
					product.updateRestockDate(result.restockDate());
				}

				productRepository.save(product);

				log.info("상품 {} 동기화 완료 - 상태: {}, 원가: {}, 재고: {}, 입고예정일: {}",
					product.getSbCode(), result.status(), result.costPrice(), result.stock(), result.restockDate());
			} catch (Exception e) {
				log.error("상품 재고 동기화 실패: {}", product.getSbCode(), e);
			}
		}
	}

	@Transactional
	public void syncStockForPreparingOrders(List<Long> productIds) {
		if (productIds == null || productIds.isEmpty()) {
			log.info("준비 중인 주문이 없습니다. 재고 동기화를 건너뜁니다.");
			return;
		}

		log.info("준비 중인 주문과 연결된 {}개 상품의 재고 동기화를 시작합니다.", productIds.size());

		int syncedCount = 0;
		for (Long productId : productIds) {
			try {
				syncProductStock(productId);
				syncedCount++;

				Thread.sleep(500);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				log.warn("재고 동기화 스레드 중단됨");
				break;
			} catch (Exception e) {
				log.error("상품 ID {} 재고 동기화 실패: {}", productId, e);
			}
		}

		log.info("재고 동기화 완료. {}/{}개 상품 동기화 성공.", syncedCount, productIds.size());
	}
}
