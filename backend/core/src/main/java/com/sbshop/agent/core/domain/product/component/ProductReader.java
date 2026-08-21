package com.sbshop.agent.core.domain.product.component;

import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ProductReader {
	Optional<Product> findById(Long id);

	Optional<Product> findBySbCode(String sbCode);

	Page<Product> search(String keyword, Pageable pageable);

	Page<Product> findByMarketRegistration(MarketType marketType, boolean registered, Pageable pageable);

	Page<Product> findByMarketRegistrationAndKeyword(
		MarketType marketType, boolean registered, String keyword, Pageable pageable);

	List<Product> findAllByIds(List<Long> ids);

	String getNextSbCodeSequence(String prefix);
}
