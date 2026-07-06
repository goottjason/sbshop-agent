package com.sbshop.agent.core.domain.product.component;

import com.sbshop.agent.core.domain.product.Product;
import java.util.List;

public interface ProductWriter {
	Product save(Product product);

	List<Product> saveAll(List<Product> products);

	void delete(Product product);
}
