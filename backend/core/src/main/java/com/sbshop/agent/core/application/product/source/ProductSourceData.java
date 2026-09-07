package com.sbshop.agent.core.application.product.source;

import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.enums.*;
import com.sbshop.agent.core.domain.pricing.VendorPricePolicy;
import java.math.BigDecimal;
import java.util.List;

public final class ProductSourceData {
	private ProductSourceData() {}

	public enum Field {
		PRICE, STOCK
	}
	public record Values(BigDecimal costPrice, BigDecimal exchangeRate, StockStatus stockStatus, Integer stock) {
		public static Values from(Product p) {
			return new Values(p.getPriceInfo() == null ? null : p.getPriceInfo().getCostPrice(),
				p.getPriceInfo() == null ? null : p.getPriceInfo().getExchangeRate(), p.getStockStatus(), p.getStock());
		}
	}
	public record Shipping(VendorType vendor, String currency, BigDecimal base, Integer baseGrams,
		BigDecimal step, Integer stepGrams) {
		public static Shipping from(VendorPricePolicy p) {
			return p == null ? null : new Shipping(p.getVendor(), p.getShipCurrency(), p.getShipBaseAmount(),
				p.getShipBaseWeightG(), p.getShipStepAmount(), p.getShipStepWeightG());
		}

		public VendorPricePolicy policy() {
			return VendorPricePolicy.builder().vendor(vendor).shipCurrency(currency)
				.shipBaseAmount(base).shipBaseWeightG(baseGrams).shipStepAmount(step).shipStepWeightG(stepGrams)
				.build();
		}
	}
	public record Captured(Values current, BigDecimal weightKg, Integer bundleQuantity, Shipping shipping) {
	}
	/** Exact catalog price and FX evidence captured before review; no raw source page is retained. */
	public record PricingEvidence(BigDecimal sourcePrice, String currency, BigDecimal observedExchangeRate,
		BigDecimal normalizedExchangeRate, BigDecimal goodsPriceKrw) {
	}
	public record Proposed(Values values, boolean priceAvailable, boolean stockAvailable, List<String> notices,
		PricingEvidence pricingEvidence) {
		public Proposed(Values values, boolean priceAvailable, boolean stockAvailable, List<String> notices) {
			this(values, priceAvailable, stockAvailable, notices, null);
		}
	}
	public record Observed(BigDecimal goodsPriceKrw, BigDecimal exchangeRate, String currency,
		StockStatus stockStatus, Integer stock, List<String> notices, PricingEvidence pricingEvidence) {
		public Observed(BigDecimal goodsPriceKrw, BigDecimal exchangeRate, String currency,
			StockStatus stockStatus, Integer stock, List<String> notices) {
			this(goodsPriceKrw, exchangeRate, currency, stockStatus, stock, notices, null);
		}
	}
}
