package com.sbshop.agent.api.controller;

import com.sbshop.agent.core.application.dashboard.DashboardService;
import com.sbshop.agent.core.application.dashboard.dto.DashboardDtos.AttentionResponse;
import com.sbshop.agent.core.application.dashboard.dto.DashboardDtos.BreakdownItem;
import com.sbshop.agent.core.application.dashboard.dto.DashboardDtos.Dimension;
import com.sbshop.agent.core.application.dashboard.dto.DashboardDtos.SummaryResponse;
import com.sbshop.agent.core.application.dashboard.dto.DashboardDtos.TimeseriesBucket;
import com.sbshop.agent.core.application.dashboard.dto.DashboardDtos.Unit;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class DashboardController {

	private final DashboardService dashboardService;

	@GetMapping("/summary")
	public SummaryResponse summary(
		@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
		LocalDateTime start,
		@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
		LocalDateTime end) {
		return dashboardService.summary(start, end);
	}

	@GetMapping("/timeseries")
	public List<TimeseriesBucket> timeseries(
		@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
		LocalDateTime start,
		@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
		LocalDateTime end,
		@RequestParam
		Unit unit) {
		return dashboardService.timeseries(start, end, unit);
	}

	@GetMapping("/breakdown")
	public List<BreakdownItem> breakdown(
		@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
		LocalDateTime start,
		@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
		LocalDateTime end,
		@RequestParam
		Dimension dimension,
		@RequestParam(defaultValue = "10")
		int limit) {
		return dashboardService.breakdown(start, end, dimension, limit);
	}

	@GetMapping("/attention")
	public AttentionResponse attention() {
		return dashboardService.attention(LocalDateTime.now());
	}
}
