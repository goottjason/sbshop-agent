package com.sbshop.agent.core.domain.process.repository;

import com.sbshop.agent.core.domain.process.ProcessStatus;
import com.sbshop.agent.core.domain.process.enums.ProcessStatusType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessStatusRepository extends JpaRepository<ProcessStatus, Long> {
	List<ProcessStatus> findByBatchId(String batchId);

	List<ProcessStatus> findByBatchIdOrderByStartedAtDesc(String batchId);

	long countByBatchId(String batchId);

	long countByBatchIdAndProcessStatus(String batchId, ProcessStatusType status);

	List<ProcessStatus> findByProcessStatus(ProcessStatusType status);
}
