package com.sbshop.agent.core.application.product;

import java.util.List;

import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.ProductRepository;
import com.sbshop.agent.core.application.product.dto.StockCheckResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.sbshop.agent.core.application.product.port.ProductStockCrawlerPort;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductSyncService {

	private final ProductRepository productRepository;
	private final ProductStockCrawlerPort productStockCrawlerPort;

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

				// 7. 입고예정일 업데이트
				product.updateRestockDate(result.restockDate());

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
