package com.sbshop.agent.core.domain.ledger.repository;

import com.sbshop.agent.core.domain.common.RecordStatus;
import com.sbshop.agent.core.domain.ledger.LedgerPaymentSource;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LedgerPaymentSourceRepository extends JpaRepository<LedgerPaymentSource, Long> {

	List<LedgerPaymentSource> findByStatusOrderByDisplayOrderAscIdAsc(RecordStatus status);

	Optional<LedgerPaymentSource> findByName(String name);

	boolean existsByNameAndStatus(String name, RecordStatus status);
}
