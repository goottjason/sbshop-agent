package com.sbshop.agent.core.application.process;

import com.sbshop.agent.core.domain.process.ProcessStatus;
import com.sbshop.agent.core.domain.process.enums.JobType;
import com.sbshop.agent.core.domain.process.enums.ProcessStatusType;
import com.sbshop.agent.core.domain.process.repository.ProcessStatusRepository;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
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
}
