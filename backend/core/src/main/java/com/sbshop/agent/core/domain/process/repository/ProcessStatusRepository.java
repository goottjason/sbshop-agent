package com.sbshop.agent.core.domain.process.repository;

import com.sbshop.agent.core.domain.process.ProcessStatus;
import com.sbshop.agent.core.domain.process.enums.ProcessStatusType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ProcessStatusRepository extends JpaRepository<ProcessStatus, Long> {
	List<ProcessStatus> findByBatchId(String batchId);

	/**
	 * batchId 목록을 DB에서 distinct 처리해 조회한다(전 행 findAll 후 메모리 distinct 방지 — OOM 예방).
	 * 최신 배치가 앞에 오도록 batchId별 최대 startedAt 내림차순 정렬.
	 */
	@Query("select p.batchId from ProcessStatus p group by p.batchId order by max(p.startedAt) desc")
	List<String> findDistinctBatchIds();

	List<ProcessStatus> findByBatchIdOrderByStartedAtDesc(String batchId);

	long countByBatchId(String batchId);

	long countByBatchIdAndProcessStatus(String batchId, ProcessStatusType status);

	List<ProcessStatus> findByProcessStatus(ProcessStatusType status);
}
