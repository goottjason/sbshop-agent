package com.sbshop.agent.infrastructure.client.coupang;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.infrastructure.client.coupang.adapter.CoupangMarketClient;
import com.sbshop.agent.infrastructure.client.coupang.client.CoupangRestClient;
import com.sbshop.agent.infrastructure.client.coupang.component.CoupangAttributeValueResolver;
import com.sbshop.agent.infrastructure.client.coupang.component.CoupangCategoryPredictor;
import com.sbshop.agent.infrastructure.client.coupang.component.CoupangMetaService;
import com.sbshop.agent.infrastructure.client.coupang.component.CoupangSearchTagGenerator;
import com.sbshop.agent.infrastructure.client.coupang.config.CoupangProperties;
import com.sbshop.agent.infrastructure.client.coupang.mapper.CoupangDataMapper;
import com.sbshop.agent.infrastructure.client.coupang.parser.CoupangProductParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CoupangVendorIdSourceTest {

	@Mock
	private CoupangProperties properties;
	@Mock
	private CoupangRestClient restClient;
	@Mock
	private CoupangCategoryPredictor categoryPredictor;
	@Mock
	private CoupangProductParser productParser;
	@Mock
	private CoupangSearchTagGenerator searchTagGenerator;
	@Mock
	private CoupangDataMapper dataMapper;
	@Mock
	private CoupangMetaService metaService;

	private CoupangMarketClient client;

	@BeforeEach
	void setUp() {
		client = new CoupangMarketClient(properties, new ObjectMapper(), restClient, categoryPredictor,
			productParser, searchTagGenerator, dataMapper, metaService, new CoupangAttributeValueResolver());
	}

	@Test
	@DisplayName("D-229: 상품 조회의 vendorId 는 env 가 아니라 DB 자격증명에서 온다 — 인증 헤더와 출처가 같아야 한다")
	void extractMarketItem_usesDbVendorId() {
		lenient().when(restClient.resolveVendorId()).thenReturn("A00123456");
		when(restClient.get(org.mockito.ArgumentMatchers.anyString()))
			.thenThrow(new RuntimeException("stop-here"));

		try {
			client.extractMarketItem("11401410095");
		} catch (Exception ignored) {
		}

		ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
		verify(restClient).get(path.capture());
		assertThat(path.getValue()).endsWith("?vendorId=A00123456");
		assertThat(path.getValue()).doesNotEndWith("?vendorId=");
	}
}
