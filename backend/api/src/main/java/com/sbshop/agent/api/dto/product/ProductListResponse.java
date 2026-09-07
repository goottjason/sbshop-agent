package com.sbshop.agent.api.dto.product;

import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.enums.ProductCategory;
import com.sbshop.agent.core.domain.product.enums.StockStatus;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record ProductListResponse(
	Long id,
	String sbCode,
	String brand,
	String productName,
	String baseName,
	String originalName,
	ProductCategory category,
	VendorType vendor,
	BigDecimal salePrice,
	Integer stock,
	Integer bundleQuantity,
	StockStatus stockStatus,
	String repImageUrl,
	List<String> hostedImages,
	String sourcingUrl,
	String memo,
	String sourceGoneReason,
	String sourceGoneAt,
	String lastCrawlError,
	String lastCrawlAt,
	Map<String, MarketBadgeState> marketRegistrations, long pendingChanges,
	com.sbshop.agent.core.domain.product.dto.ProductContentFreshness contentFreshness) {

	public static ProductListResponse from(Product p) {
		return new ProductListResponse(
			p.getId(),
			p.getSbCode(),
			p.getBrand(),
			p.getProductName(),
			p.getBaseName(),
			p.getOriginalName(),
			p.getCategory(),
			p.getVendor(),
			p.getSalePrice(),
			p.getStock(),
			p.getLogisticsInfo() != null ? p.getLogisticsInfo().getBundleQuantity() : null,
			p.getStockStatus(),
			p.getRepImageUrl(),
			p.getHostedImages(),
			p.getSourcingUrl(),
			p.getMemo(),
			p.getSourceGoneReason() != null ? p.getSourceGoneReason().name() : null,
			p.getSourceGoneAt() != null ? p.getSourceGoneAt().toString() : null,
			p.getLastCrawlError(),
			p.getLastCrawlAt() != null ? p.getLastCrawlAt().toString() : null,
			null, 0, com.sbshop.agent.core.domain.product.dto.ProductContentFreshness.EMPTY);
	}

	public static ProductListResponse from(Product p, Map<String, MarketBadgeState> marketRegistrations) {
		return from(p, marketRegistrations, 0);
	}

	public static ProductListResponse from(Product p, Map<String, MarketBadgeState> marketRegistrations,
		long pendingChanges) {
		return from(p, marketRegistrations, pendingChanges,
			com.sbshop.agent.core.domain.product.dto.ProductContentFreshness.EMPTY);
	}

	public static ProductListResponse from(Product p, Map<String, MarketBadgeState> marketRegistrations,
		long pendingChanges, com.sbshop.agent.core.domain.product.dto.ProductContentFreshness contentFreshness) {
		return new ProductListResponse(
			p.getId(),
			p.getSbCode(),
			p.getBrand(),
			p.getProductName(),
			p.getBaseName(),
			p.getOriginalName(),
			p.getCategory(),
			p.getVendor(),
			p.getSalePrice(),
			p.getStock(),
			p.getLogisticsInfo() != null ? p.getLogisticsInfo().getBundleQuantity() : null,
			p.getStockStatus(),
			p.getRepImageUrl(),
			p.getHostedImages(),
			p.getSourcingUrl(),
			p.getMemo(),
			p.getSourceGoneReason() != null ? p.getSourceGoneReason().name() : null,
			p.getSourceGoneAt() != null ? p.getSourceGoneAt().toString() : null,
			p.getLastCrawlError(),
			p.getLastCrawlAt() != null ? p.getLastCrawlAt().toString() : null,
			marketRegistrations, pendingChanges, contentFreshness);
	}
}
