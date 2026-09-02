package com.sbshop.agent.core.application.order.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
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

import com.sbshop.agent.core.application.order.dto.MarketLineItemDto;
import com.sbshop.agent.core.application.order.dto.MarketOrderDto;
import com.sbshop.agent.core.application.order.mapper.ElevenstStatusMapper;
import com.sbshop.agent.core.application.order.port.ElevenstOrderApiPort;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.order.enums.ClaimStage;
import com.sbshop.agent.core.domain.order.enums.ClaimType;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;

@ExtendWith(MockitoExtension.class)
class ElevenstLiveFindingsTest {
	private static final String ORD_NO = "20260731088778989";

	@Mock
	private ElevenstOrderApiPort api;

	private final ElevenstStatusMapper statusMapper = new ElevenstStatusMapper();

	@Test
	@DisplayName("D-161: orderlistall이 준 prdNo·prdNm을 상품주문에 싣는다 — 상품 매핑의 유일한 단서다")
	void carriesMarketProductNumberAndName() throws Exception {
		stubLists(List.of(liveShippingRow("1", "315399495342")));
		when(api.fetchProductOrderStatuses(anyString(), anyString()))
			.thenReturn(List.of(liveStatusRow1(), liveStatusRow2()));

		MarketOrderDto dto = fetch();

		assertThat(lineItem(dto, "1").getSellerProductId()).isEqualTo("3282191193");
		assertThat(lineItem(dto, "2").getSellerProductId()).isEqualTo("6124097725");
		assertThat(lineItem(dto, "2").getProductName())
			.isEqualTo("쏜리서치 베이직 뉴트리언트 투퍼데이 60캡슐");
	}

	@Test
	@DisplayName("D-161: 전체 정보 목록이 sellerPrdCd를 주면 그 값이 상품코드로 남는다 — prdNo가 덮지 않는다")
	void detailListStillOwnsSellerProductCode() throws Exception {
		when(api.fetchCompletedOrders(anyString(), anyString(), anyString())).thenReturn(List.of(
			element("<order><ordNo>" + ORD_NO + "</ordNo><ordPrdSeq>2</ordPrdSeq>"
				+ "<sellerPrdCd>230806IHB154</sellerPrdCd><prdNm>목록이 준 이름</prdNm>"
				+ "<ordQty>1</ordQty><selPrc>52800</selPrc><ordAmt>52800</ordAmt></order>")));
		when(api.fetchPackagingOrders(anyString(), anyString(), anyString())).thenReturn(List.of());
		when(api.fetchShippingOrders(anyString(), anyString(), anyString())).thenReturn(List.of());
		when(api.fetchCompletedDeliveryOrders(anyString(), anyString(), anyString())).thenReturn(List.of());
		when(api.fetchProductOrderStatuses(anyString(), anyString()))
			.thenReturn(List.of(liveStatusRow2()));

		MarketOrderDto dto = fetch();

		assertThat(lineItem(dto, "2").getMarketProductCode()).isEqualTo("230806IHB154");
		assertThat(lineItem(dto, "2").getProductName()).isEqualTo("목록이 준 이름");
		assertThat(lineItem(dto, "2").getSellerProductId()).isEqualTo("6124097725");
	}

	@Test
	@DisplayName("정산액은 마켓 실측값(stlPlnAmt)을 그대로 싣는다 — 요율 추정이 필요 없다")
	void carriesActualSettlementAmount() throws Exception {
		stubLists(List.of(liveShippingRow("1", "315399495342")));
		when(api.fetchProductOrderStatuses(anyString(), anyString()))
			.thenReturn(List.of(liveStatusRow1(), liveStatusRow2()));

		MarketOrderDto dto = fetch();

		assertThat(lineItem(dto, "1").getSettlementAmount()).isEqualByComparingTo("49887");
		assertThat(lineItem(dto, "2").getSettlementAmount()).isEqualByComparingTo("45648");
	}

	@Test
	@DisplayName("주문금액은 정산예정금액+판매수수료+마켓할인분담으로 계산된다 — 추측이 아니라 산술")
	void derivesOrderAmountFromActualFigures() throws Exception {
		stubLists(List.of(liveShippingRow("1", "315399495342")));
		when(api.fetchProductOrderStatuses(anyString(), anyString()))
			.thenReturn(List.of(liveStatusRow1(), liveStatusRow2()));

		MarketOrderDto dto = fetch();

		assertThat(lineItem(dto, "1").getTotalAmount()).isEqualByComparingTo("57700");
		assertThat(lineItem(dto, "2").getTotalAmount()).isEqualByComparingTo("52800");
	}

	@Test
	@DisplayName("배송중 목록이 준 ordPrdSeq로 송장을 그 상품주문의 배송에 붙인다")
	void attachesTrackingViaProductOrderSeq() throws Exception {
		stubLists(List.of(liveShippingRow("2", "6079990333509")));
		when(api.fetchProductOrderStatuses(anyString(), anyString()))
			.thenReturn(List.of(
				element("<order><ordNo>" + ORD_NO + "</ordNo><ordPrdSeq>1</ordPrdSeq>"
					+ "<dlvNo>D1</dlvNo><ordPrdStatNm>결제완료</ordPrdStatNm><ordQty>1</ordQty></order>"),
				element("<order><ordNo>" + ORD_NO + "</ordNo><ordPrdSeq>2</ordPrdSeq>"
					+ "<dlvNo>D2</dlvNo><ordPrdStatNm>배송중</ordPrdStatNm><ordQty>1</ordQty></order>")));

		MarketOrderDto dto = fetch();

		assertThat(dto.getShipments()).hasSize(2);
		assertThat(dto.getShipments().stream()
			.filter(sh -> "D2".equals(sh.getMarketShipmentNo())).findFirst().orElseThrow()
			.getTrackingNo()).isEqualTo("6079990333509");
		assertThat(dto.getShipments().stream()
			.filter(sh -> "D1".equals(sh.getMarketShipmentNo())).findFirst().orElseThrow()
			.getTrackingNo()).isNull();
	}

	@Test
	@DisplayName("orderlistall이 준 송장·택배사도 배송에 반영된다")
	void usesTrackingFromStatusApi() throws Exception {
		stubLists(List.of(liveShippingRow("1", "315399495342")));
		when(api.fetchProductOrderStatuses(anyString(), anyString()))
			.thenReturn(List.of(liveStatusRow1(), liveStatusRow2()));

		MarketOrderDto dto = fetch();

		assertThat(dto.getShipments()).hasSize(1);
		assertThat(dto.getShipments().get(0).getTrackingNo()).isEqualTo("315399495342");
	}

	@Test
	@DisplayName("클레임 판정은 상품주문마다 돌려준다 — 배송 단계를 덮지 않고 클레임 축에 따로 실린다(D-270)")
	void resolvesClaimsPerProductOrder() throws Exception {
		when(api.fetchOrderDetail(anyString(), anyString())).thenReturn(List.of(
			element("<order><ordNo>" + ORD_NO + "</ordNo><ordPrdSeq>1</ordPrdSeq>"
				+ "<ordPrdStat>401</ordPrdStat><ordPrdStatNm>배송중</ordPrdStatNm></order>"),
			element("<order><ordNo>" + ORD_NO + "</ordNo><ordPrdSeq>2</ordPrdSeq>"
				+ "<ordPrdStat>801</ordPrdStat><ordPrdStatNm>반품완료</ordPrdStatNm></order>")));

		var state = adapter().resolveMissingOrderState("api-key", ORD_NO);

		assertThat(state.statuses()).isEmpty();
		assertThat(state.claims()).containsOnlyKeys("2");
		assertThat(state.claims().get("2").getClaimType()).isEqualTo(ClaimType.RETURN);
		assertThat(state.claims().get("2").getClaimStage()).isEqualTo(ClaimStage.DONE);
	}

	@Test
	@DisplayName("D-157: 구매확정도 종결로 반영한다 — 클레임만 보던 종전엔 배송중으로 굳었다")
	void purchaseConfirmedIsTerminal() throws Exception {
		when(api.fetchOrderDetail(anyString(), anyString())).thenReturn(List.of(
			element("<order><ordNo>" + ORD_NO + "</ordNo><ordPrdSeq>1</ordPrdSeq>"
				+ "<ordPrdStatNm>구매확정</ordPrdStatNm><invcNo>6079990333504</invcNo></order>")));

		var state = adapter().resolveMissingOrderState("api-key", ORD_NO);

		assertThat(state.statuses()).containsExactly(Map.entry("1", ShippingStatus.CONFIRMED));
		assertThat(state.trackingNos()).containsExactly(Map.entry("1", "6079990333504"));
	}

	@Test
	@DisplayName("진행 중 상태만 있으면 빈 결과 — 사라진 주문에 진행 상태를 되씌우지 않는다")
	void returnsEmptyStatusesWhenOnlyInProgress() throws Exception {
		when(api.fetchOrderDetail(anyString(), anyString())).thenReturn(List.of(
			element("<order><ordNo>" + ORD_NO + "</ordNo><ordPrdSeq>1</ordPrdSeq>"
				+ "<ordPrdStatNm>배송준비중</ordPrdStatNm></order>")));

		assertThat(adapter().resolveMissingOrderState("api-key", ORD_NO).statuses()).isEmpty();
	}

	@Test
	@DisplayName("조회 실패해도 빈 결과 — 예외를 밖으로 내지 않는다")
	void returnsEmptyOnFailure() {
		when(api.fetchOrderDetail(anyString(), anyString()))
			.thenThrow(new RuntimeException("timeout"));

		assertThat(adapter().resolveMissingOrderState("api-key", ORD_NO).isEmpty()).isTrue();
	}

	@Test
	@DisplayName("택배사 코드 00007은 우체국으로 매핑된다 — 라이브 배송중 응답 값")
	void mapsLiveCarrierCode() throws Exception {
		stubLists(List.of(liveShippingRow("1", "6079990333509")));
		when(api.fetchProductOrderStatuses(anyString(), anyString())).thenReturn(List.of(
			element("<order><ordNo>" + ORD_NO + "</ordNo><ordPrdSeq>1</ordPrdSeq>"
				+ "<dlvNo>D1</dlvNo><ordPrdStatNm>배송중</ordPrdStatNm><ordQty>1</ordQty></order>")));

		MarketOrderDto dto = fetch();

		assertThat(dto.getShipments().get(0).getCarrier()).isEqualTo(ShippingCarrier.KOREA_POST);
	}

	private ElevenstOrderAdapter adapter() {
		return new ElevenstOrderAdapter(api, statusMapper);
	}

	private Element element(String xml) throws Exception {
		return DocumentBuilderFactory.newInstance().newDocumentBuilder()
			.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
			.getDocumentElement();
	}

	private Element liveStatusRow1() throws Exception {
		return element("<order><dlvNo>2716448228</dlvNo><invcNo>315399495342</invcNo>"
			+ "<ordCnQty>0</ordCnQty><ordNo>" + ORD_NO + "</ordNo><ordPrdSeq>1</ordPrdSeq>"
			+ "<ordPrdStat>401</ordPrdStat><ordPrdStatNm>배송중</ordPrdStatNm><ordQty>1</ordQty>"
			+ "<prdNm>쏜리서치 Calcium Magnesium Malate 240캡슐</prdNm><prdNo>3282191193</prdNo>"
			+ "<selFee>6253</selFee><stlPlnAmt>49887</stlPlnAmt>"
			+ "<tmallApplyDscAmt>1560</tmallApplyDscAmt></order>");
	}

	private Element liveStatusRow2() throws Exception {
		return element("<order><dlvNo>2716448228</dlvNo><invcNo>315399495342</invcNo>"
			+ "<ordCnQty>0</ordCnQty><ordNo>" + ORD_NO + "</ordNo><ordPrdSeq>2</ordPrdSeq>"
			+ "<ordPrdStat>401</ordPrdStat><ordPrdStatNm>배송중</ordPrdStatNm><ordQty>1</ordQty>"
			+ "<prdNm>쏜리서치 베이직 뉴트리언트 투퍼데이 60캡슐</prdNm><prdNo>6124097725</prdNo>"
			+ "<selFee>5712</selFee><stlPlnAmt>45648</stlPlnAmt>"
			+ "<tmallApplyDscAmt>1440</tmallApplyDscAmt></order>");
	}

	private Element liveShippingRow(String seq, String invcNo) throws Exception {
		return element("<order><dlvEtprsCd>00007</dlvEtprsCd><invcNo>" + invcNo + "</invcNo>"
			+ "<ordNo>" + ORD_NO + "</ordNo><ordPrdSeq>" + seq + "</ordPrdSeq>"
			+ "<sndEndDt>2026-07-31 00:35:27</sndEndDt></order>");
	}

	private MarketCredential credential() {
		MarketCredential c = mock(MarketCredential.class);
		when(c.getAccessKey()).thenReturn("api-key");
		return c;
	}

	private void stubLists(List<Element> shipping) {
		when(api.fetchCompletedOrders(anyString(), anyString(), anyString())).thenReturn(List.of());
		when(api.fetchPackagingOrders(anyString(), anyString(), anyString())).thenReturn(List.of());
		when(api.fetchShippingOrders(anyString(), anyString(), anyString())).thenReturn(shipping);
		when(api.fetchCompletedDeliveryOrders(anyString(), anyString(), anyString())).thenReturn(List.of());
	}

	private MarketOrderDto fetch() {
		return adapter().fetchOrders(credential(), LocalDate.now().minusDays(3), LocalDate.now())
			.get(0);
	}

	private static MarketLineItemDto lineItem(MarketOrderDto dto, String seq) {
		return dto.getShipments().stream().flatMap(s -> s.getLineItems().stream())
			.filter(li -> seq.equals(li.getMarketLineItemNo()))
			.findFirst().orElseThrow(() -> new AssertionError("상품주문 " + seq + " 없음"));
	}
}
