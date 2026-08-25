package com.sbshop.agent.api.controller;

import com.sbshop.agent.core.application.market.MarketCatalogReconciliationService;
import com.sbshop.agent.core.application.market.dto.MarketSyncReport;
import com.sbshop.agent.core.application.market.dto.MarketSyncReportRequest;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1/products/market-sync")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class MarketSyncReportController {

	private final MarketCatalogReconciliationService marketCatalogReconciliationService;

	@GetMapping("/report")
	public ResponseEntity<MarketSyncReport> report(
		@RequestParam(required = false)
		String markets,
		@RequestParam(required = false)
		Integer limit,
		@RequestParam(required = false)
		Boolean deep,
		@RequestParam(required = false)
		Integer deepLimit,
		@RequestParam(required = false)
		Long throttleMs) {
		MarketSyncReportRequest request = MarketSyncReportRequest.of(parseMarkets(markets), limit, deep, deepLimit,
			throttleMs);
		log.info("[마켓대조] 리포트 시작: markets={}, limit={}, deep={}, deepLimit={}",
			request.markets(), request.sampleLimit(), request.deep(), request.deepLimit());
		MarketSyncReport report = marketCatalogReconciliationService.reconcile(request);
		log.info("[마켓대조] 리포트 완료: {}ms", report.elapsedMs());
		return ResponseEntity.ok(report);
	}

	private List<MarketType> parseMarkets(String markets) {
		if (markets == null || markets.isBlank()) {
			return List.of();
		}
		List<MarketType> parsed = new ArrayList<>();
		for (String token : Arrays.stream(markets.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList()) {
			parsed.add(toMarketType(token));
		}
		return parsed;
	}

	private MarketType toMarketType(String token) {
		try {
			MarketType market = MarketType.valueOf(token.toUpperCase());
			if (market == MarketType.UNKNOWN) {
				throw new IllegalArgumentException(unsupportedMessage(token));
			}
			return market;
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException(unsupportedMessage(token));
		}
	}

	private String unsupportedMessage(String token) {
		return "지원하지 않는 마켓입니다: " + token + " (가능: "
			+ MarketSyncReportRequest.DEFAULT_MARKETS.stream().map(MarketType::name).collect(Collectors.joining(", "))
			+ ")";
	}
}
