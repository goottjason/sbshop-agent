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
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;

/**
 * 2단계 배포 후 <b>라이브 응답을 직접 확인해서</b> 정정한 사실들을 고정한다 (2026-08-06).
 *
 * <p>배포 후 정나영 건이 2행으로 갈리기는 했지만 상품·금액이 빈 껍데기였고 기존 행이 고아가 됐다.
 * 추측을 멈추고 `orderlistall`·`orderlistalladdr`·`ordservices/shipping` 세 응답을 실물로 떴다.
 * 픽스처는 그 응답에서 그대로 옮긴 것이다.
 *
 * <p>정정된 사실:
 * <ul>
 * <li><b>배송중 목록은 {@code ordPrdSeq}를 준다.</b> "안 준다"는 전제로 구현했던 것이 틀렸다.
 *     대신 {@code dlvNo}를 주지 않는다.
 * <li><b>{@code orderlistall}이 {@code stlPlnAmt}(정산예정금액)·{@code selFee}(판매수수료)를 준다.</b>
 *     정산액을 요율로 추정할 필요가 없다(설계 9.1 · D-122의 근본 해법).
 * <li><b>어느 API도 {@code sellerPrdCd}를 주지 않는다.</b> 상품 매핑은 전체 정보 목록에서만 얻는다.
 * <li><b>{@code orderlistalladdr}도 상품주문마다 한 행이다.</b> 첫 행만 읽던 것은 키메라 오류였다.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ElevenstLiveFindingsTest {

	private static final String ORD_NO = "20260731088778989";

	@Mock
	private ElevenstOrderApiPort api;

	private final ElevenstStatusMapper statusMapper = new ElevenstStatusMapper();

	private ElevenstOrderAdapter adapter() {
		return new ElevenstOrderAdapter(api, statusMapper);
	}

	private Element element(String xml) throws Exception {
		return DocumentBuilderFactory.newInstance().newDocumentBuilder()
			.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
			.getDocumentElement();
	}

	/** 라이브 `orderlistall` 응답 그대로 (정나영 순번1). */
	private Element liveStatusRow1() throws Exception {
		return element("<order><dlvNo>2716448228</dlvNo><invcNo>315399495342</invcNo>"
			+ "<ordCnQty>0</ordCnQty><ordNo>" + ORD_NO + "</ordNo><ordPrdSeq>1</ordPrdSeq>"
			+ "<ordPrdStat>401</ordPrdStat><ordPrdStatNm>배송중</ordPrdStatNm><ordQty>1</ordQty>"
			+ "<prdNm>쏜리서치 Calcium Magnesium Malate 240캡슐</prdNm><prdNo>3282191193</prdNo>"
			+ "<selFee>6253</selFee><stlPlnAmt>49887</stlPlnAmt>"
			+ "<tmallApplyDscAmt>1560</tmallApplyDscAmt></order>");
	}

	/** 라이브 `orderlistall` 응답 그대로 (정나영 순번2). */
	private Element liveStatusRow2() throws Exception {
		return element("<order><dlvNo>2716448228</dlvNo><invcNo>315399495342</invcNo>"
			+ "<ordCnQty>0</ordCnQty><ordNo>" + ORD_NO + "</ordNo><ordPrdSeq>2</ordPrdSeq>"
			+ "<ordPrdStat>401</ordPrdStat><ordPrdStatNm>배송중</ordPrdStatNm><ordQty>1</ordQty>"
			+ "<prdNm>쏜리서치 베이직 뉴트리언트 투퍼데이 60캡슐</prdNm><prdNo>6124097725</prdNo>"
			+ "<selFee>5712</selFee><stlPlnAmt>45648</stlPlnAmt>"
			+ "<tmallApplyDscAmt>1440</tmallApplyDscAmt></order>");
	}

	/** 라이브 `ordservices/shipping` 응답 그대로 — ordPrdSeq는 있고 dlvNo는 없다. */
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

	@Test
	@DisplayName("D-161: orderlistall이 준 prdNo·prdNm을 상품주문에 싣는다 — 상품 매핑의 유일한 단서다")
	void carriesMarketProductNumberAndName() throws Exception {
		// 배송중 단계의 주문은 전체 정보 목록에 없어 sellerPrdCd를 얻을 수 없다. 그러나 orderlistall이
		// prdNo(11번가 상품번호)를 주고 sb_market_registration이 그 값을 이미 보관한다 — 버릴 이유가 없다.
		stubLists(List.of(liveShippingRow("1", "315399495342")));
		when(api.fetchProductOrderStatuses(anyString(), anyString()))
			.thenReturn(List.of(liveStatusRow1(), liveStatusRow2()));

		MarketOrderDto dto = fetch();

		assertThat(lineItem(dto, "1").getSellerProductId()).isEqualTo("3282191193");
		assertThat(lineItem(dto, "2").getSellerProductId()).isEqualTo("6124097725");
		// 상품명도 준다 — 매핑에 실패해도 화면이 비지 않는다.
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
		// 주문 발견은 목록이 한다 — 라이브에서도 배송중 목록이 이 주문을 발견했다.
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
		// 라이브에서 두 상품주문 모두 검증했다: 49887+6253+1560=57700, 45648+5712+1440=52800.
		// 전체 정보 목록의 날짜 창을 지난 주문은 이 경로가 유일한 금액 출처다.
		// 주문 발견은 목록이 한다 — 라이브에서도 배송중 목록이 이 주문을 발견했다.
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
		// 이 목록은 dlvNo를 주지 않는다. 종전 구현은 ordPrdSeq도 안 준다고 전제해
		// 송장을 붙일 길이 없었다 — 실제로는 ordPrdSeq를 준다.
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
		// 순번1의 배송은 송장이 없다 — 다른 상품주문의 송장이 새어들지 않는다.
		assertThat(dto.getShipments().stream()
			.filter(sh -> "D1".equals(sh.getMarketShipmentNo())).findFirst().orElseThrow()
			.getTrackingNo()).isNull();
	}

	@Test
	@DisplayName("orderlistall이 준 송장·택배사도 배송에 반영된다")
	void usesTrackingFromStatusApi() throws Exception {
		// 주문 발견은 목록이 한다 — 라이브에서도 배송중 목록이 이 주문을 발견했다.
		stubLists(List.of(liveShippingRow("1", "315399495342")));
		when(api.fetchProductOrderStatuses(anyString(), anyString()))
			.thenReturn(List.of(liveStatusRow1(), liveStatusRow2()));

		MarketOrderDto dto = fetch();

		// 두 상품주문이 같은 dlvNo → 배송 하나, 송장 하나.
		assertThat(dto.getShipments()).hasSize(1);
		assertThat(dto.getShipments().get(0).getTrackingNo()).isEqualTo("315399495342");
	}

	@Test
	@DisplayName("클레임 판정은 상품주문마다 돌려준다 — 첫 행을 주문 전체에 씌우지 않는다")
	void resolvesClaimsPerProductOrder() throws Exception {
		// orderlistalladdr도 상품주문마다 한 행이다(라이브 확인). 한 상품만 반품신청된 주문에서
		// 첫 행만 읽으면 나머지 상품까지 반품으로 만들거나, 반대로 놓친다.
		when(api.fetchOrderDetail(anyString(), anyString())).thenReturn(List.of(
			element("<order><ordNo>" + ORD_NO + "</ordNo><ordPrdSeq>1</ordPrdSeq>"
				+ "<ordPrdStat>401</ordPrdStat><ordPrdStatNm>배송중</ordPrdStatNm></order>"),
			element("<order><ordNo>" + ORD_NO + "</ordNo><ordPrdSeq>2</ordPrdSeq>"
				+ "<ordPrdStat>801</ordPrdStat><ordPrdStatNm>반품완료</ordPrdStatNm></order>")));

		Map<String, ShippingStatus> claims =
			adapter().resolveMissingOrderState("api-key", ORD_NO).statuses();

		// 순번1은 진행 중(배송중)이라 담기지 않는다 — 사라진 주문에 진행 상태를 되씌우지 않는다.
		assertThat(claims).containsExactly(Map.entry("2", ShippingStatus.RETURNED));
	}

	@Test
	@DisplayName("D-157: 구매확정도 종결로 반영한다 — 클레임만 보던 종전엔 배송중으로 굳었다")
	void purchaseConfirmedIsTerminal() throws Exception {
		// 신고(2026-08-08): 20260720086485068이 마켓에선 구매확정인데 시스템은 SHIPPED였다.
		// 목록을 벗어난 주문의 정상 종결을 버리면 상태가 영원히 갱신되지 않는다.
		when(api.fetchOrderDetail(anyString(), anyString())).thenReturn(List.of(
			element("<order><ordNo>" + ORD_NO + "</ordNo><ordPrdSeq>1</ordPrdSeq>"
				+ "<ordPrdStatNm>구매확정</ordPrdStatNm><invcNo>6079990333504</invcNo></order>")));

		var state = adapter().resolveMissingOrderState("api-key", ORD_NO);

		assertThat(state.statuses()).containsExactly(Map.entry("1", ShippingStatus.DELIVERED));
		// D-158: 같은 응답의 마켓 보유 송장도 함께 거둔다.
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
}
