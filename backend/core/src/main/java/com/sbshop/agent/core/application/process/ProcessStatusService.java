package com.sbshop.agent.core.application.process;

import com.sbshop.agent.core.domain.common.exception.ResourceNotFoundException;
import com.sbshop.agent.core.domain.process.ProcessStatus;
import com.sbshop.agent.core.domain.process.enums.JobType;
import com.sbshop.agent.core.domain.process.enums.ProcessStatusType;
import com.sbshop.agent.core.domain.process.enums.ProcessStep;
import com.sbshop.agent.core.domain.process.repository.ProcessStatusRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProcessStatusService {

	private final ProcessStatusRepository processStatusRepository;

	private final Set<JobType> runningJobTypes = ConcurrentHashMap.newKeySet();

	private final Map<String, JobType> batchJobTypes = new ConcurrentHashMap<>();

	@Transactional
	public String startBatch(JobType jobType, List<String> productCodes) {
		if (!runningJobTypes.add(jobType)) {
			throw new IllegalStateException(
				"이미 진행 중인 배치가 있습니다: jobType=" + jobType + " (완료 후 다시 시도하세요)");
		}
		String batchId = UUID.randomUUID().toString().substring(0, 8);
		try {
			for (String productCode : productCodes) {
				ProcessStatus status = ProcessStatus.builder()
					.batchId(batchId)
					.productCode(productCode)
					.jobType(jobType)
					.step(ProcessStep.INITIALIZE_BATCH)
					.processStatus(ProcessStatusType.PENDING)
					.startedAt(LocalDateTime.now())
					.build();
				processStatusRepository.save(status);
			}
		} catch (RuntimeException e) {
			runningJobTypes.remove(jobType);
			throw e;
		}
		batchJobTypes.put(batchId, jobType);
		log.info("배치 시작: batchId={}, jobType={}, count={}", batchId, jobType, productCodes.size());
		return batchId;
	}

	public void releaseBatch(String batchId) {
		JobType jobType = batchJobTypes.remove(batchId);
		if (jobType != null) {
			runningJobTypes.remove(jobType);
			log.info("배치 가드 해제: batchId={}, jobType={}", batchId, jobType);
		}
	}

	@Transactional
	public void updateStep(String batchId, String productCode, ProcessStep step,
		ProcessStatusType status, String message) {
		List<ProcessStatus> statuses = processStatusRepository.findByBatchId(batchId);
		statuses.stream()
			.filter(s -> s.getProductCode().equals(productCode))
			.findFirst()
			.ifPresent(s -> s.updateStep(step, status, message));
	}

	@Transactional
	public void markSuccess(String batchId, String productCode, String message) {
		updateStep(batchId, productCode, ProcessStep.UPDATE_PRODUCT_SAVE, ProcessStatusType.SUCCESS, message);
	}

	@Transactional
	public void updateStep(String batchId, String productCode, ProcessStep step,
		ProcessStatusType status, String message, String details) {
		processStatusRepository.findByBatchId(batchId).stream()
			.filter(s -> s.getProductCode().equals(productCode))
			.findFirst()
			.ifPresent(s -> s.updateStep(step, status, message, details));
	}

	@Transactional
	public void markPartialFailed(String batchId, String productCode, String message) {
		updateStep(batchId, productCode, ProcessStep.UPDATE_PRODUCT_PUBLISH,
			ProcessStatusType.PARTIAL_FAILED, message);
	}

	@Transactional
	public void markPartialFailed(String batchId, String productCode, String message, String details) {
		updateStep(batchId, productCode, ProcessStep.UPDATE_PRODUCT_PUBLISH,
			ProcessStatusType.PARTIAL_FAILED, message, details);
	}

	@Transactional
	public void markSuccess(String batchId, String productCode, String message, String details) {
		updateStep(batchId, productCode, ProcessStep.UPDATE_PRODUCT_SAVE,
			ProcessStatusType.SUCCESS, message, details);
	}

	@Transactional
	public void markFailed(String batchId, String productCode, String message) {
		updateStep(batchId, productCode, ProcessStep.UPDATE_PRODUCT_ERROR, ProcessStatusType.FAILED, message);
	}

	@Transactional(readOnly = true)
	public List<ProcessStatus> getBatchStatus(String batchId) {
		return getBatchStatus(batchId, null);
	}

	@Transactional(readOnly = true)
	public List<ProcessStatus> getBatchStatus(String batchId, ProcessStatusType statusFilter) {
		if (statusFilter == null) {
			List<ProcessStatus> statuses = processStatusRepository.findByBatchIdOrderByStartedAtDesc(batchId);
			if (statuses.isEmpty()) {
				throw new ResourceNotFoundException(
					"배치를 찾을 수 없습니다: " + batchId);
			}
			return statuses;
		}
		if (processStatusRepository.countByBatchId(batchId) == 0) {
			throw new ResourceNotFoundException(
				"배치를 찾을 수 없습니다: " + batchId);
		}
		return processStatusRepository.findByBatchIdAndProcessStatusOrderByStartedAtDesc(batchId, statusFilter);
	}

	@Transactional(readOnly = true)
	public BatchSummary getBatchSummary(String batchId) {
		long total = processStatusRepository.countByBatchId(batchId);
		if (total == 0) {
			throw new ResourceNotFoundException(
				"배치를 찾을 수 없습니다: " + batchId);
		}
		long success = processStatusRepository.countByBatchIdAndProcessStatus(batchId, ProcessStatusType.SUCCESS);
		long failed = processStatusRepository.countByBatchIdAndProcessStatus(batchId, ProcessStatusType.FAILED);
		long partial = processStatusRepository.countByBatchIdAndProcessStatus(batchId,
			ProcessStatusType.PARTIAL_FAILED);
		return BatchSummary.of(batchId, total, success, failed, partial);
	}

	@Transactional(readOnly = true)
	public List<String> getAllBatchIds() {
		return processStatusRepository.findDistinctBatchIds();
	}

	@Transactional
	public int recoverOrphanedPending() {
		List<ProcessStatus> orphans = processStatusRepository.findByProcessStatus(ProcessStatusType.PENDING);
		for (ProcessStatus orphan : orphans) {
			orphan.updateStep(ProcessStep.UPDATE_PRODUCT_ERROR, ProcessStatusType.FAILED,
				"이전 실행 중단으로 복구 처리(재시작)");
		}
		if (!orphans.isEmpty()) {
			log.warn("고아 PENDING 배치 상태 복구: {}건을 FAILED로 처리", orphans.size());
		}
		return orphans.size();
	}
}
