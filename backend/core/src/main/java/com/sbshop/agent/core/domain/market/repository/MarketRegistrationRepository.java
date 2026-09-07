package com.sbshop.agent.core.domain.market.repository;

import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface MarketRegistrationRepository extends JpaRepository<MarketRegistration, Long> {

	@org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT r FROM MarketRegistration r WHERE r.id = :id")
	Optional<MarketRegistration> findForConnectionUpdate(@Param("id")
	Long id);

	List<MarketRegistration> findByProductId(Long productId);

	List<MarketRegistration> findByProductIdIn(List<Long> productIds);

	List<MarketRegistration> findByMarketType(MarketType marketType);

	@Query("select coalesce(max(r.id), 0) from MarketRegistration r where r.marketType = :market")
	long lastRegistrationId(@Param("market")
	MarketType market);

	@Query("select r from MarketRegistration r join Product p on p.id = r.productId where r.marketType = :market and r.connectionState = com.sbshop.agent.core.domain.market.MarketConnectionState.LINKED and p.deletedAt is null and r.id > :after and r.id <= :through order by r.id")
	List<MarketRegistration> dailyInspectionPage(@Param("market")
	MarketType market, @Param("after")
	long after,
		@Param("through")
		long through, org.springframework.data.domain.Pageable pageable);

	List<MarketRegistration> findByMarketTypeAndIsSyncedTrue(MarketType marketType);

	@Query("SELECT r FROM MarketRegistration r WHERE r.marketType = :marketType "
		+ "AND r.isSynced = false AND r.unsyncReason IS NULL ORDER BY r.id ASC")
	List<MarketRegistration> findUnclassifiedUnsynced(@Param("marketType")
	MarketType marketType);

	Optional<MarketRegistration> findByProductIdAndMarketType(Long productId, MarketType marketType);

	@Query("SELECT r FROM MarketRegistration r WHERE r.marketType = :marketType "
		+ "AND r.marketIdentifiers LIKE CONCAT('%', :value, '%') ORDER BY r.id ASC")
	List<MarketRegistration> findIdentifierCandidates(@Param("marketType")
	MarketType marketType, @Param("value")
	String value);

	@Query("SELECT r.productId AS productId, p.sbCode AS sbCode, r.marketIdentifiers AS marketIdentifiers, "
		+ "p.priceInfo.salePrice AS localSalePrice "
		+ "FROM MarketRegistration r LEFT JOIN Product p ON p.id = r.productId "
		+ "WHERE r.marketType = :marketType")
	List<MarketRegistrationSyncRow> findSyncRowsByMarketType(@Param("marketType")
	MarketType marketType);
}
