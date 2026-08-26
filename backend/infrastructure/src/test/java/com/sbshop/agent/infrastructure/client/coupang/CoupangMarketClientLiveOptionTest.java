package com.sbshop.agent.infrastructure.client.coupang;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.domain.market.client.dto.MarketDraftPrice;
import com.sbshop.agent.core.domain.market.client.dto.MarketDraftPriceMiss;
import com.sbshop.agent.core.domain.market.client.dto.MarketLiveOption;
import com.sbshop.agent.infrastructure.client.coupang.adapter.CoupangMarketClient;
import com.sbshop.agent.infrastructure.client.coupang.client.CoupangRestClient;
import com.sbshop.agent.infrastructure.client.coupang.component.CoupangAttributeValueResolver;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CoupangMarketClientLiveOptionTest {

	private static final String INVENTORIES_BASE = "/v2/providers/seller_api/apis/api/v1/marketplace/vendor-items/";
	private static final String DETAIL_BASE = "/v2/providers/seller_api/apis/api/v1/marketplace/seller-products/";

	@Mock
	private CoupangRestClient restClient;

	private CoupangMarketClient client;

	@BeforeEach
	void setUp() {
		client = new CoupangMarketClient(null, new ObjectMapper(), restClient, null,
			null, null, null, null, new CoupangAttributeValueResolver());
	}

	@Test
	@DisplayName("옵션 실판매 조회: inventories GET 한 번으로 실판매가·재고·판매상태를 읽는다")
	void fetchLiveOption_readsPriceStockAndOnSale() {
		when(restClient.get(anyString())).thenReturn(
			"{\"code\":\"SUCCESS\",\"message\":\"\",\"data\":{\"sellerItemId\":87763025801,"
				+ "\"amountInStock\":999,\"salePrice\":50100,\"onSale\":true}}");

		Optional<MarketLiveOption> found = client.fetchLiveOption("87763025801");

		assertThat(found).isPresent();
		MarketLiveOption option = found.orElseThrow();
		assertThat(option.optionId()).isEqualTo("87763025801");
		assertThat(option.salePrice()).isEqualTo(50100);
		assertThat(option.stock()).isEqualTo(999);
		assertThat(option.onSale()).isTrue();

		ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
		verify(restClient).get(path.capture());
		assertThat(path.getValue()).isEqualTo(INVENTORIES_BASE + "87763025801/inventories");
	}

	@Test
	@DisplayName("옵션 실판매 조회: onSale=false 면 판매중지로 그대로 싣는다")
	void fetchLiveOption_keepsSuspendedFlag() {
		when(restClient.get(anyString())).thenReturn(
			"{\"code\":\"SUCCESS\",\"data\":{\"sellerItemId\":1,\"amountInStock\":0,"
				+ "\"salePrice\":12000,\"onSale\":false}}");

		MarketLiveOption option = client.fetchLiveOption("1").orElseThrow();

		assertThat(option.onSale()).isFalse();
		assertThat(option.stock()).isZero();
	}

	@Test
	@DisplayName("옵션 실판매 조회: 봉투 code 가 ERROR 면 실패로 던진다 — 성공으로 삼키지 않는다")
	void fetchLiveOption_throwsOnErrorEnvelope() {
		when(restClient.get(anyString())).thenReturn(
			"{\"code\":\"ERROR\",\"message\":\"권한이 없습니다\",\"data\":null}");

		assertThatThrownBy(() -> client.fetchLiveOption("1"))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("ERROR")
			.hasMessageContaining("권한이 없습니다");
	}

	@Test
	@DisplayName("옵션 실판매 조회: 봉투는 SUCCESS 인데 data 가 비면 실패로 던진다")
	void fetchLiveOption_throwsWhenDataMissing() {
		when(restClient.get(anyString())).thenReturn("{\"code\":\"SUCCESS\",\"data\":null}");

		assertThatThrownBy(() -> client.fetchLiveOption("1"))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("데이터 없음");
	}

	@Test
	@DisplayName("옵션 실판매 조회: 문서화된 400(유효한 옵션 없음)은 옵션 부재로 빈 값 — 조회 실패가 아니다")
	void fetchLiveOption_emptyOnDocumentedInvalidOption() {
		when(restClient.get(anyString())).thenThrow(new RuntimeException("Coupang API 호출 실패",
			new RuntimeException("400 Bad Request: \"{\"code\":\"ERROR\",\"message\":\"유효한 옵션이 없습니다\"}\"")));

		assertThat(client.fetchLiveOption("1")).isEmpty();
	}

	@Test
	@DisplayName("옵션 실판매 조회: 404 도 옵션 부재로 빈 값이다")
	void fetchLiveOption_emptyOnNotFound() {
		when(restClient.get(anyString())).thenThrow(new RuntimeException("Coupang API 호출 실패",
			new RuntimeException("404 Not Found")));

		assertThat(client.fetchLiveOption("1")).isEmpty();
	}

	@Test
	@DisplayName("옵션 실판매 조회: 문서화되지 않은 오류는 삼키지 않고 던진다 — 부재로 단정하지 않는다")
	void fetchLiveOption_rethrowsUndocumentedFailure() {
		when(restClient.get(anyString())).thenThrow(new RuntimeException("Coupang API 호출 실패",
			new RuntimeException("500 Internal Server Error")));

		assertThatThrownBy(() -> client.fetchLiveOption("1")).isInstanceOf(RuntimeException.class);
	}

	@Test
	@DisplayName("옵션 실판매 조회: 옵션ID 가 비면 호출하지 않는다")
	void fetchLiveOption_skipsBlankOptionId() {
		assertThat(client.fetchLiveOption(" ")).isEmpty();
		assertThat(client.fetchLiveOption(null)).isEmpty();
		verify(restClient, never()).get(anyString());
	}

	@Test
	@DisplayName("옵션 실판매 조회는 GET 전용이다 — 쓰기 동사를 쓰지 않는다")
	void fetchLiveOption_neverWrites() {
		when(restClient.get(anyString())).thenReturn(
			"{\"code\":\"SUCCESS\",\"data\":{\"sellerItemId\":1,\"amountInStock\":1,"
				+ "\"salePrice\":100,\"onSale\":true}}");

		client.fetchLiveOption("1");

		verify(restClient, never()).put(anyString(), any());
		verify(restClient, never()).post(anyString(), any());
		verify(restClient, never()).requestWithBody(anyString(), anyString(), any());
	}

	@Test
	@DisplayName("옵션 실판매 조회: 값이 문자열로 와도 숫자로 읽는다 — 조용히 미상으로 떨구지 않는다")
	void fetchLiveOption_readsStringEncodedNumbers() {
		when(restClient.get(anyString())).thenReturn(
			"{\"code\":\"SUCCESS\",\"data\":{\"sellerItemId\":\"7\",\"amountInStock\":\"12\","
				+ "\"salePrice\":\"33000\",\"onSale\":\"true\"}}");

		MarketLiveOption option = client.fetchLiveOption("7").orElseThrow();

		assertThat(option.salePrice()).isEqualTo(33000);
		assertThat(option.stock()).isEqualTo(12);
		assertThat(option.onSale()).isTrue();
	}

	@Test
	@DisplayName("쿠팡은 옵션 실판매 조회를 지원한다고 선언한다")
	void supportsLiveOptionLookup() {
		assertThat(client.supportsLiveOptionLookup()).isTrue();
	}

	@Test
	@DisplayName("초안가 조회: 등록상품 GET 의 items[0].salePrice 를 읽는다")
	void fetchDraftSalePrice_readsFirstItemSalePrice() {
		when(restClient.get(anyString())).thenReturn(
			"{\"code\":\"SUCCESS\",\"data\":{\"sellerProductId\":14813281569,"
				+ "\"statusName\":\"임시저장\",\"items\":[{\"salePrice\":61400}]}}");

		MarketDraftPrice draft = client.fetchDraftSalePrice("14813281569");
		assertThat(draft.isPresent()).isTrue();
		assertThat(draft.salePrice()).isEqualTo(61400);
		assertThat(draft.miss()).isNull();

		ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
		verify(restClient).get(path.capture());
		assertThat(path.getValue()).isEqualTo(DETAIL_BASE + "14813281569");
	}

	@Test
	@DisplayName("초안가 조회: items 가 빈 배열이면 미상 사유 EMPTY_ITEMS 로 구분해 알린다")
	void fetchDraftSalePrice_emptyItemsReason() {
		when(restClient.get(anyString())).thenReturn(
			"{\"code\":\"SUCCESS\",\"data\":{\"sellerProductId\":1,\"items\":[]}}");

		MarketDraftPrice draft = client.fetchDraftSalePrice("1");

		assertThat(draft.isPresent()).isFalse();
		assertThat(draft.miss()).isEqualTo(MarketDraftPriceMiss.EMPTY_ITEMS);
	}

	@Test
	@DisplayName("초안가 조회: items 필드 자체가 없으면 구조 불일치(NO_ITEMS_FIELD)로 구분한다 — 빈 배열과 섞지 않는다")
	void fetchDraftSalePrice_noItemsFieldReason() {
		when(restClient.get(anyString())).thenReturn(
			"{\"code\":\"SUCCESS\",\"data\":{\"sellerProductId\":1}}");

		MarketDraftPrice draft = client.fetchDraftSalePrice("1");

		assertThat(draft.isPresent()).isFalse();
		assertThat(draft.miss()).isEqualTo(MarketDraftPriceMiss.NO_ITEMS_FIELD);
	}

	@Test
	@DisplayName("초안가 조회: items[0].salePrice 를 읽을 수 없으면 NO_PRICE_FIELD 로 구분한다")
	void fetchDraftSalePrice_noPriceFieldReason() {
		when(restClient.get(anyString())).thenReturn(
			"{\"code\":\"SUCCESS\",\"data\":{\"sellerProductId\":1,\"items\":[{\"itemName\":\"x\"}]}}");

		MarketDraftPrice draft = client.fetchDraftSalePrice("1");

		assertThat(draft.isPresent()).isFalse();
		assertThat(draft.miss()).isEqualTo(MarketDraftPriceMiss.NO_PRICE_FIELD);
	}

	@Test
	@DisplayName("초안가 조회: 404 는 상품 부재(PRODUCT_ABSENT)로 구분한다")
	void fetchDraftSalePrice_productAbsentReason() {
		when(restClient.get(anyString())).thenThrow(new RuntimeException("Coupang API 호출 실패",
			new RuntimeException("404 Not Found")));

		assertThat(client.fetchDraftSalePrice("1").miss()).isEqualTo(MarketDraftPriceMiss.PRODUCT_ABSENT);
	}

	@Test
	@DisplayName("초안가 조회: 봉투 code 가 ERROR 면 실패로 던진다")
	void fetchDraftSalePrice_throwsOnErrorEnvelope() {
		when(restClient.get(anyString())).thenReturn(
			"{\"code\":\"ERROR\",\"message\":\"조회 권한 없음\",\"data\":null}");

		assertThatThrownBy(() -> client.fetchDraftSalePrice("1"))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("조회 권한 없음");
	}

	@Test
	@DisplayName("초안가 조회: 등록상품ID 가 비면 호출하지 않고 NO_SELLER_PRODUCT_ID 로 구분한다")
	void fetchDraftSalePrice_skipsBlankId() {
		assertThat(client.fetchDraftSalePrice("").miss()).isEqualTo(MarketDraftPriceMiss.NO_SELLER_PRODUCT_ID);
		verify(restClient, never()).get(anyString());
	}
}
