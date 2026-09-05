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

	// 신규 입력은 kg. 이전 상품의 단위는 별도 출처 확인 전까지 미확인이다.
	@Column(name = "weight", precision = ProductWeight.PRECISION, scale = ProductWeight.SCALE)
	private BigDecimal weight;

	@Column(name = "bundle_quantity")
	private Integer bundleQuantity;
}
