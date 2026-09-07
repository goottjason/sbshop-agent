package com.sbshop.agent.core.domain.product.source;

import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ProductSourceSnapshotRepository extends JpaRepository<ProductSourceSnapshot, String> {
	List<ProductSourceSnapshot> findByCollectionIdOrderByRequestedAtAscIdAsc(String collectionId);

	Optional<ProductSourceSnapshot> findFirstByStateOrderByRequestedAtAscIdAsc(ProductSourceSnapshot.State state);

	Optional<ProductSourceSnapshot> findFirstByStateAndVendorOrderByRequestedAtAscIdAsc(
		ProductSourceSnapshot.State state, String vendor);

	long countByStateIn(List<ProductSourceSnapshot.State> states);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select s from ProductSourceSnapshot s where s.id=:id")
	Optional<ProductSourceSnapshot> findLocked(@Param("id")
	String id);

	@Query("select s from ProductSourceSnapshot s, ProductSourceCollection c where s.collectionId=c.id "
		+ "and s.productId=:productId and c.actor=:actor order by s.requestedAt desc, s.id desc")
	List<ProductSourceSnapshot> history(@Param("productId")
	Long productId, @Param("actor")
	String actor,
		org.springframework.data.domain.Pageable pageable);
}
