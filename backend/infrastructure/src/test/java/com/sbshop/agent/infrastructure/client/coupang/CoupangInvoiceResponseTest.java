package com.sbshop.agent.infrastructure.client.coupang;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sbshop.agent.infrastructure.client.coupang.CoupangInvoiceResponse.InvoiceResult;
import com.sbshop.agent.infrastructure.client.coupang.CoupangInvoiceResponse.ResponseData;

class CoupangInvoiceResponseTest {

	private InvoiceResult result(boolean succeed, String msg) {
		return new InvoiceResult(1L, succeed, "OK", msg, false);
	}

	@Test
	@DisplayName("봉투 code=200 + 모든 항목 succeed=true → isSuccessful()=true")
	void envelopeOkAllItemsSucceed_isSuccessful() {
		ResponseData data = new ResponseData(200, "OK",
			List.of(result(true, null), result(true, null)));
		CoupangInvoiceResponse res = new CoupangInvoiceResponse("200", "OK", data);

		assertThat(res.isSuccessful()).isTrue();
	}

	@Test
	@DisplayName("봉투 code=200 + 한 항목 succeed=false → isSuccessful()=false, failureReason에 사유 포함")
	void envelopeOkOneItemFails_notSuccessful_reasonSurfaced() {
		ResponseData data = new ResponseData(200, "OK",
			List.of(result(true, null), result(false, "유효하지 않은 송장번호")));
		CoupangInvoiceResponse res = new CoupangInvoiceResponse("200", "OK", data);

		assertThat(res.isSuccessful()).isFalse();
		assertThat(res.failureReason()).contains("유효하지 않은 송장번호");
	}

	@Test
	@DisplayName("봉투 code!=200 → isSuccessful()=false")
	void envelopeNotOk_notSuccessful() {
		CoupangInvoiceResponse res = new CoupangInvoiceResponse("500", "ERROR", null);

		assertThat(res.isSuccessful()).isFalse();
	}

	@Test
	@DisplayName("data/responseList null → 봉투 성공만으로 isSuccessful()=true")
	void nullData_envelopeOnly_isSuccessful() {
		CoupangInvoiceResponse res = new CoupangInvoiceResponse("200", "OK", null);

		assertThat(res.isSuccessful()).isTrue();
	}
}
