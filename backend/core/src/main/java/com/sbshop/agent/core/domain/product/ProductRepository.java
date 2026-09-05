package com.sbshop.agent.core.domain.product;

import com.sbshop.agent.core.domain.product.enums.ProductCategory;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {
	Product save(Product product);

	Optional<Product> findBySbCode(String sbCode);

	List<Product> findBySbCodeIn(List<String> sbCodes);

	List<Product> findByProductNameContaining(String name);

	List<Product> findAllByIdIn(List<Long> ids);

	@Query("SELECT MAX(p.sbCode) FROM Product p WHERE p.sbCode LIKE CONCAT(:prefix, '%')")
	Optional<String> findMaxSbCodeByPrefix(@Param("prefix")
	String prefix);

	@Query("SELECT p.id FROM Product p WHERE p.deletedAt IS NULL "
		+ "AND p.sourcingInfo.sourceUrl IS NOT NULL AND p.sourcingInfo.sourceUrl <> '' "
		+ "AND p.brand IS NOT NULL AND p.brand <> '' "
		+ "AND (LOCATE(' ', p.originalName) = 0 OR p.brand <> SUBSTRING(p.originalName, 1, LOCATE(' ', p.originalName) - 1)) "
		+ "ORDER BY p.id")
	List<Long> findFieldSyncTargetIds();

	@Query("SELECT p FROM Product p WHERE p.sourcingInfo.vendor = :vendor AND p.deletedAt IS NULL")
	List<Product> findByVendor(@Param("vendor")
	VendorType vendor);

	@Query("SELECT p.sourcingInfo.sourceUrl FROM Product p "
		+ "WHERE p.sourcingInfo.sourceUrl IS NOT NULL AND p.sourcingInfo.sourceUrl <> ''")
	List<String> findAllSourceUrls();

	@Query("SELECT DISTINCT p.category FROM Product p WHERE p.deletedAt IS NULL AND p.category IS NOT NULL")
	List<ProductCategory> findDistinctCategories();

	@Query("SELECT p.id FROM Product p "
		+ "WHERE p.deletedAt IS NULL "
		+ "AND (p.productSpec.barcode IS NULL OR p.productSpec.barcode = '') "
		+ "AND p.sourcingInfo.sourceUrl IS NOT NULL AND p.sourcingInfo.sourceUrl <> '' "
		+ "ORDER BY p.id")
	List<Long> findBarcodeBackfillTargetIds();

	@Query("SELECT p.id FROM Product p "
		+ "WHERE p.deletedAt IS NULL "
		+ "AND p.sourcingInfo.vendor = :vendor "
		+ "AND (p.productSpec.barcode IS NULL OR p.productSpec.barcode = '') "
		+ "AND p.sourcingInfo.sourceUrl IS NOT NULL AND p.sourcingInfo.sourceUrl <> '' "
		+ "ORDER BY p.id")
	List<Long> findBarcodeBackfillTargetIds(@Param("vendor")
	VendorType vendor);

	@Query("SELECT p.id FROM Product p "
		+ "WHERE p.deletedAt IS NULL "
		+ "AND p.brand IS NOT NULL AND p.brand <> '' "
		+ "AND p.originalName IS NOT NULL AND p.originalName <> '' "
		+ "AND p.sourcingInfo.sourceUrl IS NOT NULL AND p.sourcingInfo.sourceUrl <> '' "
		+ "AND p.brand = CASE WHEN LOCATE(' ', p.originalName) > 0 "
		+ "THEN SUBSTRING(p.originalName, 1, LOCATE(' ', p.originalName) - 1) ELSE p.originalName END "
		+ "ORDER BY p.id")
	List<Long> findBrandBackfillTargetIds();

	@Query("SELECT p.id FROM Product p "
		+ "WHERE p.deletedAt IS NULL "
		+ "AND p.sourcingInfo.vendor = :vendor "
		+ "AND p.brand IS NOT NULL AND p.brand <> '' "
		+ "AND p.originalName IS NOT NULL AND p.originalName <> '' "
		+ "AND p.sourcingInfo.sourceUrl IS NOT NULL AND p.sourcingInfo.sourceUrl <> '' "
		+ "AND p.brand = CASE WHEN LOCATE(' ', p.originalName) > 0 "
		+ "THEN SUBSTRING(p.originalName, 1, LOCATE(' ', p.originalName) - 1) ELSE p.originalName END "
		+ "ORDER BY p.id")
	List<Long> findBrandBackfillTargetIds(@Param("vendor")
	VendorType vendor);
}
