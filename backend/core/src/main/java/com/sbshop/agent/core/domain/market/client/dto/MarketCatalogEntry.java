package com.sbshop.agent.core.domain.market.client.dto;

import java.util.Map;

public record MarketCatalogEntry(String sellerCode, Map<String, String> identifiers, String status) {
}
