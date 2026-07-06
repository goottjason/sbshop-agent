package com.sbshop.agent.core.domain.product.vo;

import com.sbshop.agent.core.domain.product.enums.VendorType;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import java.sql.Types;

@Embeddable
@Getter
@Builder(toBuilder = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class SourcingInfo {

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(Types.VARCHAR)
	@Column(name = "vendor", length = 50)
	private VendorType vendor;

	@Column(name = "sourcing_url", length = 1000)
	private String sourceUrl;

	@Column(name = "manufacturer", length = 100)
	private String manufacturer;

	@Column(name = "origin", length = 100)
	private String origin;

	@Column(name = "hs_code", length = 50)
	private String hsCode;
}
