package com.sbshop.agent.infrastructure.client.elevenst;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.infrastructure.client.elevenst.adapter.ElevenstMarketClient;
import com.sbshop.agent.infrastructure.client.elevenst.client.ElevenstMarketRestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ElevenstMarketClientDeleteTest {

	@Mock
	private ElevenstMarketRestClient restClient;

	private ElevenstMarketClient client;

	@BeforeEach
	void setUp() {
		client = new ElevenstMarketClient(restClient);
	}

	@Test
	@DisplayName("삭제 응답만으로 완료하지 않고 재조회에 넘긴다")
	void deleteUsesRecordedTransportAndRequiresReadback() {
		when(restClient.deleteRecorded("3264931038"))
			.thenReturn(new ElevenstMarketRestClient.DeleteResponse(200,
				"<Product><resultCode>200</resultCode><message>정상적으로 처리되었습니다.</message></Product>", "trace"));
		assertThatThrownBy(() -> client.deleteFromMarket("3264931038"))
			.hasMessageContaining("삭제 미확인").hasMessageContaining("정상적으로 처리되었습니다.");
		verify(restClient).deleteRecorded("3264931038");
	}

	@Test
	@DisplayName("HTTP 200 안의 업무 거절 사유도 보존한다")
	void businessRejectionRemainsVisible() {
		when(restClient.deleteRecorded(anyString()))
			.thenReturn(new ElevenstMarketRestClient.DeleteResponse(200,
				"<Product><resultCode>500</resultCode><message>주문 이력이 있어 삭제할 수 없습니다.</message></Product>", "trace"));
		assertThatThrownBy(() -> client.deleteFromMarket("3264931038"))
			.hasMessageContaining("코드 500").hasMessageContaining("주문 이력이 있어 삭제할 수 없습니다.");
	}
}
