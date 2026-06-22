package com.sbshop.agent.infrastructure.client.coupang;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record CoupangAcceptOrdersRequest(
	@JsonProperty("vendorId") String vendorId,
	@JsonProperty("shipmentBoxIds") List<String> shipmentBoxIds
) {
}
