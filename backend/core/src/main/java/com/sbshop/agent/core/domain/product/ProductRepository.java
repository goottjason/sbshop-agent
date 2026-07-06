package com.sbshop.agent.core.domain.product;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, Long> {
	Product save(Product product);

	Optional<Product> findBySbCode(String sbCode);

	List<Product> findBySbCodeIn(List<String> sbCodes);

	List<Product> findByProductNameContaining(String name);

	List<Product> findAllByIdIn(List<Long> ids);

	@Query("SELECT MAX(p.sbCode) FROM Product p WHERE p.sbCode LIKE CONCAT(:prefix, '%')")
	Optional<String> findMaxSbCodeByPrefix(@Param("prefix") String prefix);

	@Query("SELECT p FROM Product p WHERE p.sourcingInfo.vendor = :vendor")
	List<Product> findByVendor(@Param("vendor") com.sbshop.agent.core.domain.product.enums.VendorType vendor);
}
