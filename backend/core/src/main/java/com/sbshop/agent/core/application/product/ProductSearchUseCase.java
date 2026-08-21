package com.sbshop.agent.core.application.product;

import com.sbshop.agent.core.domain.common.exception.ResourceNotFoundException;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.component.ProductReader;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProductSearchUseCase {
	private final ProductReader productReader;

	public Page<Product> searchProducts(String keyword, Pageable pageable) {
		return productReader.search(keyword, pageable);
	}

	public Page<Product> searchByMarket(MarketType marketType, boolean registered, Pageable pageable) {
		return productReader.findByMarketRegistration(marketType, registered, pageable);
	}

	public Page<Product> searchByMarketAndKeyword(
		MarketType marketType, boolean registered, String keyword, Pageable pageable) {
		return productReader.findByMarketRegistrationAndKeyword(marketType, registered, keyword, pageable);
	}

	public Product getProductDetail(Long id) {
		return productReader.findById(id)
			.orElseThrow(() -> new ResourceNotFoundException("상품을 찾을 수 없습니다: " + id));
	}
}
