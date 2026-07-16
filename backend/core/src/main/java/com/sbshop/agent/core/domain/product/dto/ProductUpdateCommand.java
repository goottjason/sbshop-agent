package com.sbshop.agent.core.domain.product.dto;

import com.sbshop.agent.core.domain.product.enums.MeasureUnit;
import com.sbshop.agent.core.domain.product.enums.ProductCategory;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import java.math.BigDecimal;
import java.util.List;
import lombok.Builder;

/**
 * 상품 수정 커맨드(26필드). 대부분의 호출부는 1~4개 필드만 채우므로 위치기반 생성자는
 * 오배치 위험이 크다(F-PROD-10/14). 명시적 필드 지정을 위해 {@link Builder}를 제공한다 —
 * 미지정 필드는 null(모든 필드 객체타입)이라 종전 위치기반 null 채움과 동작이 동일하다.
 */
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
}
