package com.sbshop.agent.core.domain.supplier.repository;

import com.sbshop.agent.core.domain.common.RecordStatus;
import com.sbshop.agent.core.domain.supplier.Supplier;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplierRepository extends JpaRepository<Supplier, Long> {
	Optional<Supplier> findBySupplierCode(String supplierCode);

	// F-SUP-CS-2: 중복 코드는 DB unique 예외에 의존하지 말고 사전검증으로 명확한 400을 낸다.
	boolean existsBySupplierCode(String supplierCode);

	// F-SUP-2: 목록 조회는 소프트삭제(ARCHIVED/DELETED)를 제외하고 ACTIVE만 반환한다.
	// F-SUP-4: 목록은 supplierCode 오름차순으로 안정 정렬(정렬 부재로 인한 순서 비결정성 제거).
	List<Supplier> findByStatusOrderBySupplierCodeAsc(RecordStatus status);
}
