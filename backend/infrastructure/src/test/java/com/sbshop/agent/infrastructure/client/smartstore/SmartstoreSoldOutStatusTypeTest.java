package com.sbshop.agent.infrastructure.client.smartstore;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.sbshop.agent.infrastructure.client.smartstore.adapter.SmartstoreMarketClient;

class SmartstoreSoldOutStatusTypeTest {

	private Map<String, Object> normalize(String statusType) {
		Map<String, Object> originProduct = new HashMap<>();
		if (statusType != null) {
			originProduct.put("statusType", statusType);
		}
		ReflectionTestUtils.invokeMethod(SmartstoreMarketClient.class,
			"normalizeReadOnlyStatusType", originProduct);
		return originProduct;
	}

	@Test
	@DisplayName("D-266: OUTOFSTOCK 은 조회 전용 파생값이라 SALE 로 바꿔 보낸다")
	void outOfStockIsReplacedWithSale() {
		assertThat(normalize("OUTOFSTOCK")).containsEntry("statusType", "SALE");
	}

	@Test
	@DisplayName("D-266: statusType 이 비어 있으면 SALE 로 채운다 — 옛 상품은 이 값이 없어 400 이 난다")
	void missingStatusTypeIsFilled() {
		assertThat(normalize(null)).containsEntry("statusType", "SALE");
	}

	@Test
	@DisplayName("D-266: 빈 문자열도 SALE 로 채운다")
	void blankStatusTypeIsFilled() {
		assertThat(normalize("")).containsEntry("statusType", "SALE");
	}

	@Test
	@DisplayName("판매중지 같은 실제 값은 건드리지 않는다 — 우리가 상태를 바꾸지 않는다")
	void realStatusIsKept() {
		assertThat(normalize("SUSPENSION")).containsEntry("statusType", "SUSPENSION");
	}
}
