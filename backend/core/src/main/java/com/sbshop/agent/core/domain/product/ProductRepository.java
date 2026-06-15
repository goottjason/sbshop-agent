package com.sbshop.agent.core.domain.product;

import java.util.List;
import java.util.Optional;

public interface ProductRepository {
	Product save(Product product);

	Optional<Product> findById(Long id);

	Optional<Product> findBySbCode(String sbCode);

	List<Product> findAll();
}
