package com.sbshop.agent.core.application.supplier.dto;

import java.math.BigDecimal;

public record CreateCurrencyCommand(String currencyCode, BigDecimal exchangeRate) {
}
