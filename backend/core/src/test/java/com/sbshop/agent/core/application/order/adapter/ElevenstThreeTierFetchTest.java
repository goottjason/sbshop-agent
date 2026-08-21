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
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.w3c.dom.Element;

import com.sbshop.agent.core.application.order.dto.MarketLineItemDto;
import com.sbshop.agent.core.application.order.dto.MarketOrderDto;
import com.sbshop.agent.core.application.order.dto.MarketShipmentDto;
import com.sbshop.agent.core.application.order.mapper.ElevenstStatusMapper;
import com.sbshop.agent.core.application.order.port.ElevenstOrderApiPort;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.order.enums.ShippingCarrier;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;

@ExtendWith(MockitoExtension.class)
class ElevenstThreeTierFetchTest {
	private static final String ORD_NO = "20260731088778989";

	@Mock
	private ElevenstOrderApiPort api;

	private final ElevenstStatusMapper statusMapper = new ElevenstStatusMapper();

	@Test
	@DisplayName("정나영 건: 개별배송이면 배송 2건·상품주문 2건으로 갈라지고 상태가 각각 다르다")
	void splitsIntoTwoShipmentsWhenDeliveryNosDiffer() throws Exception {
		stubLists(
			List.of(listRow(ORD_NO, "1", "2716448228", "210121IHB011", "쏜리서치 Calcium Magnesium", "57700", null)),
			List.of(shippingRow(ORD_NO, "2716448229", "424079080471")));
		when(api.fetchProductOrderStatuses(anyString(), anyString())).thenReturn(List.of(
			statusRow(ORD_NO, "1", "2716448228", "결제완료"),
			statusRow(ORD_NO, "2", "2716448229", "발송완료")));

		List<MarketOrderDto> result = fetch();

		assertThat(result).hasSize(1);
		MarketOrderDto dto = result.get(0);
		assertThat(dto.getMarketOrderNo()).isEqualTo(ORD_NO);
		assertThat(dto.getRecipientName()).isEqualTo("정나영");
		assertThat(dto.getCustomsClearanceNo()).isEqualTo("P200032008307");

		assertThat(dto.getShipments()).hasSize(2);
		assertThat(allLineItems(dto)).hasSize(2);

		assertThat(lineItem(dto, "1").getStatus()).isEqualTo(ShippingStatus.NEW);
		assertThat(lineItem(dto, "2").getStatus()).isEqualTo(ShippingStatus.SHIPPED);

		MarketShipmentDto shipped = dto.getShipments().stream()
			.filter(s -> "2716448229".equals(s.getMarketShipmentNo())).findFirst().orElseThrow();
		assertThat(shipped.getTrackingNo()).isEqualTo("424079080471");
		assertThat(shipped.getCarrier()).isEqualTo(ShippingCarrier.CJ_LOGISTICS);

		MarketShipmentDto notShipped = dto.getShipments().stream()
			.filter(s -> "2716448228".equals(s.getMarketShipmentNo())).findFirst().orElseThrow();
		assertThat(notShipped.getTrackingNo()).isNull();
	}

	@Test
	@DisplayName("묶음배송이면 배송 1건에 상품주문 2건이 묶인다 — 송장 하나를 공유한다")
	void groupsIntoOneShipmentWhenDeliveryNoShared() throws Exception {
		stubLists(
			List.of(
				listRow(ORD_NO, "1", "2716448228", "210121IHB011", "칼마", "57700", null),
				listRow(ORD_NO, "2", "2716448228", "210121IHB012", "뉴트리언트", "52800", null)),
			List.of(shippingRow(ORD_NO, "2716448228", "424079080471")));
		when(api.fetchProductOrderStatuses(anyString(), anyString())).thenReturn(List.of(
			statusRow(ORD_NO, "1", "2716448228", "발송완료"),
			statusRow(ORD_NO, "2", "2716448228", "발송완료")));

		List<MarketOrderDto> result = fetch();

		MarketOrderDto dto = result.get(0);
		assertThat(dto.getShipments()).hasSize(1);
		MarketShipmentDto shipment = dto.getShipments().get(0);
		assertThat(shipment.getMarketShipmentNo()).isEqualTo("2716448228");
		assertThat(shipment.getTrackingNo()).isEqualTo("424079080471");
		assertThat(shipment.getLineItems()).hasSize(2);
		assertThat(lineItem(dto, "1").getProductName()).isEqualTo("칼마");
		assertThat(lineItem(dto, "2").getProductName()).isEqualTo("뉴트리언트");
	}

	@Test
	@DisplayName("단일 상품 주문은 배송 1 : 상품주문 1이다 — 현재 데이터 전부의 형태")
	void singleItemOrderStaysOneToOne() throws Exception {
		stubLists(
			List.of(listRow("20260801088977098", "1", "2716448300", "210121IHB011", "비타민", "10000", null)),
			List.of());
		when(api.fetchProductOrderStatuses(anyString(), anyString())).thenReturn(List.of(
			statusRow("20260801088977098", "1", "2716448300", "결제완료")));

		List<MarketOrderDto> result = fetch();

		assertThat(result).hasSize(1);
		assertThat(result.get(0).getShipments()).hasSize(1);
		assertThat(allLineItems(result.get(0))).hasSize(1);
		MarketLineItemDto li = allLineItems(result.get(0)).get(0);
		assertThat(li.getMarketLineItemNo()).isEqualTo("1");
		assertThat(li.getMarketProductCode()).isEqualTo("210121IHB011");
		assertThat(li.getStatus()).isEqualTo(ShippingStatus.NEW);
		assertThat(li.getTotalAmount()).isEqualByComparingTo("10000");
	}

	@Test
	@DisplayName("orderlistall이 빈 응답이면 목록 소속으로 상태를 폴백한다 — 한 API가 동기화를 무력화하지 않는다")
	void fallsBackToListSourceStatusWhenStatusApiEmpty() throws Exception {
		stubLists(
			List.of(listRow(ORD_NO, "1", "2716448228", "210121IHB011", "칼마", "57700", null)),
			List.of());
		when(api.fetchProductOrderStatuses(anyString(), anyString())).thenReturn(List.of());

		List<MarketOrderDto> result = fetch();

		assertThat(lineItem(result.get(0), "1").getStatus()).isEqualTo(ShippingStatus.NEW);
	}

	@Test
	@DisplayName("orderlistall이 예외를 던져도 주문은 반환된다")
	void survivesStatusApiFailure() throws Exception {
		stubLists(
			List.of(listRow(ORD_NO, "1", "2716448228", "210121IHB011", "칼마", "57700", null)),
			List.of());
		when(api.fetchProductOrderStatuses(anyString(), anyString()))
			.thenThrow(new RuntimeException("11번가 상품주문상태 조회 실패: timeout"));

		List<MarketOrderDto> result = fetch();

		assertThat(result).hasSize(1);
		assertThat(lineItem(result.get(0), "1").getStatus()).isEqualTo(ShippingStatus.NEW);
	}

	@Test
	@DisplayName("배송번호가 없으면 주문번호로 대체한다 — 배송 없는 주문은 만들지 않는다")
	void fallsBackToOrderNoAsShipmentNo() throws Exception {
		stubLists(
			List.of(listRow(ORD_NO, "1", null, "210121IHB011", "칼마", "57700", null)),
			List.of());
		when(api.fetchProductOrderStatuses(anyString(), anyString())).thenReturn(List.of(
			statusRow(ORD_NO, "1", null, "결제완료")));

		List<MarketOrderDto> result = fetch();

		assertThat(result.get(0).getShipments()).hasSize(1);
		assertThat(result.get(0).getShipments().get(0).getMarketShipmentNo()).isEqualTo(ORD_NO);
	}

	@Test
	@DisplayName("배송중 행에 배송번호가 없어도 배송이 하나면 그 배송에 송장을 붙인다")
	void attachesTrackingToSoleShipmentWhenShippingRowLacksDeliveryNo() throws Exception {
		stubLists(
			List.of(listRow(ORD_NO, "1", "2716448228", "210121IHB011", "칼마", "57700", null)),
			List.of(shippingRow(ORD_NO, null, "424079080471")));
		when(api.fetchProductOrderStatuses(anyString(), anyString())).thenReturn(List.of(
			statusRow(ORD_NO, "1", "2716448228", "발송완료")));

		List<MarketOrderDto> result = fetch();

		assertThat(result.get(0).getShipments()).hasSize(1);
		assertThat(result.get(0).getShipments().get(0).getTrackingNo()).isEqualTo("424079080471");
	}

	@Test
	@DisplayName("상태조회는 주문번호를 100건씩 나눠 호출한다")
	void chunksStatusLookupBy100() throws Exception {
		List<Element> rows = new ArrayList<>();
		for (int i = 0; i < 150; i++) {
			rows.add(listRow("2026080108897" + String.format("%04d", i), "1", "D" + i,
				"210121IHB011", "비타민", "10000", null));
		}
		stubLists(rows, List.of());
		when(api.fetchProductOrderStatuses(anyString(), anyString())).thenReturn(List.of());

		fetch();

		verify(api, times(2)).fetchProductOrderStatuses(anyString(), anyString());
	}

	@Test
	@DisplayName("라인아이템 레벨 평면 필드는 채우지 않는다 — 3계층이 유일한 원본")
	void doesNotPopulateLineItemLevelFlatFields() throws Exception {
		stubLists(
			List.of(
				listRow(ORD_NO, "1", "2716448228", "210121IHB011", "칼마", "57700", null),
				listRow(ORD_NO, "2", "2716448229", "210121IHB012", "뉴트리언트", "52800", "424079080471")),
			List.of());
		when(api.fetchProductOrderStatuses(anyString(), anyString())).thenReturn(List.of(
			statusRow(ORD_NO, "1", "2716448228", "결제완료"),
			statusRow(ORD_NO, "2", "2716448229", "발송완료")));

		MarketOrderDto dto = fetch().get(0);

		assertThat(dto.getStatus()).isNull();
		assertThat(dto.getTrackingNo()).isNull();
		assertThat(dto.getMarketProductCode()).isNull();
		assertThat(dto.getProductName()).isNull();
		assertThat(dto.getRecipientName()).isEqualTo("정나영");
		assertThat(dto.getZipcode()).isEqualTo("07997");
	}

	@Test
	@DisplayName("배송번호는 marketSpecificData에 남는다 — 발주확인·발송처리가 쓴다")
	void keepsDeliveryNoInMarketSpecificData() throws Exception {
		stubLists(
			List.of(listRow(ORD_NO, "1", "2716448228", "210121IHB011", "칼마", "57700", null)),
			List.of());
		when(api.fetchProductOrderStatuses(anyString(), anyString())).thenReturn(List.of(
			statusRow(ORD_NO, "1", "2716448228", "결제완료")));

		MarketOrderDto dto = fetch().get(0);

		assertThat(lineItem(dto, "1").getMarketSpecificData()).containsEntry("ordPrdSeq", "1");
		assertThat(dto.getMarketSpecificData()).containsEntry("dlvNo", "2716448228");
	}

	private ElevenstOrderAdapter adapter() {
		return new ElevenstOrderAdapter(api, statusMapper);
	}

	private Element element(String xml) throws Exception {
		return DocumentBuilderFactory.newInstance().newDocumentBuilder()
			.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
			.getDocumentElement();
	}

	private Element listRow(String ordNo, String seq, String dlvNo, String sellerPrdCd,
		String prdNm, String price, String invcNo) throws Exception {
		return element("<order>"
			+ "<ordNo>" + ordNo + "</ordNo>"
			+ "<ordPrdSeq>" + seq + "</ordPrdSeq>"
			+ (dlvNo == null ? "" : "<dlvNo>" + dlvNo + "</dlvNo>")
			+ "<sellerPrdCd>" + sellerPrdCd + "</sellerPrdCd>"
			+ "<prdNm>" + prdNm + "</prdNm>"
			+ "<ordQty>1</ordQty><selPrc>" + price + "</selPrc><ordAmt>" + price + "</ordAmt>"
			+ (invcNo == null ? "" : "<invcNo>" + invcNo + "</invcNo><dlvEtprsCd>00034</dlvEtprsCd>")
			+ "<rcvrNm>정나영</rcvrNm><rcvrMailNo>07997</rcvrMailNo>"
			+ "<rcvrBaseAddr>서울특별시 양천구</rcvrBaseAddr><rcvrDtlsAddr>101동</rcvrDtlsAddr>"
			+ "<psnCscUniqNo>P200032008307</psnCscUniqNo>"
			+ "</order>");
	}

	private Element shippingRow(String ordNo, String dlvNo, String invcNo) throws Exception {
		return element("<order>"
			+ "<ordNo>" + ordNo + "</ordNo>"
			+ (dlvNo == null ? "" : "<dlvNo>" + dlvNo + "</dlvNo>")
			+ "<invcNo>" + invcNo + "</invcNo><dlvEtprsCd>00034</dlvEtprsCd>"
			+ "<rcvrNm>정나영</rcvrNm></order>");
	}

	private Element statusRow(String ordNo, String seq, String dlvNo, String statNm) throws Exception {
		return element("<order>"
			+ "<ordNo>" + ordNo + "</ordNo>"
			+ "<ordPrdSeq>" + seq + "</ordPrdSeq>"
			+ (dlvNo == null ? "" : "<dlvNo>" + dlvNo + "</dlvNo>")
			+ "<ordPrdStatNm>" + statNm + "</ordPrdStatNm>"
			+ "<ordQty>1</ordQty></order>");
	}

	private MarketCredential credential() {
		MarketCredential c = mock(MarketCredential.class);
		when(c.getAccessKey()).thenReturn("api-key");
		return c;
	}

	private void stubLists(List<Element> complete, List<Element> shipping) {
		when(api.fetchCompletedOrders(anyString(), anyString(), anyString())).thenReturn(complete);
		when(api.fetchPackagingOrders(anyString(), anyString(), anyString())).thenReturn(List.of());
		when(api.fetchShippingOrders(anyString(), anyString(), anyString())).thenReturn(shipping);
		when(api.fetchCompletedDeliveryOrders(anyString(), anyString(), anyString())).thenReturn(List.of());
	}

	private List<MarketOrderDto> fetch() {
		return adapter().fetchOrders(credential(), LocalDate.now().minusDays(3), LocalDate.now());
	}

	private static MarketLineItemDto lineItem(MarketOrderDto dto, String seq) {
		return dto.getShipments().stream()
			.flatMap(s -> s.getLineItems().stream())
			.filter(li -> seq.equals(li.getMarketLineItemNo()))
			.findFirst().orElseThrow(() -> new AssertionError("상품주문 " + seq + " 없음"));
	}

	private static List<MarketLineItemDto> allLineItems(MarketOrderDto dto) {
		List<MarketLineItemDto> all = new ArrayList<>();
		dto.getShipments().forEach(s -> all.addAll(s.getLineItems()));
		return all;
	}
}
