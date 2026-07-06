package com.sbshop.agent.core.domain.supplier.repository;

import com.sbshop.agent.core.domain.supplier.Supplier;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplierRepository extends JpaRepository<Supplier, Long> {
	Optional<Supplier> findBySupplierCode(String supplierCode);
}
