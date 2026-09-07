package com.sbshop.agent.core.domain.market.marketplus;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MarketPlusTransmissionRepository extends JpaRepository<MarketPlusTransmission, Long> {
	boolean existsByFingerprint(String fingerprint);

	List<MarketPlusTransmission> findTop100ByProductIdOrderByCompletedAtDescIdDesc(Long productId);

	// Keep all ties: minute-resolution UI times cannot order conflicting results by import ID.
	@Query("""
		select e from MarketPlusTransmission e where e.registrationId in :registrationIds and not exists (
		 select n.id from MarketPlusTransmission n where n.registrationId=e.registrationId
		 and n.productId=e.productId
		 and n.mallId=e.mallId and n.shopNo=e.shopNo and n.market=e.market and n.sellerAccount=e.sellerAccount
		 and n.cafe24ProductNo=e.cafe24ProductNo and n.cafe24ProductCode=e.cafe24ProductCode
		 and n.externalId=e.externalId and n.transferType=e.transferType and n.completedAt>e.completedAt
		) order by e.completedAt desc, e.id desc
		""")
	List<MarketPlusTransmission> findLatestObservations(@Param("registrationIds")
	List<Long> registrationIds);
}
