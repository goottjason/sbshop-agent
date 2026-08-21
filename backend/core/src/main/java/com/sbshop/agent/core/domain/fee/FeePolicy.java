package com.sbshop.agent.core.domain.fee;

import com.sbshop.agent.core.domain.common.BaseEntity;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import java.sql.Types;

@Entity
@Table(name = "sb_fee_policy")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FeePolicy extends BaseEntity {

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(Types.VARCHAR)
	@Column(name = "market_type", length = 50, nullable = false)
	private MarketType marketType;

	@Column(name = "category_name", length = 100, nullable = false)
	private String categoryName;

	@Column(name = "fee_rate", precision = 5, scale = 2, nullable = false)
	private BigDecimal feeRate;

	@Builder
	public FeePolicy(MarketType marketType, String categoryName, BigDecimal feeRate) {
		this.marketType = marketType;
		this.categoryName = categoryName;
		this.feeRate = feeRate;
	}
}
