package com.sbshop.agent.core.application.order.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.w3c.dom.Element;

import com.sbshop.agent.core.application.order.mapper.ElevenstStatusMapper;
import com.sbshop.agent.core.application.order.port.ElevenstOrderApiPort;
import com.sbshop.agent.core.domain.order.enums.ClaimStage;
import com.sbshop.agent.core.domain.order.enums.ClaimType;
import com.sbshop.agent.core.domain.order.vo.ClaimData;

@ExtendWith(MockitoExtension.class)
class ElevenstClaimListSignalsTest {
	@Mock
	private ElevenstOrderApiPort api;

	private final ElevenstStatusMapper statusMapper = new ElevenstStatusMapper();

	private ElevenstOrderAdapter adapter() {
		return new ElevenstOrderAdapter(api, statusMapper);
	}

	@Test
	@DisplayName("D-278: 반품 요청 목록의 clmStat이 ordNo/ordPrdSeq로 색인된다")
	void returnRequestRowBecomesClaimSignal() throws Exception {
		when(api.fetchReturnRequestedOrders(anyString(), anyString(), anyString()))
			.thenReturn(List.of(claimRow("201001068151292", "1", "105")));

		Map<String, Map<String, ClaimData>> signals = adapter()
			.fetchClaimListSignals("api-key", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 10));

		ClaimData claim = signals.get("201001068151292").get("1");
		assertThat(claim.getClaimType()).isEqualTo(ClaimType.RETURN);
		assertThat(claim.getClaimStage()).isEqualTo(ClaimStage.REQUESTED);
	}

	@Test
	@DisplayName("D-278: 취소 목록의 ordCnStatCd가 취소 클레임으로 매핑된다")
	void cancelRowBecomesClaimSignal() throws Exception {
		when(api.fetchCancelCompletedOrders(anyString(), anyString(), anyString()))
			.thenReturn(List.of(cancelRow("201001198151936", "1", "02")));

		Map<String, Map<String, ClaimData>> signals = adapter()
			.fetchClaimListSignals("api-key", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 10));

		ClaimData claim = signals.get("201001198151936").get("1");
		assertThat(claim.getClaimType()).isEqualTo(ClaimType.CANCEL);
		assertThat(claim.getClaimStage()).isEqualTo(ClaimStage.DONE);
	}

	@Test
	@DisplayName("D-278: ordPrdSeq가 없는 로우는 주문 단위 키로 들어간다")
	void rowWithoutSeqUsesOrderWideKey() throws Exception {
		when(api.fetchReturnRequestedOrders(anyString(), anyString(), anyString()))
			.thenReturn(List.of(claimRow("201001068151292", null, "105")));

		Map<String, Map<String, ClaimData>> signals = adapter()
			.fetchClaimListSignals("api-key", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 10));

		assertThat(signals.get("201001068151292")).containsKey(ElevenstOrderAdapter.CLAIM_ORDER_WIDE);
	}

	@Test
	@DisplayName("D-278: 나중에 조회한 완료 목록이 앞선 요청 목록 신호를 덮어쓴다 — 최신 상태 우선")
	void laterStageOverwritesEarlierStage() throws Exception {
		when(api.fetchReturnRequestedOrders(anyString(), anyString(), anyString()))
			.thenReturn(List.of(claimRow("201001068151292", "1", "105")));
		when(api.fetchReturnCompletedOrders(anyString(), anyString(), anyString()))
			.thenReturn(List.of(claimRow("201001068151292", "1", "106")));

		Map<String, Map<String, ClaimData>> signals = adapter()
			.fetchClaimListSignals("api-key", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 10));

		assertThat(signals.get("201001068151292").get("1").getClaimStage()).isEqualTo(ClaimStage.DONE);
	}

	@Test
	@DisplayName("D-278: 클레임이 아닌(미매핑) 로우는 결과에 담기지 않는다")
	void inactiveClaimIsNotIndexed() throws Exception {
		when(api.fetchReturnRequestedOrders(anyString(), anyString(), anyString()))
			.thenReturn(List.of(claimRow("201001068151292", "1", "999")));

		Map<String, Map<String, ClaimData>> signals = adapter()
			.fetchClaimListSignals("api-key", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 10));

		assertThat(signals).isEmpty();
	}

	@Test
	@DisplayName("D-278: 30일을 넘는 구간은 30일 단위로 나눠 9종 API를 매 구간마다 호출한다")
	void chunksLongRangeIntoThirtyDayWindows() throws Exception {
		stubAllEmpty();

		adapter().fetchClaimListSignals("api-key", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 8, 10));

		verify(api, times(3)).fetchReturnRequestedOrders(anyString(), anyString(), anyString());
		verify(api, times(3)).fetchCancelWithdrawnOrders(anyString(), anyString(), anyString());
	}

	private void stubAllEmpty() {
		when(api.fetchReturnRequestedOrders(anyString(), anyString(), anyString())).thenReturn(List.of());
		when(api.fetchReturnCompletedOrders(anyString(), anyString(), anyString())).thenReturn(List.of());
		when(api.fetchReturnWithdrawnOrders(anyString(), anyString(), anyString())).thenReturn(List.of());
		when(api.fetchExchangeRequestedOrders(anyString(), anyString(), anyString())).thenReturn(List.of());
		when(api.fetchExchangeCompletedOrders(anyString(), anyString(), anyString())).thenReturn(List.of());
		when(api.fetchExchangeWithdrawnOrders(anyString(), anyString(), anyString())).thenReturn(List.of());
		when(api.fetchCancelRequestedOrders(anyString(), anyString(), anyString())).thenReturn(List.of());
		when(api.fetchCancelCompletedOrders(anyString(), anyString(), anyString())).thenReturn(List.of());
		when(api.fetchCancelWithdrawnOrders(anyString(), anyString(), anyString())).thenReturn(List.of());
	}

	private Element element(String xml) throws Exception {
		return DocumentBuilderFactory.newInstance().newDocumentBuilder()
			.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
			.getDocumentElement();
	}

	private Element claimRow(String ordNo, String seq, String clmStat) throws Exception {
		return element("<order>"
			+ "<ordNo>" + ordNo + "</ordNo>"
			+ (seq == null ? "" : "<ordPrdSeq>" + seq + "</ordPrdSeq>")
			+ "<clmStat>" + clmStat + "</clmStat>"
			+ "</order>");
	}

	private Element cancelRow(String ordNo, String seq, String ordCnStatCd) throws Exception {
		return element("<order>"
			+ "<ordNo>" + ordNo + "</ordNo>"
			+ (seq == null ? "" : "<ordPrdSeq>" + seq + "</ordPrdSeq>")
			+ "<ordCnStatCd>" + ordCnStatCd + "</ordCnStatCd>"
			+ "</order>");
	}
}
