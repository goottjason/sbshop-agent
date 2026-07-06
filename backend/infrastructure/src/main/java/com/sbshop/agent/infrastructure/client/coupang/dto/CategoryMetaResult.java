package com.sbshop.agent.infrastructure.client.coupang.dto;

import java.util.List;
import lombok.Builder;

@Builder
public record CategoryMetaResult(
		List<CoupangProductPayload.Item.Attribute> attributes,
		List<CoupangProductPayload.Item.Notice> notices) {
}
