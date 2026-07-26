package com.sbshop.agent.core.domain.product;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sbshop.agent.core.domain.order.enums.MarketType;

public interface ProductRepository extends JpaRepository<Product, Long> {
	Product save(Product product);

	Optional<Product> findBySbCode(String sbCode);

	List<Product> findBySbCodeIn(List<String> sbCodes);

	List<Product> findByProductNameContaining(String name);

	List<Product> findAllByIdIn(List<Long> ids);

	@Query("SELECT MAX(p.sbCode) FROM Product p WHERE p.sbCode LIKE CONCAT(:prefix, '%')")
	Optional<String> findMaxSbCodeByPrefix(@Param("prefix")
	String prefix);

	@Query("SELECT p FROM Product p WHERE p.sourcingInfo.vendor = :vendor")
	List<Product> findByVendor(@Param("vendor")
	com.sbshop.agent.core.domain.product.enums.VendorType vendor);

	/**
	 * 등록된 모든 상품의 소싱 URL. 신규 상품 발굴의 중복 제외(S1)가 이 목록에서 벤더 상품 ID를 뽑아
	 * 후보와 대조한다. 엔티티 전체가 아니라 URL 문자열만 가져와 대량 스캔 비용을 낮춘다.
	 */
	@Query("SELECT p.sourcingInfo.sourceUrl FROM Product p "
		+ "WHERE p.sourcingInfo.sourceUrl IS NOT NULL AND p.sourcingInfo.sourceUrl <> ''")
	List<String> findAllSourceUrls();

	@Query("SELECT p FROM Product p WHERE " +
		"LOWER(p.productName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
		"LOWER(p.sbCode) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
		"LOWER(p.brand) LIKE LOWER(CONCAT('%', :keyword, '%'))")
	Page<Product> searchByKeyword(@Param("keyword")
	String keyword, Pageable pageable);

	@Query("SELECT p FROM Product p WHERE p.id NOT IN " +
		"(SELECT r.productId FROM MarketRegistration r WHERE r.marketType = :marketType)")
	Page<Product> findUnregisteredByMarket(@Param("marketType")
	MarketType marketType, Pageable pageable);

	@Query("SELECT p FROM Product p WHERE p.id IN " +
		"(SELECT r.productId FROM MarketRegistration r WHERE r.marketType = :marketType)")
	Page<Product> findRegisteredByMarket(@Param("marketType")
	MarketType marketType, Pageable pageable);

	// F-PROD-1: 마켓 미등록 필터 AND 키워드 검색 결합(둘 다 지정 시 keyword가 무시되던 버그 수정).
	@Query("SELECT p FROM Product p WHERE p.id NOT IN " +
		"(SELECT r.productId FROM MarketRegistration r WHERE r.marketType = :marketType) AND (" +
		"LOWER(p.productName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
		"LOWER(p.sbCode) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
		"LOWER(p.brand) LIKE LOWER(CONCAT('%', :keyword, '%')))")
	Page<Product> findUnregisteredByMarketAndKeyword(@Param("marketType")
	MarketType marketType, @Param("keyword")
	String keyword, Pageable pageable);

	// F-PROD-1: 마켓 등록 필터 AND 키워드 검색 결합.
	@Query("SELECT p FROM Product p WHERE p.id IN " +
		"(SELECT r.productId FROM MarketRegistration r WHERE r.marketType = :marketType) AND (" +
		"LOWER(p.productName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
		"LOWER(p.sbCode) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
		"LOWER(p.brand) LIKE LOWER(CONCAT('%', :keyword, '%')))")
	Page<Product> findRegisteredByMarketAndKeyword(@Param("marketType")
	MarketType marketType, @Param("keyword")
	String keyword, Pageable pageable);
}
