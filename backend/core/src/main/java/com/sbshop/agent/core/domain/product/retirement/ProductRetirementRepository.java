package com.sbshop.agent.core.domain.product.retirement;

import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRetirementRepository extends JpaRepository<ProductRetirement, Long> {

	Optional<ProductRetirement> findByProductId(Long productId);

	List<ProductRetirement> findByProductIdIn(List<Long> productIds);

	List<ProductRetirement> findByRetirementStatus(RetirementStatus status);

	List<ProductRetirement> findByRetirementStatusAndEsmConfirmedAtIsNull(RetirementStatus status);

	long countByRetirementStatus(RetirementStatus status);

	boolean existsByProductIdInAndRetirementStatus(List<Long> productIds, RetirementStatus status);

	@Query("SELECT r FROM ProductRetirement r WHERE r.retirementStatus = :status "
		+ "ORDER BY r.lastAttemptAt ASC NULLS FIRST, r.id ASC")
	List<ProductRetirement> findDue(@Param("status")
	RetirementStatus status, Pageable pageable);

	@Query("SELECT p FROM Product p WHERE p.sourcingInfo.vendor = :vendor AND p.deletedAt IS NULL "
		+ "AND NOT EXISTS (SELECT 1 FROM OrderLineItem li WHERE li.productId = p.id) "
		+ "AND NOT EXISTS (SELECT 1 FROM ProductRetirement r WHERE r.productId = p.id "
		+ "AND r.retirementStatus IN (com.sbshop.agent.core.domain.product.retirement.RetirementStatus.IN_PROGRESS, "
		+ "com.sbshop.agent.core.domain.product.retirement.RetirementStatus.DONE)) "
		+ "ORDER BY p.id")
	List<Product> findCandidates(@Param("vendor")
	VendorType vendor, Pageable pageable);
}
