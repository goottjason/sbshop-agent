package com.sbshop.agent.core.domain.product.dto;

import com.sbshop.agent.core.domain.product.enums.MeasureUnit;
import com.sbshop.agent.core.domain.product.enums.ProductCategory;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import com.sbshop.agent.core.domain.product.vo.ProductWeight;
import java.math.BigDecimal;
import java.util.List;
import lombok.Builder;

@Builder
public record ProductUpdateCommand(
	String brand,
	String name,
	String baseName,
	String originalName,
	ProductCategory category,
	BigDecimal costPrice,
	BigDecimal exchangeRate,
	BigDecimal deliveryFee,
	BigDecimal marginRate,
	BigDecimal couponRate,
	BigDecimal minMarginPrice,
	BigDecimal salePrice,
	Integer stock,
	BigDecimal weight, // kg. 기존 값의 단위 확인·변환은 별도 검토 절차를 따른다.
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
	public ProductUpdateCommand {
		ProductWeight.requireKilograms(weight);
	}
}
