package com.sbshop.agent.core.domain.supplier.repository;

import com.sbshop.agent.core.domain.supplier.Currency;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CurrencyRepository extends JpaRepository<Currency, String> {

	// F-SUP-LC-2: 통화 목록은 currencyCode 오름차순으로 안정 정렬(정렬 부재로 인한 순서 비결정성 제거).
	List<Currency> findAllByOrderByCurrencyCodeAsc();
}
