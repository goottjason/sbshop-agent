package com.sbshop.agent.core.application.product.batch;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.product.source.ProductSourceSnapshot;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class BatchSourceDiagnosisTest {
	private final ObjectMapper mapper = new ObjectMapper();

	private ProductSourceSnapshot snapshot(String url) {
		return new ProductSourceSnapshot("s", "c", 1L, "SB", 1L, "f", url, "IHB", Instant.EPOCH, "{}");
	}

	@Test
	void retainedPriceAndMissingBundleExplainCalculationFailureRatherThanDeletion() {
		var s = snapshot("https://kr.iherb.com/pr/product/8435");
		s.complete("""
			{"values":{"stockStatus":"IN_STOCK"},"priceAvailable":false,"stockAvailable":true,
			 "notices":["묶음수량이 없어 개당 원가를 계산할 수 없습니다."],
			 "pricingEvidence":{"sourcePrice":12525,"currency":"KRW"}}
			""", false, true, Instant.EPOCH);
		var d = BatchSourceDiagnosis.from(s, mapper);
		assertThat(d.code()).isEqualTo("COST_CALCULATION_FAILED");
		assertThat(d.sourcePrice()).isEqualByComparingTo("12525");
		assertThat(d.stockStatus()).isEqualTo("IN_STOCK");
		assertThat(d.notices()).containsExactly("묶음수량이 없어 개당 원가를 계산할 수 없습니다.");
	}

	@Test
	void notFoundDoesNotAssertDeletionOrDiscontinuation() {
		var s = snapshot("https://kr.iherb.com/pr/product/8435");
		s.fail(ProductSourceSnapshot.State.FAILED, "[SOURCE_HTTP_FAILED] HTTP 404");
		var d = BatchSourceDiagnosis.from(s, mapper);
		assertThat(d.code()).isEqualTo("SOURCE_NOT_FOUND");
		assertThat(d.action()).contains("단정하지 않습니다");
	}

	@Test
	void invalidUrlIsDistinctFromConnectionFailure() {
		var s = snapshot("https://kr.iherb.com/not-a-product");
		s.fail(ProductSourceSnapshot.State.FAILED, "실패");
		assertThat(BatchSourceDiagnosis.from(s, mapper).code()).isEqualTo("INVALID_SOURCE_URL");
	}

	@Test
	void corruptOldEvidenceDoesNotBreakDetailView() {
		var s = snapshot("https://kr.iherb.com/pr/product/8435");
		s.complete("not-json", false, true, Instant.EPOCH);
		assertThat(BatchSourceDiagnosis.from(s, mapper).code()).isEqualTo("UNCONFIRMED");
	}

	@Test
	void forbiddenResponseIsNotMissingProduct() {
		var s = snapshot("https://kr.iherb.com/pr/product/8435");
		s.fail(ProductSourceSnapshot.State.FAILED, "HTTP 403");
		assertThat(BatchSourceDiagnosis.from(s, mapper).code()).isEqualTo("ACCESS_DENIED");
	}

	@Test
	void queuedWorkIsNotDiagnosedAsFailure() {
		assertThat(BatchSourceDiagnosis.from(snapshot("https://kr.iherb.com/pr/product/8435"), mapper)).isNull();
	}
	@Test void explicitSourceProblemsHaveOneConciseReason() {
		var s = snapshot("https://kr.iherb.com/pr/product/8435");
		s.fail(ProductSourceSnapshot.State.FAILED, "[SOURCE_DISCONTINUED] 생산 중단으로 더 이상 구매할 수 없는 상품입니다.");
		assertThat(BatchSourceDiagnosis.from(s, mapper).summary()).isEqualTo("생산 중단으로 더 이상 구매할 수 없는 상품입니다.");
		s.fail(ProductSourceSnapshot.State.FAILED, "[SOURCE_PRICE_ZERO] 소싱처 가격이 0원인 비정상 상품입니다.");
		assertThat(BatchSourceDiagnosis.from(s, mapper).summary()).isEqualTo("소싱처 가격이 0원인 비정상 상품입니다.");
		assertThat(BatchSourceDiagnosis.from(s, mapper).sourcePrice()).isEqualByComparingTo("0");
	}

}
