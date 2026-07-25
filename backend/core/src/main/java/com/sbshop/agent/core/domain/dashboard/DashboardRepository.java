package com.sbshop.agent.core.domain.dashboard;

import com.sbshop.agent.core.application.dashboard.dto.DashboardDtos.AggRow;
import com.sbshop.agent.core.domain.order.enums.CustomsStatus;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import java.time.LocalDateTime;
import java.util.List;

public interface DashboardRepository {
	List<AggRow> findRowsBetween(LocalDateTime start, LocalDateTime end);
	int countByShippingStatusIn(List<ShippingStatus> statuses);
	int countCustomsIssue(List<CustomsStatus> statuses);
	int countOutOfStock();
	int countDelayed(LocalDateTime newOnOrBefore, LocalDateTime preparingOnOrBefore);
}
