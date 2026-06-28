package com.sbshop.agent.core.domain.product;

import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.ListCrudRepository;

public interface ProductRepository extends ListCrudRepository<Product, Long> {
	Product save(Product product);

	Optional<Product> findBySbCode(String sbCode);

	List<Product> findByProductNameContaining(String name);
}
