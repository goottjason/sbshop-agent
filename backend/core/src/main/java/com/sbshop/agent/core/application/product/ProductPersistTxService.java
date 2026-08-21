package com.sbshop.agent.core.application.product;

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
public class ProductPersistTxService {
	private final ProductWriter productWriter;

	@Transactional
	public List<Product> saveAll(List<Product> products) {
		List<Product> saved = productWriter.saveAll(products);
		log.info("{}개 상품 일괄 저장 완료", saved.size());
		return saved;
	}
}
