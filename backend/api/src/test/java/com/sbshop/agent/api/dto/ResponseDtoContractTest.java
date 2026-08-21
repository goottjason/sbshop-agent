package com.sbshop.agent.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sbshop.agent.api.dto.batch.ProcessStatusResponse;
import com.sbshop.agent.api.dto.market.MarketRegistrationResponse;
import com.sbshop.agent.api.dto.sync.SyncStatusResponse;
import com.sbshop.agent.core.application.sync.SyncStatusService;
import com.sbshop.agent.core.domain.market.MarketRegistration;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.process.ProcessStatus;
import com.sbshop.agent.core.domain.process.enums.JobType;
import com.sbshop.agent.core.domain.process.enums.ProcessStatusType;
import com.sbshop.agent.core.domain.process.enums.ProcessStep;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ResponseDtoContractTest {

	private final ObjectMapper mapper = new ObjectMapper()
		.registerModule(new JavaTimeModule())
		.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

	private String json(Object o) {
		try {
			return mapper.writeValueAsString(o);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@Test
	@DisplayName("F-BATCH-S1: ProcessStatusResponse 직렬화가 ProcessStatus 엔티티와 바이트 동일")
	void processStatusResponse_mirrorsEntityJson() {
		ProcessStatus entity = ProcessStatus.builder()
			.batchId("batch-1")
			.productCode("P100")
			.jobType(JobType.CRAWL_AND_UPDATE_PRICE_STOCK)
			.step(ProcessStep.values()[0])
			.processStatus(ProcessStatusType.values()[0])
			.message("진행중")
			.details("ch1:ok")
			.startedAt(LocalDateTime.of(2026, 7, 15, 10, 0))
			.build();
		ReflectionTestUtils.setField(entity, "id", 42L);
		ReflectionTestUtils.setField(entity, "createdAt", LocalDateTime.of(2026, 7, 15, 9, 0));
		ReflectionTestUtils.setField(entity, "updatedAt", LocalDateTime.of(2026, 7, 15, 9, 30));
		ReflectionTestUtils.setField(entity, "updatedAtExtra", LocalDateTime.of(2026, 7, 15, 10, 5));

		assertThat(json(ProcessStatusResponse.from(entity))).isEqualTo(json(entity));
	}

	@Test
	@DisplayName("F-BATCH-S1: null enum/audit 필드도 엔티티 직렬화와 동일")
	void processStatusResponse_mirrorsEntityJson_withNulls() {
		ProcessStatus entity = ProcessStatus.builder()
			.batchId("batch-2")
			.productCode("P200")
			.build();
		assertThat(json(ProcessStatusResponse.from(entity))).isEqualTo(json(entity));
	}

	@Test
	@DisplayName("F-SYNC-24: SyncStatusResponse 직렬화가 SyncStatus 내부클래스와 바이트 동일")
	void syncStatusResponse_mirrorsInnerClassJson() {
		SyncStatusService.SyncStatus inner = new SyncStatusService.SyncStatus(
			"COUPANG", "COMPLETED", LocalDateTime.of(2026, 7, 15, 8, 0), null);
		assertThat(json(SyncStatusResponse.from(inner))).isEqualTo(json(inner));

		SyncStatusService.SyncStatus failed = new SyncStatusService.SyncStatus(
			"SMART_STORE", "FAILED", null, "401 Unauthorized");
		assertThat(json(SyncStatusResponse.from(failed))).isEqualTo(json(failed));
	}

	@Test
	@DisplayName("F-MREG-4: MarketRegistrationResponse 직렬화가 엔티티와 바이트 동일 (원시 JSON 식별자 포함)")
	void marketRegistrationResponse_mirrorsEntityJson() {
		MarketRegistration entity = MarketRegistration.builder()
			.productId(7L)
			.sbProductId(70L)
			.marketType(MarketType.COUPANG)
			.marketProductName("테스트 상품")
			.marketIdentifiers("{\"vendorItemId\":\"V123\",\"sellerProductId\":\"S456\"}")
			.marketDetailedInfo("{\"price\":10000}")
			.build();
		ReflectionTestUtils.setField(entity, "id", 9L);
		ReflectionTestUtils.setField(entity, "createdAt", LocalDateTime.of(2026, 7, 14, 0, 0));
		ReflectionTestUtils.setField(entity, "updatedAt", LocalDateTime.of(2026, 7, 14, 1, 0));
		entity.markSynced();

		assertThat(json(MarketRegistrationResponse.from(entity))).isEqualTo(json(entity));
	}

	@Test
	@DisplayName("F-MREG-4: 잘못된/빈 식별자 JSON도 엔티티 폴백('{}')과 동일하게 raw 방출")
	void marketRegistrationResponse_mirrorsEntityJson_invalidIdentifiers() {
		MarketRegistration entity = MarketRegistration.builder()
			.productId(8L)
			.marketType(MarketType.SMART_STORE)
			.marketProductName(null)
			.marketIdentifiers(null)
			.marketDetailedInfo("not-json")
			.build();

		assertThat(json(MarketRegistrationResponse.from(entity))).isEqualTo(json(entity));
	}
}
