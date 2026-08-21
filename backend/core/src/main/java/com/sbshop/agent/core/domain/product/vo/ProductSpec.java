package com.sbshop.agent.core.domain.product.vo;

import com.sbshop.agent.core.domain.product.enums.MeasureUnit;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.math.BigDecimal;
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
public class ProductSpec {
	@Column(name = "barcode", length = 100)
	private String barcode;

	@Column(name = "capacity", precision = 10, scale = 2)
	private BigDecimal capacity;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(Types.VARCHAR)
	@Column(name = "measure_unit", length = 50)
	private MeasureUnit measureUnit;
}
