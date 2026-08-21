package com.sbshop.agent.api.dto.supplier;

import com.sbshop.agent.core.domain.supplier.Currency;
import java.math.BigDecimal;

public record CurrencyResponse(
	String currencyCode,
	BigDecimal exchangeRate) {

	public static CurrencyResponse from(Currency c) {
		return new CurrencyResponse(c.getCurrencyCode(), c.getExchangeRate());
	}
}
