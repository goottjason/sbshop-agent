package com.sbshop.agent.core.domain.product.content;

import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ProductContentSnapshotRepository extends JpaRepository<ProductContentSnapshot, String> {
	List<ProductContentSnapshot> findByCollectionIdOrderByRequestedAtAscIdAsc(String collectionId);

	Optional<ProductContentSnapshot> findFirstByStateOrderByRequestedAtAscIdAsc(ProductContentSnapshot.State state);

	long countByStateIn(List<ProductContentSnapshot.State> states);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select s from ProductContentSnapshot s where s.id=:id")
	Optional<ProductContentSnapshot> findLocked(@Param("id")
	String id);

	@Query("select s from ProductContentSnapshot s, ProductContentCollection c where s.collectionId=c.id "
		+ "and s.productId=:productId and c.actor=:actor order by s.requestedAt desc, s.id desc")
	List<ProductContentSnapshot> history(@Param("productId")
	Long productId, @Param("actor")
	String actor,
		org.springframework.data.domain.Pageable pageable);
}
