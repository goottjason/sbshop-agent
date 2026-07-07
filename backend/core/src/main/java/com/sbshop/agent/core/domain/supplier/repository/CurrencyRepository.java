package com.sbshop.agent.core.domain.supplier.repository;

import com.sbshop.agent.core.domain.supplier.Currency;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CurrencyRepository extends JpaRepository<Currency, String> {}
