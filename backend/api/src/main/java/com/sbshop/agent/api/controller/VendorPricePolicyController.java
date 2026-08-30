package com.sbshop.agent.api.controller;

import com.sbshop.agent.core.application.pricing.VendorPricePolicyService;
import com.sbshop.agent.core.domain.pricing.VendorPricePolicy;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/vendor-price-policy")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class VendorPricePolicyController {

	private final VendorPricePolicyService service;

	@GetMapping
	public ResponseEntity<List<VendorPricePolicyResponse>> list() {
		return ResponseEntity.ok(service.findAll().stream()
			.map(VendorPricePolicyResponse::from).toList());
	}

	@GetMapping("/{vendor}")
	public ResponseEntity<VendorPricePolicyResponse> get(@PathVariable
	String vendor) {
		return service.find(VendorType.valueOf(vendor.toUpperCase()))
			.map(VendorPricePolicyResponse::from)
			.map(ResponseEntity::ok)
			.orElseGet(() -> ResponseEntity.notFound().build());
	}

	@PutMapping("/{vendor}")
	public ResponseEntity<VendorPricePolicyResponse> upsert(@PathVariable
	String vendor, @RequestBody
	VendorPricePolicyRequest request) {
		VendorPricePolicy saved = service.upsert(VendorType.valueOf(vendor.toUpperCase()),
			request.marginRate(), request.couponRate(), request.minMarginPrice(),
			request.shipCurrency(), request.shipBaseAmount(), request.shipBaseWeightG(),
			request.shipStepAmount(), request.shipStepWeightG(),
			request.domesticFee(), request.domesticFreeOver());
		return ResponseEntity.ok(VendorPricePolicyResponse.from(saved));
	}

	public record VendorPricePolicyRequest(BigDecimal marginRate, BigDecimal couponRate,
		BigDecimal minMarginPrice, String shipCurrency, BigDecimal shipBaseAmount,
		Integer shipBaseWeightG, BigDecimal shipStepAmount, Integer shipStepWeightG,
		BigDecimal domesticFee, BigDecimal domesticFreeOver) {
	}

	public record VendorPricePolicyResponse(String vendor, BigDecimal marginRate,
		BigDecimal couponRate, BigDecimal minMarginPrice, String shipCurrency,
		BigDecimal shipBaseAmount, Integer shipBaseWeightG, BigDecimal shipStepAmount,
		Integer shipStepWeightG, BigDecimal domesticFee, BigDecimal domesticFreeOver) {

		static VendorPricePolicyResponse from(VendorPricePolicy p) {
			return new VendorPricePolicyResponse(p.getVendor().name(), p.getMarginRate(),
				p.getCouponRate(), p.getMinMarginPrice(), p.getShipCurrency(),
				p.getShipBaseAmount(), p.getShipBaseWeightG(), p.getShipStepAmount(),
				p.getShipStepWeightG(), p.getDomesticFee(), p.getDomesticFreeOver());
		}
	}
}
