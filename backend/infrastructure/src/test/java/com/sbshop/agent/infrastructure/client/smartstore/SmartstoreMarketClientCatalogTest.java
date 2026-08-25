package com.sbshop.agent.infrastructure.client.smartstore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.market.client.dto.MarketCatalogEntry;
import com.sbshop.agent.infrastructure.client.smartstore.adapter.SmartstoreMarketClient;
import com.sbshop.agent.infrastructure.client.smartstore.client.SmartstoreRestClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SmartstoreMarketClientCatalogTest {

	@Mock
	private SmartstoreRestClient restClient;

	private SmartstoreMarketClient client;

	@BeforeEach
	void setUp() {
		client = new SmartstoreMarketClient(null, null, null, null, restClient, new ObjectMapper());
	}

	@Test
	@DisplayName("카탈로그: sellerManagementCode·originProductNo·channelProductNo·statusType 를 한 번의 순회로 담는다")
	void fetchCatalog_mapsSellerCodeIdentifiersAndStatus() {
		when(restClient.post(eq("/v1/products/search"), any())).thenReturn(
			"{\"last\":true,\"contents\":[{\"originProductNo\":6321468668,\"channelProducts\":["
				+ "{\"channelProductNo\":6351684748,\"channelServiceType\":\"STOREFARM\","
				+ "\"sellerManagementCode\":\"220227IHB052\",\"statusType\":\"SUSPENSION\"}]}]}");

		List<MarketCatalogEntry> entries = client.fetchCatalog(0L);

		assertThat(entries).hasSize(1);
		MarketCatalogEntry entry = entries.get(0);
		assertThat(entry.sellerCode()).isEqualTo("220227IHB052");
		assertThat(entry.identifiers())
			.containsEntry("originProductNo", "6321468668")
			.containsEntry("channelProductNo", "6351684748");
		assertThat(entry.status()).isEqualTo("SUSPENSION");
	}

	@Test
	@DisplayName("카탈로그: last=false 인 동안 page 를 올리며 순회하고 last=true 에서 종료한다")
	void fetchCatalog_paginatesUntilLast() {
		when(restClient.post(eq("/v1/products/search"), any()))
			.thenReturn("{\"last\":false,\"contents\":[{\"originProductNo\":1,\"channelProducts\":["
				+ "{\"channelProductNo\":11,\"channelServiceType\":\"STOREFARM\","
				+ "\"sellerManagementCode\":\"A\",\"statusType\":\"SALE\"}]}]}")
			.thenReturn("{\"last\":true,\"contents\":[{\"originProductNo\":2,\"channelProducts\":["
				+ "{\"channelProductNo\":22,\"channelServiceType\":\"STOREFARM\","
				+ "\"sellerManagementCode\":\"B\",\"statusType\":\"SALE\"}]}]}");

		List<MarketCatalogEntry> entries = client.fetchCatalog(0L);

		assertThat(entries).extracting(MarketCatalogEntry::sellerCode).containsExactly("A", "B");
		ArgumentCaptor<Object> bodies = ArgumentCaptor.forClass(Object.class);
		verify(restClient, times(2)).post(eq("/v1/products/search"), bodies.capture());
		assertThat(asMap(bodies.getAllValues().get(0))).containsEntry("page", 1).containsEntry("size", 500);
		assertThat(asMap(bodies.getAllValues().get(1))).containsEntry("page", 2).containsEntry("size", 500);
	}

	@Test
	@DisplayName("카탈로그: contents 가 비면 즉시 종료하고 빈 목록을 반환한다")
	void fetchCatalog_emptyContentsStopsImmediately() {
		when(restClient.post(eq("/v1/products/search"), any()))
			.thenReturn("{\"last\":false,\"contents\":[]}");

		assertThat(client.fetchCatalog(0L)).isEmpty();
		verify(restClient, times(1)).post(eq("/v1/products/search"), any());
	}

	@Test
	@DisplayName("카탈로그: sellerManagementCode 가 없으면 sellerCode 는 null 이고 식별자는 그대로 담는다")
	void fetchCatalog_missingSellerCodeIsNull() {
		when(restClient.post(eq("/v1/products/search"), any())).thenReturn(
			"{\"last\":true,\"contents\":[{\"originProductNo\":7,\"channelProducts\":["
				+ "{\"channelProductNo\":77,\"channelServiceType\":\"STOREFARM\",\"statusType\":\"SALE\"}]}]}");

		MarketCatalogEntry entry = client.fetchCatalog(0L).get(0);

		assertThat(entry.sellerCode()).isNull();
		assertThat(entry.identifiers()).containsEntry("originProductNo", "7");
	}

	@Test
	@DisplayName("카탈로그: 조회 중 실패하면 부분 결과를 반환하지 않고 예외를 던진다")
	void fetchCatalog_failurePropagatesInsteadOfPartialResult() {
		when(restClient.post(eq("/v1/products/search"), any()))
			.thenReturn("{\"last\":false,\"contents\":[{\"originProductNo\":1,\"channelProducts\":["
				+ "{\"channelProductNo\":11,\"channelServiceType\":\"STOREFARM\","
				+ "\"sellerManagementCode\":\"A\",\"statusType\":\"SALE\"}]}]}")
			.thenThrow(new RuntimeException("502 Bad Gateway"));

		assertThatThrownBy(() -> client.fetchCatalog(0L))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("전체 상품 조회 실패");
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> asMap(Object body) {
		return (Map<String, Object>)body;
	}

	@Test
	@DisplayName("카탈로그: sellerManagementCode 앞뒤 공백을 제거해 SB코드 조인이 깨지지 않게 한다")
	void fetchCatalog_trimsSellerCodeAndIdentifiers() {
		when(restClient.post(eq("/v1/products/search"), any())).thenReturn(
			"{\"last\":true,\"contents\":[{\"originProductNo\":\" 6321468668 \",\"channelProducts\":["
				+ "{\"channelProductNo\":\" 6351684748 \",\"channelServiceType\":\"STOREFARM\","
				+ "\"sellerManagementCode\":\"  220227IHB052  \",\"statusType\":\" SUSPENSION \"}]}]}");

		MarketCatalogEntry entry = client.fetchCatalog(0L).get(0);

		assertThat(entry.sellerCode()).isEqualTo("220227IHB052");
		assertThat(entry.identifiers())
			.containsEntry("originProductNo", "6321468668")
			.containsEntry("channelProductNo", "6351684748");
		assertThat(entry.status()).isEqualTo("SUSPENSION");
	}

	@Test
	@DisplayName("카탈로그: 페이지 상한을 소진하면 잘린 목록을 반환하지 않고 예외를 던진다")
	void fetchCatalog_pageCapExhaustionThrowsInsteadOfTruncating() {
		when(restClient.post(eq("/v1/products/search"), any())).thenReturn(
			"{\"last\":false,\"contents\":[{\"originProductNo\":1,\"channelProducts\":["
				+ "{\"channelProductNo\":11,\"channelServiceType\":\"STOREFARM\","
				+ "\"sellerManagementCode\":\"A\",\"statusType\":\"SALE\"}]}]}");

		assertThatThrownBy(() -> client.fetchCatalog(0L))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("페이지 상한");
	}
}
