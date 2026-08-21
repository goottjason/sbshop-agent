package com.sbshop.agent.api.controller;

import com.sbshop.agent.core.application.fee.PricePolicyService;
import com.sbshop.agent.core.domain.fee.PricePolicy;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/price-policy")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class PricePolicyController {

	private final PricePolicyService pricePolicyService;

	@GetMapping
	public ResponseEntity<PricePolicyResponse> getPolicy() {
		return ResponseEntity.ok(PricePolicyResponse.from(pricePolicyService.get()));
	}

	@PutMapping
	public ResponseEntity<PricePolicyResponse> updatePolicy(@RequestBody
	PricePolicyRequest request) {
		PricePolicy saved = pricePolicyService.update(request.marginRate(), request.couponRate(),
			request.minMarginPrice());
		return ResponseEntity.ok(PricePolicyResponse.from(saved));
	}

	public record PricePolicyRequest(BigDecimal marginRate, BigDecimal couponRate,
		BigDecimal minMarginPrice) {
	}

	public record PricePolicyResponse(BigDecimal marginRate, BigDecimal couponRate,
		BigDecimal minMarginPrice) {

		static PricePolicyResponse from(PricePolicy policy) {
			if (policy == null) {
				return new PricePolicyResponse(null, null, null);
			}
			return new PricePolicyResponse(policy.getMarginRate(), policy.getCouponRate(),
				policy.getMinMarginPrice());
		}
	}
}
