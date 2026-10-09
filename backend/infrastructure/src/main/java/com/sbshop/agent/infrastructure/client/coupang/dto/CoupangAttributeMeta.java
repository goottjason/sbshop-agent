package com.sbshop.agent.infrastructure.client.coupang.dto;

import java.util.List;

public record CoupangAttributeMeta(String typeName, String dataType, List<String> usableUnits, boolean mandatory,
	boolean exposed, String groupNumber) {
}
