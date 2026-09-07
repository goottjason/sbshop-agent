package com.sbshop.agent.core.domain.market.client.dto;

import java.util.Map;

/** Separate readback proof. Setup is allowed only for a creation receipt retained by the engine. */
public record VerifiedMarketPublication(boolean verified, Map<String, String> identifiers, String detail,
	boolean approvalPending, boolean setupRequired) {
	public VerifiedMarketPublication {
		identifiers = identifiers == null ? Map.of() : Map.copyOf(identifiers);
		if (verified && (approvalPending || setupRequired || identifiers.isEmpty()))
			throw new IllegalArgumentException("등록 확정에는 심사·설정 완료와 조회된 상품 식별자가 필요합니다.");
		if (approvalPending && setupRequired)
			throw new IllegalArgumentException("심사 대기 중 후처리를 요청할 수 없습니다.");
	}

	public VerifiedMarketPublication(boolean verified, Map<String, String> identifiers, String detail,
		boolean approvalPending) {
		this(verified, identifiers, detail, approvalPending, false);
	}
}
