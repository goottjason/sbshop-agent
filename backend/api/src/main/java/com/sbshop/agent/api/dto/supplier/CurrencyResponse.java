package com.sbshop.agent.api.dto.supplier;

import com.sbshop.agent.core.domain.supplier.Currency;
import java.math.BigDecimal;

/**
 * F-SUP-LC-1: GET /currencies 응답 DTO. Currency 엔티티를 직접 노출하지 않고
 * 현재 직렬화 형태(currencyCode·exchangeRate)를 그대로 미러한다.
 */
public record CurrencyResponse(
	String currencyCode,
	BigDecimal exchangeRate) {

	public static CurrencyResponse from(Currency c) {
		return new CurrencyResponse(c.getCurrencyCode(), c.getExchangeRate());
	}
}
