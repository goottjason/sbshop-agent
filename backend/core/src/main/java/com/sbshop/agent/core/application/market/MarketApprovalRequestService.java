package com.sbshop.agent.core.application.market;

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
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class MarketApprovalRequestService {

	private final MarketClientRouter marketClientRouter;
	private final ActionLogService actionLogService;

	public MarketApprovalReport request(MarketType market, MarketApprovalRequestCommand command) {
		long started = System.currentTimeMillis();
		MarketClient client = resolveClient(market);
		List<String> targets = command.marketItemIds();
		log.info("[승인요청] {} {}건 시작 (throttleMs={}): {}", market, targets.size(), command.throttleMs(), targets);

		List<MarketApprovalResult> results = new ArrayList<>();
		for (int i = 0; i < targets.size(); i++) {
			String marketItemId = targets.get(i);
			MarketApprovalResult result;
			try {
				result = client.requestApproval(marketItemId);
			} catch (RuntimeException e) {
				log.warn("[승인요청] 처리 중 예외: marketItemId={}, msg={}", marketItemId, e.getMessage());
				result = MarketApprovalResult.failed(marketItemId, null, false, null, e.getMessage(),
					"승인 요청 처리 중 예외가 발생했습니다");
			}
			results.add(result);
			recordActionLog(market, result);
			if (i < targets.size() - 1) {
				sleepQuietly(command.throttleMs());
			}
		}

		MarketApprovalReport report = MarketApprovalReport.of(market, command.throttleMs(),
			System.currentTimeMillis() - started, results);
		log.info("[승인요청] {} 완료 — 호출 {}건 / 성공 {} · 건너뜀 {} · 재시도가능 {} · 실패 {}",
			market, report.called(), report.requested(), report.skipped(), report.retryable(), report.failed());
		return report;
	}

	private MarketClient resolveClient(MarketType market) {
		if (!marketClientRouter.hasClient(market)) {
			throw new IllegalStateException(market + " 마켓 클라이언트가 등록되어 있지 않습니다");
		}
		MarketClient client = marketClientRouter.getClient(market);
		if (!client.supportsApprovalRequest()) {
			throw new IllegalStateException(market + " 는 승인 요청 API를 지원하지 않습니다");
		}
		return client;
	}

	private void recordActionLog(MarketType market, MarketApprovalResult result) {
		if (!result.called()) {
			return;
		}
		ActionStatus status = result.outcome() == MarketApprovalOutcome.REQUESTED
			? ActionStatus.SUCCESS : ActionStatus.FAILED;
		actionLogService.record(ActionLogConstants.PRODUCT_APPROVAL_REQUEST, market.name(), status,
			"승인요청 " + result.outcome() + " marketItemId=" + result.marketItemId()
				+ ", 이전상태=" + result.priorStatus()
				+ ", code=" + result.responseCode() + ", message=" + result.responseMessage());
	}

	private void sleepQuietly(long throttleMs) {
		if (throttleMs <= 0) {
			return;
		}
		try {
			Thread.sleep(throttleMs);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("승인 요청이 중단되었습니다", e);
		}
	}
}
