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
	// F-MISC-9: 대상선정(NEW·PREPARING 주문 상품ID 조회)을 컨트롤러에서 이 서비스로 이동.
	private final OrderLineItemRepository orderLineItemRepository;
	// F-MISC-8: @Async 자기기록 — 크롤 성공/실패를 활동로그에 남긴다.
	private final ActionLogService actionLogService;

	/**
	 * F-MISC-8/9/10: 재고 동기화 오케스트레이션(비동기).
	 *
	 * <p>컨트롤러의 원시 {@code new Thread}를 대체한다. 관리되는 {@code syncTaskExecutor}에서 실행되어
	 * 스레드 고갈을 막고, 크롤 예외가 스레드에서 조용히 죽지 않도록 결과를 ActionLog로 기록한다.
	 * 대상선정(NEW·PREPARING 상품ID 조회·중복제거)도 이 서비스가 담당한다(F-MISC-9).
	 */
	@Async("syncTaskExecutor")
	public void syncStockForPreparingOrdersAsync() {
		try {
			// 1. 대상선정: '결제완료(NEW)' + '배송준비(PREPARING)' 상태 주문 상품ID 조회 후 중복 제거
			List<Long> productIdsNew = orderLineItemRepository
				.findProductIdsByShippingStatus(ShippingStatus.NEW);
			List<Long> productIdsPreparing = orderLineItemRepository
				.findProductIdsByShippingStatus(ShippingStatus.PREPARING);

			Set<Long> mergedIds = new LinkedHashSet<>(productIdsNew);
			mergedIds.addAll(productIdsPreparing);

			// 2. 필터링된 상품 ID 목록만 동기화 (안전한 크롤링)
			syncStockForPreparingOrders(new ArrayList<>(mergedIds));

			// 3. 성공 기록
			actionLogService.record(ActionLogConstants.STOCK_SYNC, null,
				ActionStatus.SUCCESS,
				"재고 동기화 완료 (대상 " + mergedIds.size() + "개 상품)");
		} catch (Exception e) {
			// 4. 실패 기록 — 예외를 삼키지 않고 로그·활동로그에 남긴다(async라 재던지기는 의미 적음)
			log.error("재고 동기화 오케스트레이션 실패", e);
			actionLogService.record(ActionLogConstants.STOCK_SYNC, null,
				ActionStatus.FAILED, "재고 동기화 실패: " + e.getMessage());
		}
	}

	@Transactional
	public void syncProductStock(Long productId) {
		// 1. 상품 ID로 상품 엔티티 조회 (없을 경우 예외 발생)
		Product product = productRepository.findById(productId)
			.orElseThrow(() -> new IllegalArgumentException("Product not found"));

		// 2. 소싱 정보 및 소싱 URL 존재 여부 확인
		if (product.getSourcingUrl() != null) {
			String sourceUrl = product.getSourcingUrl();
			try {
				// 3. 외부 크롤러 포트를 통해 소싱 URL의 재고/가격/입고일 통합 조회
				StockCheckResult result = productStockCrawlerPort
					.checkStockWithDetails(sourceUrl);

				// 4. 재고 상태 업데이트
				product.updateStockStatus(result.status());

				// 5. 원가 업데이트
				product.updateCostPrice(result.costPrice());

				// 6. 소싱 재고수량 업데이트
				product.updateSourcingStock(result.stock());

				// 7. 입고예정일 업데이트 (null 소거 방어, D-065)
				//    IN_STOCK이면 재입고일은 의미 없으므로 null 반영을 허용해 기존값을 지운다.
				//    OUT_OF_STOCK 유지 중 크롤이 restockDate=null(일시적 파싱 실패/응답변동)을
				//    주면 DB의 기존 재입고일을 덮어쓰지 않고 유지한다.
				if (result.status() == StockStatus.IN_STOCK || result.restockDate() != null) {
					product.updateRestockDate(result.restockDate());
				}

				// 8. 변경된 상품 정보 DB 저장
				productRepository.save(product);

				// 9. 동기화 완료 로깅
				log.info("상품 {} 동기화 완료 - 상태: {}, 원가: {}, 재고: {}, 입고예정일: {}",
					product.getSbCode(), result.status(), result.costPrice(), result.stock(), result.restockDate());
			} catch (Exception e) {
				// 10. 크롤링 및 동기화 실패 시 예외 로깅
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
				// 타겟 사이트 IP 차단(Rate-Limit) 방지를 위한 0.5초 딜레이
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
