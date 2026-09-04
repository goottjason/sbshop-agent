package com.sbshop.agent.api.controller;

import com.sbshop.agent.core.application.product.port.BrandLookupOutcome;
import com.sbshop.agent.core.application.product.port.CoupangBrandLookupPort;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/coupang")
@RequiredArgsConstructor
public class CoupangBrandLookupController {

	private final CoupangBrandLookupPort coupangBrandLookupPort;

	@GetMapping("/brand")
	public ResponseEntity<Map<String, Object>> lookup(@RequestParam
	String name) {
		BrandLookupOutcome outcome = coupangBrandLookupPort.findOfficialBrandName(name);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("success", true);
		body.put("keyword", name);
		body.put("status", outcome.status().name());
		body.put("matched", outcome.isMatched());
		body.put("officialBrandName", outcome.officialBrandName());
		body.put("candidates", outcome.candidates());
		return ResponseEntity.ok(body);
	}

	@GetMapping("/brand/enrolled")
	public ResponseEntity<Map<String, Object>> enrolled() {
		List<String> names = coupangBrandLookupPort.enrolledBrandNames();
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("success", true);
		body.put("count", names.size());
		body.put("brands", names);
		return ResponseEntity.ok(body);
	}
}
