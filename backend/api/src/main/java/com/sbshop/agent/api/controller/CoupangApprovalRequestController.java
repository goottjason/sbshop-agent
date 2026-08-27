package com.sbshop.agent.api.controller;

import com.sbshop.agent.api.dto.market.CoupangApprovalRequest;
import com.sbshop.agent.core.application.market.MarketApprovalRequestService;
import com.sbshop.agent.core.application.market.dto.MarketApprovalReport;
import com.sbshop.agent.core.application.market.dto.MarketApprovalRequestCommand;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1/products/coupang")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class CoupangApprovalRequestController {

	private final MarketApprovalRequestService marketApprovalRequestService;

	@PostMapping("/approval-requests")
	public ResponseEntity<MarketApprovalReport> requestApprovals(
		@RequestBody(required = false)
		CoupangApprovalRequest request) {
		MarketApprovalRequestCommand command = MarketApprovalRequestCommand.of(
			request == null ? null : request.sellerProductIds(),
			request == null ? null : request.throttleMs());
		log.info("[승인요청] 쿠팡 표본 승인 요청 {}건 (throttleMs={})",
			command.marketItemIds().size(), command.throttleMs());
		return ResponseEntity.ok(marketApprovalRequestService.request(MarketType.COUPANG, command));
	}
}
