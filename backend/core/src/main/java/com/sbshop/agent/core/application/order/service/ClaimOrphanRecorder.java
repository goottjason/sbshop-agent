package com.sbshop.agent.core.application.order.service;

import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.domain.actionlog.enums.ActionStatus;
import com.sbshop.agent.core.domain.actionlog.repository.ActionLogRepository;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ClaimOrphanRecorder {

	private static final int MAX_LISTED = 50;

	private final ActionLogService actionLogService;
	private final ActionLogRepository actionLogRepository;

	public void record(MarketType market, Collection<String> orphanOrderNos) {
		if (market == null || orphanOrderNos == null || orphanOrderNos.isEmpty()) {
			return;
		}
		String actionType = market.name() + "_CLAIM_ORPHAN";
		LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
		if (actionLogRepository.countTodayByActionType(actionType, startOfDay) > 0) {
			return;
		}
		List<String> listed = orphanOrderNos.stream().limit(MAX_LISTED).toList();
		String suffix = orphanOrderNos.size() > MAX_LISTED
			? " 외 " + (orphanOrderNos.size() - MAX_LISTED) + "건" : "";
		String message = "클레임 목록에는 있는데 우리 주문이 없다 — " + orphanOrderNos.size() + "건: "
			+ String.join(", ", listed) + suffix
			+ " · 수집 전에 취소된 주문이라 저절로 들어오지 않는다";
		actionLogService.record(actionType, market.name(), ActionStatus.WARNING, message);
		log.warn("[{}] {}", market, message);
	}
}
