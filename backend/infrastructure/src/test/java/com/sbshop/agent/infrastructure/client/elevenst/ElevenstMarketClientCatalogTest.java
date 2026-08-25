package com.sbshop.agent.infrastructure.client.elevenst;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.domain.market.client.dto.MarketCatalogEntry;
import com.sbshop.agent.infrastructure.client.elevenst.adapter.ElevenstMarketClient;
import com.sbshop.agent.infrastructure.client.elevenst.client.ElevenstMarketRestClient;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ElevenstMarketClientCatalogTest {

	@Mock
	private ElevenstMarketRestClient restClient;

	private ElevenstMarketClient client;

	private static final String PATH = "/rest/prodmarketservice/prodmarket";

	@BeforeEach
	void setUp() {
		client = new ElevenstMarketClient(restClient);
	}

	private static String record(String prdNo, String sellerPrdCd, String statCd, String statNm) {
		return "<Product><prdNo>" + prdNo + "</prdNo><sellerPrdCd>" + sellerPrdCd + "</sellerPrdCd>"
			+ "<selStatCd>" + statCd + "</selStatCd>"
			+ (statNm == null ? "" : "<selStatNm>" + statNm + "</selStatNm>")
			+ "</Product>";
	}

	private static String envelope(String body) {
		return "<?xml version=\"1.0\" encoding=\"euc-kr\"?><Products><resultCode>200</resultCode>"
			+ body + "</Products>";
	}

	@Test
	@DisplayName("카탈로그: fetchCatalog는 실호출 검증 전까지 비활성 — null(미지원)을 반환하고 마켓을 호출하지 않는다")
	void fetchCatalog_isDisabledPendingLiveVerification() {
		assertThat(client.fetchCatalog(0L)).isNull();
		verifyNoInteractions(restClient);
	}

	@Test
	@DisplayName("카탈로그: 비활성 사유를 리포트가 읽을 수 있게 노출한다")
	void catalogUnsupportedReason_explainsDisabledState() {
		assertThat(client.catalogUnsupportedReason()).contains("실호출");
	}

	@Test
	@DisplayName("카탈로그: resultCode가 아예 없는 본문은 통과시키지 않고 예외를 던진다(빈 카탈로그 오탐 차단)")
	void scanCatalog_missingResultCodeThrows() {
		when(restClient.post(eq(PATH), anyString()))
			.thenReturn("<html><body>502 Bad Gateway</body></html>");

		assertThatThrownBy(() -> client.scanCatalog(0L))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("resultCode");
	}

	@Test
	@DisplayName("카탈로그: prdNo·sellerPrdCd·판매상태를 담는다")
	void scanCatalog_mapsFields() {
		when(restClient.post(eq(PATH), anyString()))
			.thenReturn(envelope(record("4193852605", "220227IHB052", "103", "판매중")));

		List<MarketCatalogEntry> entries = client.scanCatalog(0L);

		assertThat(entries).hasSize(1);
		MarketCatalogEntry entry = entries.get(0);
		assertThat(entry.sellerCode()).isEqualTo("220227IHB052");
		assertThat(entry.identifiers()).containsEntry("prdNo", "4193852605");
		assertThat(entry.status()).isEqualTo("판매중");
	}

	@Test
	@DisplayName("카탈로그: selStatNm 이 없으면 selStatCd 를 원문 그대로 status 에 담는다")
	void scanCatalog_fallsBackToStatusCode() {
		when(restClient.post(eq(PATH), anyString()))
			.thenReturn(envelope(record("1", "SB1", "103", null)));

		assertThat(client.scanCatalog(0L).get(0).status()).isEqualTo("103");
	}

	@Test
	@DisplayName("카탈로그: limit·start·end 로 순번 페이징하고 반환 건수 < limit 이면 종료한다")
	void scanCatalog_paginatesByStartEnd() {
		StringBuilder full = new StringBuilder();
		for (int i = 1; i <= 100; i++) {
			full.append(record(String.valueOf(i), "SB" + i, "103", "판매중"));
		}
		when(restClient.post(eq(PATH), anyString()))
			.thenReturn(envelope(full.toString()))
			.thenReturn(envelope(record("101", "SB101", "103", "판매중")));

		List<MarketCatalogEntry> entries = client.scanCatalog(0L);

		assertThat(entries).hasSize(101);
		ArgumentCaptor<String> bodies = ArgumentCaptor.forClass(String.class);
		verify(restClient, times(2)).post(eq(PATH), bodies.capture());
		assertThat(bodies.getAllValues().get(0))
			.contains("<limit>100</limit>")
			.contains("<start>1</start>")
			.contains("<end>100</end>");
		assertThat(bodies.getAllValues().get(1))
			.contains("<start>101</start>")
			.contains("<end>200</end>");
	}

	@Test
	@DisplayName("카탈로그: resultCode 가 -997 이면 빈 결과가 아니라 예외를 던진다(D-208 조용한 오답 차단)")
	void scanCatalog_authRejectionThrows() {
		when(restClient.post(eq(PATH), anyString())).thenReturn(
			"<?xml version=\"1.0\" encoding=\"euc-kr\"?><ns2:result><resultCode>-997</resultCode>"
				+ "<resultMessage>등록된 API 정보가 존재하지 않습니다.</resultMessage></ns2:result>");

		assertThatThrownBy(() -> client.scanCatalog(0L))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("-997")
			.hasMessageContaining("등록된 API 정보가 존재하지 않습니다");
	}

	@Test
	@DisplayName("카탈로그: REST 클라이언트가 합성한 ERROR 봉투도 예외로 승격한다")
	void scanCatalog_syntheticErrorEnvelopeThrows() {
		when(restClient.post(eq(PATH), anyString()))
			.thenReturn("<resultCode>ERROR</resultCode><message>NO_RESPONSE</message>");

		assertThatThrownBy(() -> client.scanCatalog(0L))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("ERROR");
	}

	@Test
	@DisplayName("카탈로그: 상품 레코드가 없으면 빈 목록으로 종료한다")
	void scanCatalog_noRecordsReturnsEmpty() {
		when(restClient.post(eq(PATH), anyString())).thenReturn(envelope(""));

		assertThat(client.scanCatalog(0L)).isEmpty();
		verify(restClient, times(1)).post(eq(PATH), anyString());
	}

	@Test
	@DisplayName("카탈로그: 응답이 비어 있으면 조용히 넘기지 않고 예외를 던진다")
	void scanCatalog_blankResponseThrows() {
		when(restClient.post(eq(PATH), anyString())).thenReturn("");

		assertThatThrownBy(() -> client.scanCatalog(0L))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("빈 응답");
	}

	@Test
	@DisplayName("카탈로그: 페이지 상한을 소진하면 잘린 목록을 반환하지 않고 예외를 던진다")
	void scanCatalog_pageCapExhaustionThrowsInsteadOfTruncating() {
		StringBuilder full = new StringBuilder();
		for (int i = 1; i <= 100; i++) {
			full.append(record(String.valueOf(i), "SB" + i, "103", "판매중"));
		}
		when(restClient.post(eq(PATH), anyString())).thenReturn(envelope(full.toString()));

		assertThatThrownBy(() -> client.scanCatalog(0L))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("페이지 상한");
	}
}
