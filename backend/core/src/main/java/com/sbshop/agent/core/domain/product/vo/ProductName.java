package com.sbshop.agent.core.domain.product.vo;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductName {
	@Column(name = "brand", length = 100)
	private String brand;

	@Column(name = "original_name", length = 255)
	private String originalName;

	@Column(name = "base_name", length = 255)
	private String baseName;

	@Column(name = "product_name", nullable = false, length = 255)
	private String productName;

	@Builder
	public ProductName(String brand, String originalName, String baseName, String productName) {
		this.brand = brand;
		this.originalName = originalName;
		this.baseName = baseName;
		this.productName = productName;
	}
}
