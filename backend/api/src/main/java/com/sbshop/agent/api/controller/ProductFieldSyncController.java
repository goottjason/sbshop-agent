package com.sbshop.agent.api.controller;

import com.sbshop.agent.core.application.product.ProductFieldSyncUseCase;
import com.sbshop.agent.core.domain.market.client.dto.MarketEditField;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import java.util.EnumSet;
import java.util.LinkedHashMap;
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
			} catch (IllegalArgumentException e) {
				return ResponseEntity.badRequest()
					.body(Map.of("success", false, "message", "알 수 없는 필드: " + f));
			}
		}
		Set<MarketType> markets = EnumSet.noneOf(MarketType.class);
		for (String m : request.markets()) {
			try {
				markets.add(MarketType.valueOf(m));
			} catch (IllegalArgumentException e) {
				return ResponseEntity.badRequest()
					.body(Map.of("success", false, "message", "알 수 없는 마켓: " + m));
			}
		}
		ProductFieldSyncUseCase.FieldSyncOutcome out = useCase.sync(id, fields, markets);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("success", true);
		body.put("batchId", out.batchId());
		body.put("synced", out.result().synced().stream().map(Enum::name).toList());
		body.put("skipped", out.result().skipped().stream().map(Enum::name).toList());
		Map<String, String> failed = new LinkedHashMap<>();
		out.result().failed().forEach((k, v) -> failed.put(k.name(), v));
		body.put("failed", failed);
		return ResponseEntity.ok(body);
	}

	public record BatchFieldSyncRequest(List<String> fields, List<String> markets, Integer limit) {
	}

	@PostMapping("/batch/field-sync")
	public ResponseEntity<Map<String, Object>> batchSyncFields(@RequestBody
	BatchFieldSyncRequest request) {
		if (request.fields() == null || request.fields().isEmpty()
			|| request.markets() == null || request.markets().isEmpty()) {
			return ResponseEntity.badRequest()
				.body(Map.of("success", false, "message", "fields 와 markets 는 비울 수 없습니다"));
		}
		Set<MarketEditField> fields = EnumSet.noneOf(MarketEditField.class);
		for (String f : request.fields()) {
			fields.add(MarketEditField.valueOf(f));
		}
		Set<MarketType> markets = EnumSet.noneOf(MarketType.class);
		for (String m : request.markets()) {
			markets.add(MarketType.valueOf(m));
		}
		List<Long> targets = batchService.findTargets(request.limit() != null ? request.limit() : 0);
		if (targets.isEmpty()) {
			return ResponseEntity.ok(Map.of("success", true, "count", 0, "message", "대상이 없습니다"));
		}
		String batchId = processStatusService.startBatch(
			com.sbshop.agent.core.domain.process.enums.JobType.FIELD_SYNC,
			targets.stream().map(String::valueOf).toList());
		batchService.runBatch(batchId, targets, fields, markets);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("success", true);
		body.put("batchId", batchId);
		body.put("count", targets.size());
		return ResponseEntity.ok(body);
	}
}
