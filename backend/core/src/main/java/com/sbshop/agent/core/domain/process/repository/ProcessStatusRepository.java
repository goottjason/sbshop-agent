package com.sbshop.agent.core.domain.process.repository;

import com.sbshop.agent.core.domain.process.ProcessStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessStatusRepository extends JpaRepository<ProcessStatus, Long> {
	List<ProcessStatus> findByBatchId(String batchId);
	List<ProcessStatus> findByBatchIdOrderByStartedAtDesc(String batchId);
}
