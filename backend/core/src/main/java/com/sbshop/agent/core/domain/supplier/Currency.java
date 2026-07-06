package com.sbshop.agent.core.domain.supplier;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "sb_currency")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Currency {

	@Id
	@Column(name = "currency_code", nullable = false, length = 3)
	private String currencyCode;

	@Column(name = "exchange_rate", nullable = false)
	private BigDecimal exchangeRate;

	public Currency(String currencyCode, BigDecimal exchangeRate) {
		this.currencyCode = currencyCode;
		this.exchangeRate = exchangeRate;
	}
}
