package com.sbshop.agent.core.domain.product.component;

import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.dto.ProductSearchCondition;
import com.sbshop.agent.core.domain.product.enums.ProductCategory;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ProductReader {
	Optional<Product> findById(Long id);

	Optional<Product> findBySbCode(String sbCode);

	Page<Product> search(ProductSearchCondition condition, Pageable pageable);

	List<ProductCategory> findDistinctCategories();

	List<Product> findAllByIds(List<Long> ids);

	String getNextSbCodeSequence(String prefix);
}
