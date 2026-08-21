package com.sbshop.agent.core.domain.process.repository;

import com.sbshop.agent.core.domain.process.ProcessStatus;
import com.sbshop.agent.core.domain.process.enums.ProcessStatusType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ProcessStatusRepository extends JpaRepository<ProcessStatus, Long> {
	List<ProcessStatus> findByBatchId(String batchId);

	@Query("select p.batchId from ProcessStatus p group by p.batchId order by max(p.startedAt) desc")
	List<String> findDistinctBatchIds();

	List<ProcessStatus> findByBatchIdOrderByStartedAtDesc(String batchId);

	List<ProcessStatus> findByBatchIdAndProcessStatusOrderByStartedAtDesc(
		String batchId, ProcessStatusType processStatus);

	long countByBatchId(String batchId);

	long countByBatchIdAndProcessStatus(String batchId, ProcessStatusType status);

	List<ProcessStatus> findByProcessStatus(ProcessStatusType status);
}
