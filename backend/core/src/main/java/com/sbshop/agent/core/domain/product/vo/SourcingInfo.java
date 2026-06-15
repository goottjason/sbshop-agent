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
public class SourcingInfo {
	@Column(name = "sourcing_url", length = 1000)
	private String url;

	@Column(name = "manufacturer", length = 100)
	private String manufacturer;

	@Column(name = "origin", length = 100)
	private String origin;

	@Column(name = "hs_code", length = 50)
	private String hsCode;

	@Column(name = "stock")
	private Integer stock;

	@Builder
	public SourcingInfo(
		String url, String manufacturer, String origin, String hsCode, Integer stock) {
		this.url = url;
		this.manufacturer = manufacturer;
		this.origin = origin;
		this.hsCode = hsCode;
		this.stock = stock;
	}
}
