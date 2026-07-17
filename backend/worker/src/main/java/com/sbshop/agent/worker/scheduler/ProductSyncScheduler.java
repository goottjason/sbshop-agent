package com.sbshop.agent.worker.scheduler;

import com.sbshop.agent.core.application.product.ProductSyncService;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.ProductRepository;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProductSyncScheduler {

	private final ProductSyncService productSyncService;
	private final ProductRepository productRepository;

	@Scheduled(cron = "0 0 4 * * ?", zone = "Asia/Seoul")
	public void syncIherbProductStock() {
		log.info("iHerb 상품 재고 주기적 동기화 시작...");
		List<Product> iherbProducts = productRepository.findByVendor(VendorType.IHB);
		List<Long> productIds = iherbProducts.stream().map(Product::getId).toList();

		if (productIds.isEmpty()) {
			log.info("iHerb 상품이 없습니다. 동기화를 건너뜁니다.");
			return;
		}

		log.info("iHerb 상품 {}개 재고 동기화 시작", productIds.size());
		productSyncService.syncStockForPreparingOrders(productIds);
		log.info("iHerb 상품 재고 주기적 동기화 완료");
	}
}
