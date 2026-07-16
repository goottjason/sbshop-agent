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
		// 배치는 startBatch가 상품별로 최소 1행을 심으므로 행이 하나도 없으면 미존재 batchId다(F-BATCH-S2).
		// 빈 배열+200으로 응답하면 미존재를 정상 조회처럼 감추므로 404로 매핑되도록 던진다.
		List<ProcessStatus> statuses = processStatusRepository.findByBatchIdOrderByStartedAtDesc(batchId);
		if (statuses.isEmpty()) {
			throw new com.sbshop.agent.core.domain.common.exception.ResourceNotFoundException(
				"배치를 찾을 수 없습니다: " + batchId);
		}
		return statuses;
	}

	@Transactional(readOnly = true)
	public BatchSummary getBatchSummary(String batchId) {
		long total = processStatusRepository.countByBatchId(batchId);
		// total==0 = 미존재 batchId(정상 배치는 최소 1행). "0% 진행중"과 구분되도록 404로 던진다(F-BATCH-SM1).
		// 진행 중 배치는 total>0이므로 여기서 걸리지 않고 아래 진행률 응답(200)을 그대로 반환한다 — 폴링 유지 보증.
		if (total == 0) {
			throw new com.sbshop.agent.core.domain.common.exception.ResourceNotFoundException(
				"배치를 찾을 수 없습니다: " + batchId);
		}
		long success = processStatusRepository.countByBatchIdAndProcessStatus(batchId, ProcessStatusType.SUCCESS);
		long failed = processStatusRepository.countByBatchIdAndProcessStatus(batchId, ProcessStatusType.FAILED);
		return BatchSummary.of(batchId, total, success, failed);
	}

	@Transactional(readOnly = true)
	public List<String> getAllBatchIds() {
		// 전 행을 메모리에 로드 후 distinct 하던 방식은 이력 누적 시 OOM 위험 → DB distinct 쿼리로 대체(F-BATCH-ST1).
		return processStatusRepository.findDistinctBatchIds();
	}

	/**
	 * 냉기동 시점에 남아있는 PENDING 행을 FAILED로 복구한다.
	 *
	 * <p>배치는 api JVM에서 실행되고 배포는 api를 재시작하므로(동시 2인스턴스 아님) 부팅 시점엔
	 * 진행 중 배치가 없다. 따라서 부팅 때 존재하는 PENDING은 전부 이전(죽은) 실행의 고아이며,
	 * 방치하면 getBatchSummary가 완료 판정을 못 한다(F-BATCH-2). 부팅 훅에서 1회만 호출한다.
	 *
	 * @return 복구한 행 수
	 */
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
