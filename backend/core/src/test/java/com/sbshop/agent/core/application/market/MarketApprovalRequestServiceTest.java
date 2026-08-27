package com.sbshop.agent.core.application.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.market.dto.MarketApprovalReport;
import com.sbshop.agent.core.application.market.dto.MarketApprovalRequestCommand;
import com.sbshop.agent.core.domain.actionlog.ActionLogConstants;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
import com.sbshop.agent.core.domain.market.client.MarketClient;
import com.sbshop.agent.core.domain.market.client.MarketClientRouter;
import com.sbshop.agent.core.domain.market.client.dto.MarketApprovalOutcome;
import com.sbshop.agent.core.domain.market.client.dto.MarketApprovalResult;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MarketApprovalRequestServiceTest {

	@Mock
	private MarketClientRouter marketClientRouter;
	@Mock
	private ActionLogService actionLogService;
	@Mock
	private MarketClient coupangClient;

	private MarketApprovalRequestService service;

	@BeforeEach
	void setUp() {
		service = new MarketApprovalRequestService(marketClientRouter, actionLogService);
		when(marketClientRouter.hasClient(MarketType.COUPANG)).thenReturn(true);
		when(marketClientRouter.getClient(MarketType.COUPANG)).thenReturn(coupangClient);
		when(coupangClient.supportsApprovalRequest()).thenReturn(true);
	}

	@Test
	@DisplayName("D-215: 건별 결과를 그대로 싣고 판정별로 집계한다")
	void returnsPerItemResultsAndAggregates() {
		when(coupangClient.requestApproval("1"))
			.thenReturn(MarketApprovalResult.requested("1", "임시저장", "SUCCESS", "성공"));
		when(coupangClient.requestApproval("2"))
			.thenReturn(MarketApprovalResult.skipped("2", "승인완료", "대상 상태가 아닙니다"));
		when(coupangClient.requestApproval("3"))
			.thenReturn(MarketApprovalResult.retryable("3", "임시저장", "ERROR", "상품 정보를 등록/수정 중입니다.", "10분 후"));
		when(coupangClient.requestApproval("4"))
			.thenReturn(MarketApprovalResult.failed("4", "임시저장", true, "ERROR", "거부", null));

		MarketApprovalReport report = service.request(MarketType.COUPANG,
			MarketApprovalRequestCommand.of(List.of("1", "2", "3", "4"), null));

		assertThat(report.items()).extracting(MarketApprovalResult::marketItemId)
			.containsExactly("1", "2", "3", "4");
		assertThat(report.items()).extracting(MarketApprovalResult::outcome)
			.containsExactly(MarketApprovalOutcome.REQUESTED, MarketApprovalOutcome.SKIPPED,
				MarketApprovalOutcome.RETRYABLE, MarketApprovalOutcome.FAILED);
		assertThat(report.submitted()).isEqualTo(4);
		assertThat(report.requested()).isEqualTo(1);
		assertThat(report.skipped()).isEqualTo(1);
		assertThat(report.retryable()).isEqualTo(1);
		assertThat(report.failed()).isEqualTo(1);
		assertThat(report.called()).isEqualTo(3);
		assertThat(report.market()).isEqualTo(MarketType.COUPANG);
	}

	@Test
	@DisplayName("D-215: 한 건이 예외로 터져도 그 건만 실패로 남기고 계속한다")
	void continuesAfterFailure() {
		when(coupangClient.requestApproval("1")).thenThrow(new RuntimeException("네트워크 장애"));
		when(coupangClient.requestApproval("2"))
			.thenReturn(MarketApprovalResult.requested("2", "임시저장", "SUCCESS", "성공"));

		MarketApprovalReport report = service.request(MarketType.COUPANG,
			MarketApprovalRequestCommand.of(List.of("1", "2"), null));

		assertThat(report.items()).hasSize(2);
		assertThat(report.items().get(0).outcome()).isEqualTo(MarketApprovalOutcome.FAILED);
		assertThat(report.items().get(0).responseMessage()).contains("네트워크 장애");
		assertThat(report.items().get(1).outcome()).isEqualTo(MarketApprovalOutcome.REQUESTED);
		verify(coupangClient).requestApproval("2");
	}

	@Test
	@DisplayName("D-215: 승인 요청을 지원하지 않는 마켓이면 아무 건도 호출하지 않는다")
	void unsupportedClientCallsNothing() {
		when(coupangClient.supportsApprovalRequest()).thenReturn(false);

		assertThatThrownBy(() -> service.request(MarketType.COUPANG,
			MarketApprovalRequestCommand.of(List.of("1"), null)))
			.isInstanceOf(IllegalStateException.class);

		verify(coupangClient, never()).requestApproval(anyString());
	}

	@Test
	@DisplayName("D-215: 클라이언트가 없는 마켓이면 아무 건도 호출하지 않는다")
	void missingClientCallsNothing() {
		when(marketClientRouter.hasClient(MarketType.COUPANG)).thenReturn(false);

		assertThatThrownBy(() -> service.request(MarketType.COUPANG,
			MarketApprovalRequestCommand.of(List.of("1"), null)))
			.isInstanceOf(IllegalStateException.class);

		verify(coupangClient, never()).requestApproval(anyString());
	}

	@Test
	@DisplayName("D-215: 마켓에 실제로 쏜 건만 활동로그에 남긴다(건너뛴 건은 쓰기가 아니다)")
	void recordsActionLogForCalledItemsOnly() {
		when(coupangClient.requestApproval("1"))
			.thenReturn(MarketApprovalResult.requested("1", "임시저장", "SUCCESS", "성공"));
		when(coupangClient.requestApproval("2"))
			.thenReturn(MarketApprovalResult.skipped("2", "승인완료", "대상 상태가 아닙니다"));
		when(coupangClient.requestApproval("3"))
			.thenReturn(MarketApprovalResult.failed("3", "임시저장", true, "ERROR", "거부", null));

		service.request(MarketType.COUPANG, MarketApprovalRequestCommand.of(List.of("1", "2", "3"), null));

		verify(actionLogService).record(eq(ActionLogConstants.PRODUCT_APPROVAL_REQUEST),
			eq(MarketType.COUPANG.name()), eq(ActionStatus.SUCCESS),
			contains("1"));
		verify(actionLogService).record(eq(ActionLogConstants.PRODUCT_APPROVAL_REQUEST),
			eq(MarketType.COUPANG.name()), eq(ActionStatus.FAILED),
			contains("3"));
		verify(actionLogService, times(2)).record(eq(ActionLogConstants.PRODUCT_APPROVAL_REQUEST),
			anyString(), any(), anyString());
	}

	@Test
	@DisplayName("D-215: 건 사이에 쓰로틀만큼 쉬고, 마지막 건 뒤에는 쉬지 않는다")
	void throttlesBetweenItems() {
		when(coupangClient.requestApproval(anyString()))
			.thenReturn(MarketApprovalResult.requested("x", "임시저장", "SUCCESS", "성공"));
		long throttle = MarketApprovalRequestCommand.MIN_THROTTLE_MS;

		long singleStarted = System.currentTimeMillis();
		service.request(MarketType.COUPANG, MarketApprovalRequestCommand.of(List.of("1"), null));
		long singleElapsed = System.currentTimeMillis() - singleStarted;

		long pairStarted = System.currentTimeMillis();
		service.request(MarketType.COUPANG, MarketApprovalRequestCommand.of(List.of("1", "2"), null));
		long pairElapsed = System.currentTimeMillis() - pairStarted;

		assertThat(singleElapsed).isLessThan(throttle);
		assertThat(pairElapsed).isGreaterThanOrEqualTo(throttle);
	}
}
