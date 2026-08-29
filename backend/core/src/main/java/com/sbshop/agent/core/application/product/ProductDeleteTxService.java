package com.sbshop.agent.core.application.product;

import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.market.repository.MarketRegistrationRepository;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.component.ProductWriter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductDeleteTxService {
	private final MarketRegistrationRepository marketRegistrationRepository;
	private final ProductWriter productWriter;

	@Transactional
	public void deleteWithRegistrations(Product product, List<MarketRegistration> registrations) {
		product.markDeleted();
		productWriter.save(product);
		log.info("상품 폐기(소프트 삭제): productId={}, 등록행 {}개는 보존한다(과거 주문 추적 근거)",
			product.getId(), registrations.size());
	}

	@Transactional
	public void purge(Product product, List<MarketRegistration> registrations) {
		if (!registrations.isEmpty()) {
			marketRegistrationRepository.deleteAll(registrations);
		}
		productWriter.delete(product);
		log.warn("상품 완전 삭제(하드): productId={}, 등록행={}개 — 되돌릴 수 없다",
			product.getId(), registrations.size());
	}
}
