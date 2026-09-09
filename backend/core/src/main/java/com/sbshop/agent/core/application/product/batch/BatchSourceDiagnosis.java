package com.sbshop.agent.core.application.product.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.product.source.ProductSourceData.Proposed;
import com.sbshop.agent.core.domain.product.source.ProductSourceSnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Explains retained observations; never re-crawls or infers deletion from a failed request. */
public record BatchSourceDiagnosis(String code, String summary, String action, Instant observedAt,
	BigDecimal sourcePrice, String currency, String stockStatus, List<String> notices) {
	public static BatchSourceDiagnosis from(ProductSourceSnapshot snapshot, ObjectMapper mapper) {
		if (snapshot == null || snapshot.getState() == ProductSourceSnapshot.State.QUEUED
			|| snapshot.getState() == ProductSourceSnapshot.State.COLLECTING)
			return null;
		Proposed proposed = null;
		try {
			if (snapshot.getProposed() != null)
				proposed = mapper.readValue(snapshot.getProposed(), Proposed.class);
		} catch (Exception ignored) {
			// Old or incompatible evidence must not prevent access to the original link/history.
		}
		String reason = snapshot.getReason() == null ? "상세 원인이 저장되지 않았습니다." : snapshot.getReason();
		List<String> notices = proposed == null || proposed.notices() == null ? List.of() : proposed.notices();
		var evidence = proposed == null ? null : proposed.pricingEvidence();
		BigDecimal price = evidence == null ? null : evidence.sourcePrice();
		String currency = evidence == null ? null : evidence.currency();
		String stock = proposed == null || proposed.values() == null || proposed.values().stockStatus() == null
			? null : proposed.values().stockStatus().name();
		String code = "UNCONFIRMED", summary = reason.startsWith("[") && reason.contains("]")
			? reason.substring(reason.indexOf(']') + 1).strip() : reason;
		String action = "원본 상품을 열어 확인하세요. 이 기록만으로 삭제·단종 여부를 확정할 수 없습니다.";
		if (proposed != null) {
			if (!proposed.priceAvailable() && price != null) {
				code = "COST_CALCULATION_FAILED";
				summary = notices.stream().filter(n -> !n.startsWith("소싱처의 실제 수량")
					&& !n.startsWith("매입 원가는") && !n.startsWith("품절·재입고")
					&& (n.contains("없") || n.contains("못") || n.contains("않") || n.contains("벗어") || n.contains("유효")))
					.findFirst().orElse("매입 원가를 계산하지 못했습니다.");
				action = "아래 계산 실패 사유에 해당하는 SB 상품 정보·배송비 설정을 수정한 뒤 수집을 재시도하세요.";
			} else if (!proposed.priceAvailable()) {
				code = "PRICE_UNCONFIRMED";
				summary = "소싱처 가격을 확인하지 못했습니다.";
			} else if (!proposed.stockAvailable()) {
				code = "STOCK_UNCONFIRMED";
				summary = "소싱처 재고 상태를 확인하지 못했습니다.";
			} else {
				code = "OBSERVED";
				summary = "가격과 재고 상태를 확인했습니다.";
				action = "아래 값은 이 수집 시점의 기록이며 현재 실시간 상태를 뜻하지 않습니다.";
			}
		} else if (reason.contains("[SOURCE_DISCONTINUED]")) {
			code = "DISCONTINUED";
			summary = "생산 중단으로 더 이상 구매할 수 없는 상품입니다.";
		} else if (reason.contains("[SOURCE_PRICE_ZERO]")) {
			code = "PRICE_ZERO";
			summary = "소싱처 가격이 0원인 비정상 상품입니다.";
			price = BigDecimal.ZERO;
		} else if (reason.contains("HTTP 404") || reason.contains("HTTP 410")) {
			code = "SOURCE_NOT_FOUND";
			summary = "소싱처에서 상품을 찾을 수 없습니다.";
			action = "원본 링크를 확인하세요. 삭제·주소 변경·접근 제한을 구분할 근거가 없어 삭제나 단종으로 단정하지 않습니다.";
		} else if (reason.contains("HTTP 403") || reason.contains("HTTP 401")) {
			code = "ACCESS_DENIED";
			summary = "소싱처가 접속을 차단했습니다.";
		} else if (reason.contains("429")) {
			code = "RATE_LIMITED";
			summary = "소싱처 요청 횟수 제한으로 수집하지 못했습니다.";
			action = "재시도 가능 시간이 지난 뒤 다시 시도하세요. 상품 삭제를 뜻하지 않습니다.";
		} else if (reason.contains("SOURCE_IDENTITY_MISMATCH")) {
			code = "IDENTITY_MISMATCH";
			summary = "저장된 상품 주소와 소싱처 응답의 상품 식별자가 일치하지 않습니다.";
		} else if (reason.contains("SOURCE_REQUEST_FAILED") || reason.contains("시간 제한")) {
			code = "CONNECTION_FAILED";
			summary = "소싱처 접속 실패 또는 응답 시간 초과입니다.";
		}
		try {
			var vendor = com.sbshop.agent.core.domain.product.enums.VendorType.valueOf(snapshot.getVendor());
			if (com.sbshop.agent.core.application.product.content.ProductContentUrls.supports(vendor)) {
				try {
					com.sbshop.agent.core.application.product.content.ProductContentUrls.source(vendor,
						snapshot.getSourceUrl());
				} catch (IllegalArgumentException invalid) {
					code = "INVALID_SOURCE_URL";
					summary = "저장된 원본 주소가 이 소싱처에서 지원하는 상품 URL 형식과 다릅니다.";
					action = "SB 상품의 소싱처와 원본 주소를 확인·수정한 뒤 수집을 재시도하세요.";
				}
			}
		} catch (IllegalArgumentException | NullPointerException ignored) {
			// Unsupported/legacy vendor remains unconfirmed.
		}
		return new BatchSourceDiagnosis(code, summary, action,
			snapshot.getCollectedAt() == null ? snapshot.getRequestedAt() : snapshot.getCollectedAt(),
			price, currency, stock, notices);
	}
}
