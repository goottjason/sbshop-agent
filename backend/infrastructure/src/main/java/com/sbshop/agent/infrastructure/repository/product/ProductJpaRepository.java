package com.sbshop.agent.infrastructure.repository.product;

import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.ProductRepository;
import org.springframework.data.jpa.repository.JpaRepository;

// ProductRepository already supplies the JPA bean. This legacy alias must not create a second one.
@org.springframework.data.repository.NoRepositoryBean
public interface ProductJpaRepository extends JpaRepository<Product, Long>, ProductRepository {}
