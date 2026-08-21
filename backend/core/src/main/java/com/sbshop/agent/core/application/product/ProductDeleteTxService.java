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
		if (!registrations.isEmpty()) {
			marketRegistrationRepository.deleteAll(registrations);
		}
		productWriter.delete(product);
		log.info("상품 완전 삭제(DB): productId={}, 등록행={}개", product.getId(), registrations.size());
	}
}
