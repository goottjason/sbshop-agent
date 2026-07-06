package com.sbshop.agent.core.domain.product.vo;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@Builder(toBuilder = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class LogisticsInfo {

	@Column(name = "stock", nullable = false)
	private Integer stock;

	@Column(name = "weight", precision = 10, scale = 2)
	private BigDecimal weight;

	@Column(name = "bundle_quantity")
	private Integer bundleQuantity;
}
