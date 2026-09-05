package com.sbshop.agent.core.application.product;

import com.sbshop.agent.core.domain.common.exception.ResourceNotFoundException;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.component.ProductReader;
import com.sbshop.agent.core.domain.product.dto.ProductSearchCondition;
import com.sbshop.agent.core.domain.product.enums.ProductCategory;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProductSearchUseCase {
	private final ProductReader productReader;

	public Page<Product> searchProducts(ProductSearchCondition condition, Pageable pageable) {
		return productReader.search(condition, pageable);
	}

	public List<String> getCategoryNames() {
		return productReader.findDistinctCategories().stream()
			.filter(Objects::nonNull)
			.map(ProductCategory::name)
			.distinct()
			.sorted()
			.toList();
	}

	public Product getProductDetail(Long id) {
		return productReader.findById(id)
			.orElseThrow(() -> new ResourceNotFoundException("상품을 찾을 수 없습니다: " + id));
	}

	public List<String> getBrandNames() {
		return productReader.findDistinctBrands();
	}
}
