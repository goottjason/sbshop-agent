package com.sbshop.agent.api.dto.market;

import java.util.List;

public record CoupangApprovalRequest(List<String> sellerProductIds, Long throttleMs) {
}
