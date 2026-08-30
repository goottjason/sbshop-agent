package com.sbshop.agent.api.dto.product;

import com.sbshop.agent.core.domain.product.dto.ProductUpdateCommand;
import com.sbshop.agent.core.domain.product.enums.MeasureUnit;
import com.sbshop.agent.core.domain.product.enums.ProductCategory;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import java.math.BigDecimal;
import java.util.List;

public record ProductUpdateRequest(
	String brand,
	String name,
	String baseName,
	String originalName,
	ProductCategory category,
	BigDecimal costPrice,
	BigDecimal exchangeRate,
	BigDecimal deliveryFee,
	BigDecimal marginRate,
	BigDecimal salePrice,
	Integer stock,
	BigDecimal weight,
	Integer bundleQuantity,
	String barcode,
	BigDecimal capacity,
	MeasureUnit measureUnit,
	VendorType vendor,
	String sourceUrl,
	String manufacturer,
	String origin,
	String hsCode,
	List<String> sourceImages,
	List<String> hostedImages,
	String searchKeywords,
	String detailHtml,
	String memo) {

	public ProductUpdateCommand toCommand() {
		return new ProductUpdateCommand(
			brand, name, baseName, originalName, category,
			costPrice, exchangeRate, deliveryFee, marginRate, null, null, salePrice,
			stock, weight, bundleQuantity,
			barcode, capacity, measureUnit,
			vendor, sourceUrl, manufacturer, origin, hsCode,
			sourceImages, hostedImages,
			searchKeywords, detailHtml, memo);
	}
}
