package com.sbshop.agent.core.application.process;

import com.sbshop.agent.core.domain.common.exception.ResourceNotFoundException;
import com.sbshop.agent.core.domain.process.ProcessStatus;
import com.sbshop.agent.core.domain.process.enums.JobType;
import com.sbshop.agent.core.domain.process.enums.ProcessStatusType;
import com.sbshop.agent.core.domain.process.repository.ProcessStatusRepository;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProcessStatusServiceTest {

	@Mock
	private ProcessStatusRepository repository;
	@InjectMocks
	private ProcessStatusService service;

	@Test
	@DisplayName("배치 시작 시 batchId가 생성되고 각 상품별 ProcessStatus가 저장된다")
	void startBatch_createsBatchIdAndStatuses() {
		when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		String batchId = service.startBatch(JobType.CRAWL_AND_UPDATE_PRICE_STOCK, List.of("P001", "P002"));

		assertThat(batchId).isNotBlank().hasSize(8);
		verify(repository, times(2)).save(any());
	}

	@Test
	@DisplayName("getBatchStatus로 배치 상태를 조회한다")
	void getBatchStatus_returnsStatuses() {
		ProcessStatus status = ProcessStatus.builder()
			.batchId("test1234").productCode("P001")
			.processStatus(ProcessStatusType.SUCCESS).build();
		when(repository.findByBatchIdOrderByStartedAtDesc("test1234"))
			.thenReturn(List.of(status));

		List<ProcessStatus> result = service.getBatchStatus("test1234");

		assertThat(result).hasSize(1);
		assertThat(result.get(0).getProductCode()).isEqualTo("P001");
	}

	@Test
	@DisplayName("getBatchStatus는 미존재 batchId(행 없음)면 ResourceNotFoundException을 던진다(F-BATCH-S2, 404)")
	void getBatchStatus_unknownBatchId_throwsNotFound() {
		when(repository.findByBatchIdOrderByStartedAtDesc("nope"))
			.thenReturn(List.of());

		assertThatThrownBy(() -> service.getBatchStatus("nope"))
			.isInstanceOf(ResourceNotFoundException.class)
			.hasMessageContaining("nope");
	}

	@Test
	@DisplayName("getBatchSummary는 total/success/failed count로 done/pending/percent를 산출한다")
	void getBatchSummary_computesAggregate() {
		when(repository.countByBatchId("b-10")).thenReturn(10L);
		when(repository.countByBatchIdAndProcessStatus("b-10", ProcessStatusType.SUCCESS)).thenReturn(3L);
		when(repository.countByBatchIdAndProcessStatus("b-10", ProcessStatusType.FAILED)).thenReturn(1L);

		var summary = service.getBatchSummary("b-10");

		assertThat(summary.batchId()).isEqualTo("b-10");
		assertThat(summary.total()).isEqualTo(10L);
		assertThat(summary.success()).isEqualTo(3L);
		assertThat(summary.failed()).isEqualTo(1L);
		assertThat(summary.done()).isEqualTo(4L);
		assertThat(summary.pending()).isEqualTo(6L);
		assertThat(summary.percent()).isEqualTo(40);
	}

	@Test
	@DisplayName("getBatchSummary는 미존재 batchId(total=0)면 ResourceNotFoundException을 던진다(F-BATCH-SM1, 404)")
	void getBatchSummary_zeroTotal_throwsNotFound() {
		when(repository.countByBatchId("empty")).thenReturn(0L);

		assertThatThrownBy(() -> service.getBatchSummary("empty"))
			.isInstanceOf(ResourceNotFoundException.class)
			.hasMessageContaining("empty");
	}

	@Test
	@DisplayName("getBatchSummary는 진행중 배치(total>0, 0% 진행)면 404가 아니라 percent 0인 200 응답을 유지한다(폴링 유지 보증)")
	void getBatchSummary_inProgressZeroPercent_returns200() {
		when(repository.countByBatchId("running")).thenReturn(5L);
		when(repository.countByBatchIdAndProcessStatus("running", ProcessStatusType.SUCCESS)).thenReturn(0L);
		when(repository.countByBatchIdAndProcessStatus("running", ProcessStatusType.FAILED)).thenReturn(0L);

		var summary = service.getBatchSummary("running");

		assertThat(summary.total()).isEqualTo(5L);
		assertThat(summary.percent()).isEqualTo(0);
		assertThat(summary.done()).isEqualTo(0L);
		assertThat(summary.pending()).isEqualTo(5L);
	}

	@Test
	@DisplayName("recoverOrphanedPending은 남아있는 PENDING 행을 FAILED로 복구하고 복구 건수를 반환한다")
	void recoverOrphanedPending_marksPendingAsFailed() {
		ProcessStatus orphan1 = ProcessStatus.builder()
			.batchId("dead-1").productCode("P001")
			.processStatus(ProcessStatusType.PENDING).build();
		ProcessStatus orphan2 = ProcessStatus.builder()
			.batchId("dead-1").productCode("P002")
			.processStatus(ProcessStatusType.PENDING).build();
		when(repository.findByProcessStatus(ProcessStatusType.PENDING))
			.thenReturn(List.of(orphan1, orphan2));

		int recovered = service.recoverOrphanedPending();

		assertThat(recovered).isEqualTo(2);
		assertThat(orphan1.getProcessStatus()).isEqualTo(ProcessStatusType.FAILED);
		assertThat(orphan2.getProcessStatus()).isEqualTo(ProcessStatusType.FAILED);
		assertThat(orphan1.getMessage()).contains("재시작");
	}

	@Test
	@DisplayName("recoverOrphanedPending은 PENDING 행이 없으면 0을 반환하고 아무것도 저장하지 않는다")
	void recoverOrphanedPending_noPending_returnsZero() {
		when(repository.findByProcessStatus(ProcessStatusType.PENDING))
			.thenReturn(List.of());

		int recovered = service.recoverOrphanedPending();

		assertThat(recovered).isZero();
	}

	@Test
	@DisplayName("getAllBatchIds는 DB distinct 쿼리로 batchId만 조회하고 전 행 findAll을 호출하지 않는다(F-BATCH-ST1 OOM 방지)")
	void getAllBatchIds_usesDistinctQuery_notFindAll() {
		when(repository.findDistinctBatchIds())
			.thenReturn(List.of("batch-1", "batch-2"));

		List<String> result = service.getAllBatchIds();

		assertThat(result).containsExactly("batch-1", "batch-2");
		verify(repository, times(1)).findDistinctBatchIds();
		verify(repository, never()).findAll();
	}

	@Test
	@DisplayName("getAllBatchIds는 리포지토리의 최신순 정렬 순서를 그대로 보존한다(F-BATCH-ST2 최신순)")
	void getAllBatchIds_preservesLatestFirstOrder() {
		when(repository.findDistinctBatchIds())
			.thenReturn(List.of("latest", "middle", "oldest"));

		List<String> result = service.getAllBatchIds();

		assertThat(result).containsExactly("latest", "middle", "oldest");
	}

	@Test
	@DisplayName("getBatchStatus(status 필터)는 해당 상태 행만 반환한다(F-BATCH-S3 상태 필터)")
	void getBatchStatus_withStatusFilter_returnsOnlyMatching() {
		ProcessStatus bad = ProcessStatus.builder()
			.batchId("b1").productCode("P002").processStatus(ProcessStatusType.FAILED).build();
		when(repository.countByBatchId("b1")).thenReturn(2L);
		when(repository.findByBatchIdAndProcessStatusOrderByStartedAtDesc("b1", ProcessStatusType.FAILED))
			.thenReturn(List.of(bad));

		List<ProcessStatus> result = service.getBatchStatus("b1", ProcessStatusType.FAILED);

		assertThat(result).hasSize(1);
		assertThat(result.get(0).getProductCode()).isEqualTo("P002");
		assertThat(result.get(0).getProcessStatus()).isEqualTo(ProcessStatusType.FAILED);
	}

	@Test
	@DisplayName("getBatchStatus(status=null)는 기존 동작대로 전 행을 반환한다(비파괴 계약 보존)")
	void getBatchStatus_nullFilter_returnsAllLikeBefore() {
		ProcessStatus s1 = ProcessStatus.builder()
			.batchId("b1").productCode("P001").processStatus(ProcessStatusType.SUCCESS).build();
		when(repository.findByBatchIdOrderByStartedAtDesc("b1"))
			.thenReturn(List.of(s1));

		List<ProcessStatus> result = service.getBatchStatus("b1", null);

		assertThat(result).hasSize(1);
		verify(repository, never()).findByBatchIdAndProcessStatusOrderByStartedAtDesc(any(), any());
	}

	@Test
	@DisplayName("getBatchStatus(status 필터)도 미존재 batchId(전체 행 없음)면 404를 던진다(R1 404 동작 보존)")
	void getBatchStatus_withFilter_unknownBatchId_throwsNotFound() {
		when(repository.countByBatchId("nope")).thenReturn(0L);

		assertThatThrownBy(() -> service.getBatchStatus("nope", ProcessStatusType.FAILED))
			.isInstanceOf(ResourceNotFoundException.class)
			.hasMessageContaining("nope");
	}

	@Test
	@DisplayName("getBatchStatus(status 필터)는 배치는 존재하나 해당 상태 행이 없으면 빈 목록을 반환한다(404 아님)")
	void getBatchStatus_withFilter_existingBatchNoMatch_returnsEmpty() {
		when(repository.countByBatchId("b1")).thenReturn(5L);
		when(repository.findByBatchIdAndProcessStatusOrderByStartedAtDesc("b1", ProcessStatusType.FAILED))
			.thenReturn(List.of());

		List<ProcessStatus> result = service.getBatchStatus("b1", ProcessStatusType.FAILED);

		assertThat(result).isEmpty();
	}

	@Test
	@DisplayName("F-BATCH-1: 같은 jobType 배치가 진행 중이면 두 번째 startBatch는 IllegalStateException으로 거부한다")
	void startBatch_sameJobTypeAlreadyRunning_rejectsSecondStart() {
		when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		service.startBatch(JobType.CRAWL_AND_UPDATE_PRICE_STOCK, List.of("P001"));

		assertThatThrownBy(() ->
			service.startBatch(JobType.CRAWL_AND_UPDATE_PRICE_STOCK, List.of("P002")))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	@DisplayName("F-BATCH-1: 서로 다른 jobType은 동시에 시작할 수 있다(전면 잠금 아님)")
	void startBatch_differentJobTypes_bothAllowed() {
		when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		String b1 = service.startBatch(JobType.CRAWL_AND_UPDATE_PRICE_STOCK, List.of("P001"));
		String b2 = service.startBatch(JobType.MANUAL_UPDATE_PRICE_STOCK, List.of("P002"));

		assertThat(b1).isNotBlank();
		assertThat(b2).isNotBlank().isNotEqualTo(b1);
	}

	@Test
	@DisplayName("F-BATCH-1: 배치 완료로 가드가 해제되면 같은 jobType을 다시 시작할 수 있다(영구 잠금 아님)")
	void startBatch_afterCompletionReleasesGuard_allowsRestart() {
		when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

		String b1 = service.startBatch(JobType.CRAWL_AND_UPDATE_PRICE_STOCK, List.of("P001"));

		service.releaseBatch(b1);

		assertThat(service.startBatch(JobType.CRAWL_AND_UPDATE_PRICE_STOCK, List.of("P002")))
			.isNotBlank();
	}

	@Test
	@DisplayName("F-BATCH-1: 미등록 batchId로 releaseBatch를 호출해도 예외 없이 무시된다(멱등)")
	void releaseBatch_unknownBatchId_isNoOp() {
		service.releaseBatch("no-such-batch");
	}
}
