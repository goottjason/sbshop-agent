package com.sbshop.agent.infrastructure.client.coupang;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.market.client.dto.MarketCatalogEntry;
import com.sbshop.agent.infrastructure.client.coupang.adapter.CoupangMarketClient;
import com.sbshop.agent.infrastructure.client.coupang.client.CoupangRestClient;
import com.sbshop.agent.infrastructure.client.coupang.component.CoupangAttributeValueResolver;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CoupangMarketClientCatalogTest {

	@Mock
	private CoupangRestClient restClient;

	private CoupangMarketClient client;

	private static final String LIST_BASE = "/v2/providers/seller_api/apis/api/v1/marketplace/seller-products";

	@BeforeEach
	void setUp() {
		client = new CoupangMarketClient(null, new ObjectMapper(), restClient, null,
			null, null, null, null, new CoupangAttributeValueResolver());
	}

	@Test
	@DisplayName("카탈로그: 목록은 sellerProductId·productId·statusName 만 담고 SB코드는 null 이다")
	void fetchCatalog_listHasNoSellerCode() {
		when(restClient.resolveVendorId()).thenReturn("A00012345");
		when(restClient.get(anyString())).thenReturn(
			"{\"code\":\"SUCCESS\",\"nextToken\":\"\",\"data\":[{\"sellerProductId\":14813281569,"
				+ "\"productId\":70535073,\"statusName\":\"승인완료\"}]}");

		List<MarketCatalogEntry> entries = client.fetchCatalog(0L);

		assertThat(entries).hasSize(1);
		MarketCatalogEntry entry = entries.get(0);
		assertThat(entry.sellerCode()).isNull();
		assertThat(entry.identifiers())
			.containsEntry("sellerProductId", "14813281569")
			.containsEntry("productId", "70535073");
		assertThat(entry.status()).isEqualTo("승인완료");
	}

	@Test
	@DisplayName("카탈로그: nextToken 을 따라 순회하고 빈 nextToken 에서 종료한다")
	void fetchCatalog_followsNextTokenUntilEmpty() {
		when(restClient.resolveVendorId()).thenReturn("A00012345");
		when(restClient.get(anyString()))
			.thenReturn("{\"code\":\"SUCCESS\",\"nextToken\":\"2\",\"data\":[{\"sellerProductId\":1,"
				+ "\"productId\":11,\"statusName\":\"승인완료\"}]}")
			.thenReturn("{\"code\":\"SUCCESS\",\"nextToken\":\"\",\"data\":[{\"sellerProductId\":2,"
				+ "\"productId\":22,\"statusName\":\"심사중\"}]}");

		List<MarketCatalogEntry> entries = client.fetchCatalog(0L);

		assertThat(entries).hasSize(2);
		ArgumentCaptor<String> paths = ArgumentCaptor.forClass(String.class);
		verify(restClient, times(2)).get(paths.capture());
		assertThat(paths.getAllValues().get(0))
			.startsWith(LIST_BASE + "?")
			.contains("vendorId=A00012345")
			.contains("maxPerPage=100")
			.contains("nextToken=");
		assertThat(paths.getAllValues().get(1)).contains("nextToken=2");
	}

	@Test
	@DisplayName("카탈로그: productId 가 없는 상품은 sellerProductId 만 담는다")
	void fetchCatalog_missingProductIdOmitted() {
		when(restClient.resolveVendorId()).thenReturn("A00012345");
		when(restClient.get(anyString())).thenReturn(
			"{\"code\":\"SUCCESS\",\"nextToken\":\"\",\"data\":[{\"sellerProductId\":5,\"statusName\":\"임시저장\"}]}");

		MarketCatalogEntry entry = client.fetchCatalog(0L).get(0);

		assertThat(entry.identifiers()).containsEntry("sellerProductId", "5").doesNotContainKey("productId");
	}

	@Test
	@DisplayName("카탈로그: 봉투 code 가 SUCCESS 가 아니면 예외를 던진다(조용한 빈 결과 금지)")
	void fetchCatalog_nonSuccessEnvelopeThrows() {
		when(restClient.resolveVendorId()).thenReturn("A00012345");
		when(restClient.get(anyString()))
			.thenReturn("{\"code\":\"ERROR\",\"message\":\"권한이 없습니다\",\"data\":[]}");

		assertThatThrownBy(() -> client.fetchCatalog(0L))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("권한이 없습니다");
	}

	@Test
	@DisplayName("카탈로그: 조회 중 실패하면 부분 결과 대신 예외를 던진다")
	void fetchCatalog_failurePropagates() {
		when(restClient.resolveVendorId()).thenReturn("A00012345");
		when(restClient.get(anyString())).thenThrow(new RuntimeException("Coupang API 호출 실패"));

		assertThatThrownBy(() -> client.fetchCatalog(0L))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("전체 상품 조회 실패");
	}

	@Test
	@DisplayName("SB코드 단건: external-vendor-sku-codes 경로로 조회해 sellerCode 를 채운다")
	void fetchBySellerCode_returnsEntryWithSellerCode() {
		when(restClient.get(LIST_BASE + "/external-vendor-sku-codes/220227IHB052")).thenReturn(
			"{\"code\":\"SUCCESS\",\"data\":[{\"sellerProductId\":14813281569,\"productId\":70535073,"
				+ "\"statusName\":\"승인완료\"}]}");

		Optional<MarketCatalogEntry> entry = client.fetchBySellerCode("220227IHB052");

		assertThat(entry).isPresent();
		assertThat(entry.get().sellerCode()).isEqualTo("220227IHB052");
		assertThat(entry.get().identifiers())
			.containsEntry("sellerProductId", "14813281569")
			.containsEntry("productId", "70535073");
		assertThat(entry.get().status()).isEqualTo("승인완료");
	}

	@Test
	@DisplayName("SB코드 단건: data 가 비면 Optional.empty() 를 반환한다")
	void fetchBySellerCode_emptyDataReturnsEmpty() {
		when(restClient.get(anyString())).thenReturn("{\"code\":\"SUCCESS\",\"data\":[]}");

		assertThat(client.fetchBySellerCode("220227IHB052")).isEmpty();
	}

	@Test
	@DisplayName("SB코드 단건: 404 는 미등록으로 보고 Optional.empty() 를 반환한다")
	void fetchBySellerCode_notFoundReturnsEmpty() {
		when(restClient.get(anyString()))
			.thenThrow(new RuntimeException("Coupang API 호출 실패",
				new RuntimeException("404 Not Found: no such external vendor sku")));

		assertThat(client.fetchBySellerCode("220227IHB052")).isEmpty();
	}

	@Test
	@DisplayName("SB코드 단건: 404 가 아닌 실패는 미등록으로 둔갑시키지 않고 예외를 던진다")
	void fetchBySellerCode_otherFailureThrows() {
		when(restClient.get(anyString()))
			.thenThrow(new RuntimeException("Coupang API 호출 실패",
				new RuntimeException("500 Internal Server Error")));

		assertThatThrownBy(() -> client.fetchBySellerCode("220227IHB052"))
			.isInstanceOf(RuntimeException.class);
	}

	@Test
	@DisplayName("SB코드 단건: blank 입력은 호출 없이 Optional.empty()")
	void fetchBySellerCode_blankInput() {
		assertThat(client.fetchBySellerCode("  ")).isEmpty();
		assertThat(client.fetchBySellerCode(null)).isEmpty();
		verify(restClient, times(0)).get(anyString());
	}

	@Test
	@DisplayName("카탈로그: 식별자·상태 문자열의 앞뒤 공백을 제거한다")
	void fetchCatalog_trimsIdentifiersAndStatus() {
		when(restClient.resolveVendorId()).thenReturn("A00012345");
		when(restClient.get(anyString())).thenReturn(
			"{\"code\":\"SUCCESS\",\"nextToken\":\"\",\"data\":[{\"sellerProductId\":\" 14813281569 \","
				+ "\"productId\":70535073,\"statusName\":\" 승인완료 \"}]}");

		MarketCatalogEntry entry = client.fetchCatalog(0L).get(0);

		assertThat(entry.identifiers()).containsEntry("sellerProductId", "14813281569");
		assertThat(entry.status()).isEqualTo("승인완료");
	}

	@Test
	@DisplayName("카탈로그: 페이지 상한을 소진하면 잘린 목록을 반환하지 않고 예외를 던진다")
	void fetchCatalog_pageCapExhaustionThrowsInsteadOfTruncating() {
		when(restClient.resolveVendorId()).thenReturn("A00012345");
		when(restClient.get(anyString())).thenReturn(
			"{\"code\":\"SUCCESS\",\"nextToken\":\"2\",\"data\":[{\"sellerProductId\":1,"
				+ "\"productId\":11,\"statusName\":\"승인완료\"}]}");

		assertThatThrownBy(() -> client.fetchCatalog(0L))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("페이지 상한");
	}

	@Test
	@DisplayName("단건 조회 지원을 명시적으로 선언한다 — deep 모드가 헛돌지 않도록")
	void declaresSingleLookupSupport() {
		assertThat(client.supportsSingleLookup()).isTrue();
	}

}
