package com.sbshop.agent.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.product.port.BrandLookupOutcome;
import com.sbshop.agent.core.application.product.port.CoupangBrandLookupPort;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class CoupangBrandLookupControllerTest {

	@Mock
	private CoupangBrandLookupPort coupangBrandLookupPort;

	@Test
	@DisplayName("D-261: 매칭되면 공식 브랜드명과 matched=true 를 돌려준다")
	void matched_returnsOfficialBrandName() {
		when(coupangBrandLookupPort.findOfficialBrandName("Comvita")).thenReturn(BrandLookupOutcome.matched("콤비타"));
		CoupangBrandLookupController controller = new CoupangBrandLookupController(coupangBrandLookupPort);

		ResponseEntity<Map<String, Object>> res = controller.lookup("Comvita");

		assertThat(res.getBody()).containsEntry("success", true)
			.containsEntry("keyword", "Comvita")
			.containsEntry("status", "MATCHED")
			.containsEntry("matched", true)
			.containsEntry("officialBrandName", "콤비타");
	}

	@Test
	@DisplayName("D-261: 매칭되지 않으면 matched=false, officialBrandName=null 을 돌려준다")
	void unmatched_returnsNullBrandName() {
		when(coupangBrandLookupPort.findOfficialBrandName("Four")).thenReturn(BrandLookupOutcome.notRegistered());
		CoupangBrandLookupController controller = new CoupangBrandLookupController(coupangBrandLookupPort);

		ResponseEntity<Map<String, Object>> res = controller.lookup("Four");

		assertThat(res.getBody()).containsEntry("success", true)
			.containsEntry("status", "NOT_REGISTERED")
			.containsEntry("matched", false)
			.containsEntry("officialBrandName", null);
	}

	@Test
	@DisplayName("D-261 후속: 조회 자체가 실패하면 status=LOOKUP_FAILED 로 구분한다 — 없음과 모름은 다르다")
	void lookupFailed_returnsDistinctStatus() {
		when(coupangBrandLookupPort.findOfficialBrandName("Comvita")).thenReturn(BrandLookupOutcome.lookupFailed());
		CoupangBrandLookupController controller = new CoupangBrandLookupController(coupangBrandLookupPort);

		ResponseEntity<Map<String, Object>> res = controller.lookup("Comvita");

		assertThat(res.getBody()).containsEntry("success", true)
			.containsEntry("status", "LOOKUP_FAILED")
			.containsEntry("matched", false)
			.containsEntry("officialBrandName", null);
	}
}
