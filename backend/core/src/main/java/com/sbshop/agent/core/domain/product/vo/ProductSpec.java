package com.sbshop.agent.core.domain.product.vo;

import com.sbshop.agent.core.domain.product.enums.MeasureUnit;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import java.sql.Types;

@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductSpec {
	@Column(name = "capacity", precision = 10, scale = 2)
	private BigDecimal capacity;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(Types.VARCHAR)
	@Column(name = "measure_unit", length = 20)
	private MeasureUnit measureUnit;

	@Column(name = "weight", precision = 10, scale = 2)
	private BigDecimal weight;

	@Column(name = "bundle_quantity")
	private Integer bundleQuantity;

	@Builder
	public ProductSpec(
		BigDecimal capacity, MeasureUnit measureUnit, BigDecimal weight, Integer bundleQuantity) {
		this.capacity = capacity;
		this.measureUnit = measureUnit;
		this.weight = weight;
		this.bundleQuantity = bundleQuantity;
	}
}
