package com.sbshop.agent.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * F-ORD-11/20: confirm/batch·cancel/batch 요청을 Map 대신 OrderIdsRequest record 로 바인딩한다.
 * 프론트가 보내는 JSON 계약({"orderIds":[...]})이 record 로 그대로 역직렬화돼야 한다(계약 보존).
 */
class OrderIdsRequestContractTest {

	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	@DisplayName("프론트 JSON 바디 {\"orderIds\":[1,2]} 가 record 로 그대로 역직렬화된다")
	void deserializesFrontendBody() throws Exception {
		OrderIdsRequest req = mapper.readValue("{\"orderIds\":[1,2]}", OrderIdsRequest.class);

		assertThat(req.orderIds()).containsExactly(1L, 2L);
	}

	@Test
	@DisplayName("빈 목록 바디도 역직렬화된다(빈 리스트)")
	void deserializesEmptyList() throws Exception {
		OrderIdsRequest req = mapper.readValue("{\"orderIds\":[]}", OrderIdsRequest.class);

		assertThat(req.orderIds()).isEmpty();
	}

	@Test
	@DisplayName("orderIds 키 누락 시 null(기존 Map.get(\"orderIds\") 과 동일 동작)")
	void missingKeyYieldsNull() throws Exception {
		OrderIdsRequest req = mapper.readValue("{}", OrderIdsRequest.class);

		assertThat(req.orderIds()).isNull();
	}
}
