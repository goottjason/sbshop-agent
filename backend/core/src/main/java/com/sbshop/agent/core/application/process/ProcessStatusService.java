package com.sbshop.agent.core.application.process;

import com.sbshop.agent.core.domain.process.ProcessStatus;
import com.sbshop.agent.core.domain.process.enums.JobType;
import com.sbshop.agent.core.domain.process.enums.ProcessStep;
import com.sbshop.agent.core.domain.process.enums.ProcessStatusType;
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

	/**
	 * F-BATCH-1: 동시 배치 중복 실행 가드. <b>jobType별</b>로 "진행 중" 여부를 in-JVM 상태로 추적한다.
	 *
	 * <p><b>in-JVM으로 충분한 근거</b>: 배치는 api JVM 단일 인스턴스에서만 실행되고(worker와 별도 JVM,
	 * deployment-two-jvm-topology), 배포는 api를 재시작하므로 동시 2인스턴스가 아니다. 따라서 교차 JVM
	 * advisory lock 없이 in-JVM 상태(기존 *OrderSyncService의 AtomicBoolean 패턴과 동형)로 충분하다.
	 *
	 * <p><b>가드 범위(jobType별)</b>: 같은 jobType이 이미 진행 중이면 새 시작을 거부한다(IllegalStateException→400).
	 * 서로 다른 jobType은 동시에 허용한다(전면 "한 번에 하나"는 과제약).
	 *
	 * <p><b>트리거~완료 전체 커버</b>: startBatch(트리거)는 controller 스레드에서 실행되고 실제 배치는 @Async라
	 * 분리되므로, 가드는 startBatch에서 획득하고 배치 종료 시점(BatchCompletedEvent 리스너 → releaseBatch)에
	 * 해제한다. 이렇게 트리거~완료 구간 전체를 덮는다.
	 */
	private final Set<JobType> runningJobTypes = ConcurrentHashMap.newKeySet();

	/** 완료 이벤트는 batchId만 전달하므로, 해제 시 어떤 jobType을 풀지 역참조하기 위한 매핑. */
	private final Map<String, JobType> batchJobTypes = new ConcurrentHashMap<>();

	@Transactional
	public String startBatch(JobType jobType, List<String> productCodes) {
		// F-BATCH-1: 같은 jobType 배치가 진행 중이면 새 시작을 거부(중복 실행 방지).
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
			// 시딩 도중 실패 시 가드가 영구 잠김이 되지 않도록 즉시 해제(배치가 @Async로 뜨지 못했으므로
			// BatchCompletedEvent도 발행되지 않는다).
			runningJobTypes.remove(jobType);
			throw e;
		}
		batchJobTypes.put(batchId, jobType);
		log.info("배치 시작: batchId={}, jobType={}, count={}", batchId, jobType, productCodes.size());
		return batchId;
	}

	/**
	 * F-BATCH-1: 배치 종료 시 jobType 가드를 해제한다. BatchCompletedEvent 리스너가 batchId로 호출한다.
	 * 미등록/이미 해제된 batchId는 no-op(멱등) — 중복 이벤트나 미시딩 배치에도 안전.
	 */
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
	public void markFailed(String batchId, String productCode, String message) {
		updateStep(batchId, productCode, ProcessStep.UPDATE_PRODUCT_ERROR, ProcessStatusType.FAILED, message);
	}

	@Transactional(readOnly = true)
	public List<ProcessStatus> getBatchStatus(String batchId) {
		return getBatchStatus(batchId, null);
	}

	/**
	 * 배치 상세 상태를 조회한다. statusFilter가 주어지면 해당 상태 행만 반환한다(F-BATCH-S3, 예: FAILED만).
	 *
	 * <p>미존재 판정은 <b>전체 행 유무</b>로 한다(R1의 404 동작 보존). 배치는 startBatch가 상품별로
	 * 최소 1행을 심으므로 행이 하나도 없으면 미존재 batchId다(F-BATCH-S2). 필터 결과가 비었다는 이유로
	 * 404를 던지면 "FAILED 없음(정상 배치)"과 "미존재 배치"를 혼동하므로, 미존재는 count로만 판정하고
	 * 필터 결과가 비면 빈 목록(200)을 반환한다.
	 *
	 * @param statusFilter 필터할 상태(null이면 전 행 — 기존 비파괴 계약)
	 */
	@Transactional(readOnly = true)
	public List<ProcessStatus> getBatchStatus(String batchId, ProcessStatusType statusFilter) {
		if (statusFilter == null) {
			// 빈 배열+200으로 응답하면 미존재를 정상 조회처럼 감추므로 404로 매핑되도록 던진다.
			List<ProcessStatus> statuses = processStatusRepository.findByBatchIdOrderByStartedAtDesc(batchId);
			if (statuses.isEmpty()) {
				throw new com.sbshop.agent.core.domain.common.exception.ResourceNotFoundException(
					"배치를 찾을 수 없습니다: " + batchId);
			}
			return statuses;
		}
		// 필터 조회: 미존재는 전체 행 count로 판정(필터 결과 공집합과 구분).
		if (processStatusRepository.countByBatchId(batchId) == 0) {
			throw new com.sbshop.agent.core.domain.common.exception.ResourceNotFoundException(
				"배치를 찾을 수 없습니다: " + batchId);
		}
		return processStatusRepository.findByBatchIdAndProcessStatusOrderByStartedAtDesc(batchId, statusFilter);
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
