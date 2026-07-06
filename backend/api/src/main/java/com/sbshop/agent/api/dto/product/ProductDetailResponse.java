package com.sbshop.agent.api.dto.product;

import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.enums.ProductCategory;
import com.sbshop.agent.core.domain.product.enums.StockStatus;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import com.sbshop.agent.core.domain.product.vo.LogisticsInfo;
import com.sbshop.agent.core.domain.product.vo.PriceInfo;
import com.sbshop.agent.core.domain.product.vo.ProductSpec;
import com.sbshop.agent.core.domain.product.vo.SourcingInfo;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record ProductDetailResponse(
		Long id,
		String sbCode,
		String brand,
		String productName,
		String baseName,
		String originalName,
		ProductCategory category,
		VendorType vendor,
		PriceInfo priceInfo,
		LogisticsInfo logisticsInfo,
		ProductSpec productSpec,
		SourcingInfo sourcingInfo,
		List<String> sourceImages,
		List<String> hostedImages,
		String searchKeywords,
		String detailHtml,
		String memo,
		StockStatus stockStatus,
		LocalDate restockDate) {

	public static ProductDetailResponse from(Product p) {
		return new ProductDetailResponse(
				p.getId(),
				p.getSbCode(),
				p.getBrand(),
				p.getProductName(),
				p.getBaseName(),
				p.getOriginalName(),
				p.getCategory(),
				p.getVendor(),
				p.getPriceInfo(),
				p.getLogisticsInfo(),
				p.getProductSpec(),
				p.getSourcingInfo(),
				p.getSourceImages(),
				p.getHostedImages(),
				p.getSearchKeywords(),
				p.getDetailHtml(),
				p.getMemo(),
				p.getStockStatus(),
				p.getRestockDate());
	}
}
