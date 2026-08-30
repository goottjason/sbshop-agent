package com.sbshop.agent.core.application.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 마켓 클라이언트가 받는 rawData 에 식별자(sellerProductId 등)가 함께 실려야 한다.
 * 쿠팡 단계 가격조정(D-246)은 sellerProductId 로 현재가를 읽는데, marketDetailedInfo 에는
 * 운영 1,262건 중 20건에만 들어 있고 marketIdentifiers 에는 1,262건 전부 들어 있다.
 */
class SyncPassesIdentifiersTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	private Map<String, Object> merge(String detailedInfo, String identifiers) throws Exception {
		return ProductMarketSyncService.mergeRawDataWithIdentifiers(objectMapper, detailedInfo, identifiers);
	}

	@Test
	@DisplayName("식별자가 rawData 에 합쳐진다 — 상세정보에 없어도 sellerProductId 를 쓸 수 있다")
	void identifiersAreMerged() throws Exception {
		Map<String, Object> merged = merge("{\"statusName\":\"승인완료\"}",
			"{\"sellerProductId\":\"13398738809\",\"vendorItemId\":\"82268240879\"}");

		assertThat(merged).containsEntry("sellerProductId", "13398738809");
		assertThat(merged).containsEntry("statusName", "승인완료");
	}

	@Test
	@DisplayName("상세정보의 값이 식별자보다 우선한다 — 마켓에서 읽어온 최신값을 덮지 않는다")
	void detailedInfoWins() throws Exception {
		Map<String, Object> merged = merge("{\"sellerProductId\":\"NEW\"}",
			"{\"sellerProductId\":\"OLD\"}");

		assertThat(merged).containsEntry("sellerProductId", "NEW");
	}

	@Test
	@DisplayName("어느 쪽이 비어도 나머지를 살린다")
	void tolerantToMissing() throws Exception {
		assertThat(merge(null, "{\"sellerProductId\":\"A\"}")).containsEntry("sellerProductId", "A");
		assertThat(merge("{\"a\":1}", null)).containsEntry("a", 1);
		assertThat(merge(null, null)).isEmpty();
	}

	@Test
	@DisplayName("깨진 JSON 이어도 던지지 않는다 — 동기화 전체를 멈추면 안 된다")
	void tolerantToBrokenJson() throws Exception {
		assertThat(merge("{깨짐", "{\"sellerProductId\":\"A\"}")).containsEntry("sellerProductId", "A");
		assertThat(merge("{\"a\":1}", "{깨짐")).containsEntry("a", 1);
	}
}
