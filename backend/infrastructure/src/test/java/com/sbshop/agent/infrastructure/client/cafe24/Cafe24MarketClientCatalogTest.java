package com.sbshop.agent.infrastructure.client.cafe24;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.market.client.dto.MarketCatalogEntry;
import com.sbshop.agent.infrastructure.client.cafe24.adapter.Cafe24MarketClient;
import com.sbshop.agent.infrastructure.client.cafe24.client.Cafe24RestClient;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class Cafe24MarketClientCatalogTest {

	@Mock
	private Cafe24RestClient restClient;

	private Cafe24MarketClient client;

	@BeforeEach
	void setUp() {
		client = new Cafe24MarketClient(new ObjectMapper(), restClient, null, null);
	}

	private static String page(String... products) {
		return "{\"products\":[" + String.join(",", products) + "]}";
	}

	private static String product(int no, String code, String customCode) {
		return "{\"product_no\":" + no + ",\"product_code\":\"" + code + "\","
			+ (customCode == null ? "" : "\"custom_product_code\":\"" + customCode + "\",")
			+ "\"display\":\"T\",\"selling\":\"T\"}";
	}

	@Test
	@DisplayName("카탈로그: custom_product_code 를 sellerCode 로, product_no·product_code 를 식별자로 담는다")
	void fetchCatalog_mapsSellerCodeAndIdentifiers() {
		when(restClient.get(anyString())).thenReturn(page(product(17624, "P000BABW", "220227IHB052")));

		List<MarketCatalogEntry> entries = client.fetchCatalog(0L);

		assertThat(entries).hasSize(1);
		MarketCatalogEntry entry = entries.get(0);
		assertThat(entry.sellerCode()).isEqualTo("220227IHB052");
		assertThat(entry.identifiers())
			.containsEntry("product_no", "17624")
			.containsEntry("product_code", "P000BABW");
		assertThat(entry.status()).isEqualTo("display=T,selling=T");
	}

	@Test
	@DisplayName("카탈로그: custom_product_code 가 응답에 없으면 sellerCode 는 null 이다")
	void fetchCatalog_missingCustomProductCodeYieldsNullSellerCode() {
		when(restClient.get(anyString())).thenReturn(page(product(17624, "P000BABW", null)));

		assertThat(client.fetchCatalog(0L).get(0).sellerCode()).isNull();
	}

	@Test
	@DisplayName("카탈로그: limit=100·offset 증가로 순회하고 반환 건수가 limit 미만이면 종료한다")
	void fetchCatalog_paginatesByOffsetUntilShortPage() {
		String[] full = new String[100];
		for (int i = 0; i < 100; i++) {
			full[i] = product(i + 1, "P" + i, "SB" + i);
		}
		when(restClient.get(anyString()))
			.thenReturn(page(full))
			.thenReturn(page(product(101, "P101", "SB101")));

		List<MarketCatalogEntry> entries = client.fetchCatalog(0L);

		assertThat(entries).hasSize(101);
		ArgumentCaptor<String> paths = ArgumentCaptor.forClass(String.class);
		verify(restClient, times(2)).get(paths.capture());
		assertThat(paths.getAllValues().get(0)).contains("limit=100").contains("offset=0");
		assertThat(paths.getAllValues().get(1)).contains("offset=100");
	}

	@Test
	@DisplayName("카탈로그: 요청 경로에 custom_product_code 를 포함한 fields 를 명시한다")
	void fetchCatalog_requestsCustomProductCodeExplicitly() {
		when(restClient.get(anyString())).thenReturn(page());

		client.fetchCatalog(0L);

		ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
		verify(restClient).get(path.capture());
		assertThat(path.getValue())
			.startsWith("/admin/products?")
			.contains("custom_product_code")
			.contains("product_no")
			.contains("product_code")
			.contains("display")
			.contains("selling");
	}

	@Test
	@DisplayName("카탈로그: 429 를 만나면 백오프 후 재시도해 이어서 수집한다")
	void fetchCatalog_backsOffOn429AndRetries() {
		when(restClient.get(anyString()))
			.thenThrow(new RuntimeException("Cafe24 API 호출 실패(429): {\"error\":\"Too many requests\"}"))
			.thenReturn(page(product(1, "P1", "SB1")));

		List<MarketCatalogEntry> entries = client.fetchCatalog(0L);

		assertThat(entries).hasSize(1);
		verify(restClient, times(2)).get(anyString());
	}

	@Test
	@DisplayName("카탈로그: 429 가 아닌 실패는 부분 결과를 반환하지 않고 예외를 던진다")
	void fetchCatalog_nonRateLimitFailurePropagates() {
		when(restClient.get(anyString()))
			.thenThrow(new RuntimeException("Cafe24 API 호출 실패(500): boom"));

		assertThatThrownBy(() -> client.fetchCatalog(0L))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("전체 상품 조회 실패");
	}

	@Test
	@DisplayName("카탈로그: offset 상한(5000)에 닿으면 since_product_no 커서로 전환한다")
	void fetchCatalog_switchesToCursorAtOffsetCap() {
		String[] full = new String[100];
		for (int i = 0; i < 100; i++) {
			full[i] = product(i + 1, "P" + i, "SB" + i);
		}
		when(restClient.get(anyString())).thenReturn(page(full));
		when(restClient.get(startsWith("/admin/products?limit=100&since_product_no=")))
			.thenReturn(page(product(9999, "P9999", "SB9999")));

		List<MarketCatalogEntry> entries = client.fetchCatalog(0L);

		assertThat(entries).hasSize(5001);
		ArgumentCaptor<String> paths = ArgumentCaptor.forClass(String.class);
		verify(restClient, times(51)).get(paths.capture());
		assertThat(paths.getAllValues().get(50)).contains("since_product_no=100");
	}

	@Test
	@DisplayName("카탈로그: throttleMs 를 페이지 사이에 실제로 대기한다")
	void fetchCatalog_honoursThrottleBetweenPages() {
		String[] full = new String[100];
		for (int i = 0; i < 100; i++) {
			full[i] = product(i + 1, "P" + i, "SB" + i);
		}
		when(restClient.get(anyString()))
			.thenReturn(page(full))
			.thenReturn(page(product(101, "P101", "SB101")));

		long started = System.nanoTime();
		client.fetchCatalog(120L);
		long elapsedMs = (System.nanoTime() - started) / 1_000_000L;

		assertThat(elapsedMs).isGreaterThanOrEqualTo(120L);
	}

	@Test
	@DisplayName("카탈로그: custom_product_code·product_code 앞뒤 공백을 제거한다")
	void fetchCatalog_trimsSellerCodeAndIdentifiers() {
		when(restClient.get(anyString())).thenReturn(
			"{\"products\":[{\"product_no\":\" 17624 \",\"product_code\":\" P000BABW \","
				+ "\"custom_product_code\":\"  220227IHB052  \",\"display\":\"T\",\"selling\":\"T\"}]}");

		MarketCatalogEntry entry = client.fetchCatalog(0L).get(0);

		assertThat(entry.sellerCode()).isEqualTo("220227IHB052");
		assertThat(entry.identifiers())
			.containsEntry("product_no", "17624")
			.containsEntry("product_code", "P000BABW");
	}

	@Test
	@DisplayName("카탈로그: 페이지 상한을 소진하면 잘린 목록을 반환하지 않고 예외를 던진다")
	void fetchCatalog_pageCapExhaustionThrowsInsteadOfTruncating() {
		String[] full = new String[100];
		for (int i = 0; i < 100; i++) {
			full[i] = product(i + 1, "P" + i, "SB" + i);
		}
		when(restClient.get(anyString())).thenReturn(page(full));

		assertThatThrownBy(() -> client.fetchCatalog(0L))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("페이지 상한");
	}
}
