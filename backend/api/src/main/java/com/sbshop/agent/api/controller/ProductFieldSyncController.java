package com.sbshop.agent.api.controller;

import com.sbshop.agent.core.application.product.ProductFieldSyncUseCase;
import com.sbshop.agent.core.domain.market.client.dto.MarketEditField;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class ProductFieldSyncController {

	private final ProductFieldSyncUseCase useCase;
	private final com.sbshop.agent.core.application.product.ProductFieldSyncBatchService batchService;
	private final com.sbshop.agent.core.application.process.ProcessStatusService processStatusService;

	public record FieldSyncRequest(List<String> fields, List<String> markets) {
	}

	@PostMapping("/{id}/field-sync")
	public ResponseEntity<Map<String, Object>> syncFields(@PathVariable
	Long id, @RequestBody
	FieldSyncRequest request) {
		if (request.fields() == null || request.fields().isEmpty()
			|| request.markets() == null || request.markets().isEmpty()) {
			return ResponseEntity.badRequest()
				.body(Map.of("success", false, "message", "fields 와 markets 는 비울 수 없습니다"));
		}
		Set<MarketEditField> fields = EnumSet.noneOf(MarketEditField.class);
		for (String f : request.fields()) {
			try {
				fields.add(MarketEditField.valueOf(f));
			} catch (IllegalArgumentException | NullPointerException e) {
				return ResponseEntity.badRequest()
					.body(Map.of("success", false, "message", "알 수 없는 필드: " + f));
			}
		}
		Set<MarketType> markets = EnumSet.noneOf(MarketType.class);
		for (String m : request.markets()) {
			try {
				markets.add(MarketType.valueOf(m));
			} catch (IllegalArgumentException | NullPointerException e) {
				return ResponseEntity.badRequest()
					.body(Map.of("success", false, "message", "알 수 없는 마켓: " + m));
			}
		}
		return reviewRequired();
	}

	public record BatchFieldSyncRequest(List<String> fields, List<String> markets, Integer limit) {
	}

	@PostMapping("/batch/field-sync")
	public ResponseEntity<Map<String, Object>> batchSyncFields(@RequestBody
	BatchFieldSyncRequest request) {
		return syncFields(null, new FieldSyncRequest(request.fields(), request.markets()));
	}

	private ResponseEntity<Map<String, Object>> reviewRequired() {
		return ResponseEntity.status(409).body(Map.of("success", false, "code", "FIELD_REVIEW_REQUIRED",
			"message", "상품관리의 필드 반영 검토에서 대상과 전송값을 확인하세요. 기존 즉시 반영 경로는 작업을 만들지 않습니다.",
			"reviewPath", "/api/v1/market-field-sync/reviews"));
	}

}
