package com.sbshop.agent.core.application.process;

import com.sbshop.agent.core.domain.process.ProcessStatus;
import com.sbshop.agent.core.domain.process.enums.JobType;
import com.sbshop.agent.core.domain.process.enums.ProcessStep;
import com.sbshop.agent.core.domain.process.enums.ProcessStatusType;
import com.sbshop.agent.core.domain.process.repository.ProcessStatusRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProcessStatusService {

	private final ProcessStatusRepository processStatusRepository;

	@Transactional
	public String startBatch(JobType jobType, List<String> productCodes) {
		String batchId = UUID.randomUUID().toString().substring(0, 8);
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
		log.info("배치 시작: batchId={}, jobType={}, count={}", batchId, jobType, productCodes.size());
		return batchId;
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
	public void markFailed(String batchId, String productCode, String message) {
		updateStep(batchId, productCode, ProcessStep.UPDATE_PRODUCT_ERROR, ProcessStatusType.FAILED, message);
	}

	@Transactional(readOnly = true)
	public List<ProcessStatus> getBatchStatus(String batchId) {
		return processStatusRepository.findByBatchIdOrderByStartedAtDesc(batchId);
	}
}
