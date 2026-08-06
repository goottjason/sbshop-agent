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

/**
 * 2단계: 11번가 어댑터가 <b>(주문 / 배송 / 상품주문) 3계층</b>으로 정규화한다.
 *
 * <p>발단은 정나영 건이다 — 11번가 `20260731088778989`은 상품주문 2건인데 우리 DB에는 라인아이템이
 * 1건이었고, 그 1건이 순번1의 상품·정산액과 순번2의 송장을 뒤섞어 갖고 있었다. 순번2(52,800원)는
 * 시스템에 존재조차 하지 않아 구매·발주·정산 대상에서 빠졌다.
 *
 * <p>원인은 어댑터가 <b>주문번호로만 키잉</b>했기 때문이다. D-126은 이것을 "목록 신뢰 등급"으로
 * 덮었지만 그 전제(4개 목록이 서로 다른 축을 본다)가 D-130에서 거짓으로 확정됐다 — 목록 행은
 * <b>상품주문 단위</b>이고, 두 목록은 같은 주문의 <b>다른 상품주문</b>을 돌려주고 있었다.
 * 그래서 등급·병합 로직을 걷어내고 상태는 {@code orderlistall}이 직접 알려주는 것을 쓴다.
 */
@ExtendWith(MockitoExtension.class)
class ElevenstThreeTierFetchTest {

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

	/** 결제완료·배송준비중·배송완료 목록 행 — 전체 정보를 준다. */
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

	/** 배송중 목록 행 — 최소 정보(송장·배송번호·수취인)만 준다. */
	private Element shippingRow(String ordNo, String dlvNo, String invcNo) throws Exception {
		return element("<order>"
			+ "<ordNo>" + ordNo + "</ordNo>"
			+ (dlvNo == null ? "" : "<dlvNo>" + dlvNo + "</dlvNo>")
			+ "<invcNo>" + invcNo + "</invcNo><dlvEtprsCd>00034</dlvEtprsCd>"
			+ "<rcvrNm>정나영</rcvrNm></order>");
	}

	/** orderlistall 행 — 상품주문별 상태를 직접 준다. */
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

		// 주문은 하나다 — 수취인·주소는 상품마다 갈리지 않는다.
		assertThat(result).hasSize(1);
		MarketOrderDto dto = result.get(0);
		assertThat(dto.getMarketOrderNo()).isEqualTo(ORD_NO);
		assertThat(dto.getRecipientName()).isEqualTo("정나영");
		assertThat(dto.getCustomsClearanceNo()).isEqualTo("P200032008307");

		assertThat(dto.getShipments()).hasSize(2);
		assertThat(allLineItems(dto)).hasSize(2);

		// 순번1 결제완료 / 순번2 발송완료 — 같은 주문에서 상태가 갈리는 것이 실제다.
		assertThat(lineItem(dto, "1").getStatus()).isEqualTo(ShippingStatus.NEW);
		assertThat(lineItem(dto, "2").getStatus()).isEqualTo(ShippingStatus.SHIPPED);

		// 송장은 순번2가 속한 배송에만 붙는다 — 종전엔 한 행에 섞여 순번1의 송장처럼 보였다.
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
		// 11번가는 묶음배송번호가 같으면 한 번의 발송처리로 나머지 주문번호까지 모두 발송된다(-3308).
		// 즉 같은 dlvNo는 물리적으로 같은 택배 한 상자이고 송장도 하나다.
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

		// 결제완료 목록에서 왔으므로 NEW. 상태 판정이 덜 정확해질 뿐 주문이 사라지지는 않는다.
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

		// 150건 → 100 + 50. 11번가 문서상 상한이 100건이다.
		verify(api, times(2)).fetchProductOrderStatuses(anyString(), anyString());
	}

	@Test
	@DisplayName("라인아이템 레벨 평면 필드는 채우지 않는다 — 3계층이 유일한 원본")
	void doesNotPopulateLineItemLevelFlatFields() throws Exception {
		// 평면 필드에 "첫 상품주문"을 담으면 종전의 키메라 행이 그대로 되살아난다.
		// 주문 공통 필드(수취인·주소·통관번호)는 주문 계층에 남으므로 계속 채운다.
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
		// 주문 계층은 그대로 채워진다.
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

		// 라인아이템은 자기 순번을, 주문은 대표 배송번호를 갖는다(발주확인이 주문 단위로 읽는다).
		assertThat(lineItem(dto, "1").getMarketSpecificData()).containsEntry("ordPrdSeq", "1");
		assertThat(dto.getMarketSpecificData()).containsEntry("dlvNo", "2716448228");
	}
}
