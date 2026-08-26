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

	List<MarketRegistration> findByProductId(Long productId);

	List<MarketRegistration> findByProductIdIn(List<Long> productIds);

	List<MarketRegistration> findByMarketType(MarketType marketType);

	Optional<MarketRegistration> findByProductIdAndMarketType(Long productId, MarketType marketType);

	@Query("SELECT r FROM MarketRegistration r WHERE r.marketType = :marketType AND r.marketIdentifiers LIKE CONCAT('%', :value, '%')")
	List<MarketRegistration> findByMarketTypeAndIdentifiersContaining(@Param("marketType")
	MarketType marketType, @Param("value")
	String value);

	@Query("SELECT r.productId AS productId, p.sbCode AS sbCode, r.marketIdentifiers AS marketIdentifiers, "
		+ "p.priceInfo.salePrice AS localSalePrice "
		+ "FROM MarketRegistration r LEFT JOIN Product p ON p.id = r.productId "
		+ "WHERE r.marketType = :marketType")
	List<MarketRegistrationSyncRow> findSyncRowsByMarketType(@Param("marketType")
	MarketType marketType);
}
