package com.sbshop.agent.core.domain.market.sync;

import java.time.Instant;
import lombok.Getter;

@Getter
public class MarketTransferFailure extends RuntimeException {
	private final String code;
	private final Instant retryAfter;

	public MarketTransferFailure(String code, String detail, Instant retryAfter, Throwable cause) {
		super(detail, cause);
		this.code = code;
		this.retryAfter = retryAfter;
	}

	public boolean rateLimited() {
		return code != null && code.startsWith("HTTP_429");
	}
}
