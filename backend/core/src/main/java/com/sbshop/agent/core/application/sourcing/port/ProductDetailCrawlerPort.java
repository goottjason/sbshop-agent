package com.sbshop.agent.core.application.sourcing.port;

import com.sbshop.agent.core.application.sourcing.dto.ProductDetailDto;

/** 상품 상세 크롤 포트 — 통관 게이트와 인리치먼트가 쓴다. */
public interface ProductDetailCrawlerPort {

	/** 실패해도 예외를 던지지 않고 {@code ok=false}인 결과를 돌려준다(호출측이 REVIEW로 승격). */
	ProductDetailDto fetchDetail(String sourceUrl);
}
