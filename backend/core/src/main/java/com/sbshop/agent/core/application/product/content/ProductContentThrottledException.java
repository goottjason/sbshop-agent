package com.sbshop.agent.core.application.product.content;

import java.time.Instant;

public class ProductContentThrottledException extends RuntimeException {
	private final Instant retryAfter;

	public ProductContentThrottledException() {
		this(null);
	}

	public ProductContentThrottledException(Instant retryAfter) {
		super("소싱처가 요청을 제한했습니다. 최소 5분"
			+ (retryAfter == null ? " 대기 후 새로 수집하세요." : " 및 서버 안내 시각(" + retryAfter + ")까지 대기합니다."));
		this.retryAfter = retryAfter;
	}

	public Instant retryAfter() {
		return retryAfter;
	}
}
