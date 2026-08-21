package com.sbshop.agent.core.application.order.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
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
import com.sbshop.agent.core.application.order.mapper.ElevenstStatusMapper;
import com.sbshop.agent.core.application.order.port.ElevenstOrderApiPort;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;

@ExtendWith(MockitoExtension.class)
class ElevenstStatusAxisPrecedenceTest {
	@Mock
	private ElevenstOrderApiPort elevenstOrderApiPort;

	private final ElevenstStatusMapper statusMapper = new ElevenstStatusMapper();

	@Test
	@DisplayName("서로 다른 주문은 섞이지 않는다")
	void distinctOrdersAreNotMerged() throws Exception {
		stubEmptyExcept(
			List.of(completeOrder("20260731088778989")),
			List.of(shippingOrder("20260801088977098", "363082000865")));
		when(elevenstOrderApiPort.fetchProductOrderStatuses(anyString(), anyString()))
			.thenReturn(List.of());

		List<MarketOrderDto> result = fetch();

		assertThat(result).hasSize(2);
		assertThat(result).extracting(MarketOrderDto::getMarketOrderNo)
			.containsExactlyInAnyOrder("20260731088778989", "20260801088977098");
	}

	@Test
	@DisplayName("여러 주간 chunk에 걸쳐 같은 주문이 나와도 한 건으로 모인다")
	void mergesAcrossWeeklyChunks() throws Exception {
		String ordNo = "20260731088778989";
		when(elevenstOrderApiPort.fetchCompletedOrders(anyString(), anyString(), anyString()))
			.thenReturn(List.of(completeOrder(ordNo)), List.of(), List.of(), List.of(), List.of());
		when(elevenstOrderApiPort.fetchPackagingOrders(anyString(), anyString(), anyString()))
			.thenReturn(List.of());
		when(elevenstOrderApiPort.fetchShippingOrders(anyString(), anyString(), anyString()))
			.thenReturn(List.of(), List.of(), List.of(), List.of(), List.of(shippingOrder(ordNo, "424079080471")));
		when(elevenstOrderApiPort.fetchCompletedDeliveryOrders(anyString(), anyString(), anyString()))
			.thenReturn(List.of());
		when(elevenstOrderApiPort.fetchProductOrderStatuses(anyString(), anyString()))
			.thenReturn(List.of());

		List<MarketOrderDto> result = new ElevenstOrderAdapter(elevenstOrderApiPort, statusMapper)
			.fetchOrders(credential(), LocalDate.now().minusDays(29), LocalDate.now());

		assertThat(result).hasSize(1);
		assertThat(soleTracking(result.get(0))).isEqualTo("424079080471");
	}

	@Test
	@DisplayName("결제완료·배송중 목록이 같은 상품주문을 줘도 상태는 orderlistall이 정한다")
	void statusComesFromStatusApiNotListMembership() throws Exception {
		String ordNo = "20260731088778989";
		stubEmptyExcept(List.of(completeOrder(ordNo)), List.of(shippingOrder(ordNo, "424079080471")));
		when(elevenstOrderApiPort.fetchProductOrderStatuses(anyString(), anyString()))
			.thenReturn(List.of(element("<order><ordNo>" + ordNo + "</ordNo>"
				+ "<ordPrdSeq>1</ordPrdSeq><dlvNo>2716448228</dlvNo>"
				+ "<ordPrdStatNm>결제완료</ordPrdStatNm><ordQty>1</ordQty></order>")));

		List<MarketOrderDto> result = fetch();

		assertThat(result).hasSize(1);
		assertThat(soleStatus(result.get(0))).isEqualTo(ShippingStatus.NEW);
		assertThat(soleTracking(result.get(0))).isEqualTo("424079080471");
	}

	@Test
	@DisplayName("배송중 목록에만 있고 상태조회도 답이 없으면 목록 소속으로 폴백한다")
	void shippingOnlyFallsBackToShipped() throws Exception {
		String ordNo = "20260801088977098";
		stubEmptyExcept(List.of(), List.of(shippingOrder(ordNo, "363082000865")));
		when(elevenstOrderApiPort.fetchProductOrderStatuses(anyString(), anyString()))
			.thenReturn(List.of());

		List<MarketOrderDto> result = fetch();

		assertThat(result).hasSize(1);
		assertThat(soleStatus(result.get(0))).isEqualTo(ShippingStatus.SHIPPED);
		assertThat(soleTracking(result.get(0))).isEqualTo("363082000865");
		assertThat(result.get(0).getShipments().get(0).getLineItems().get(0).getMarketLineItemNo())
			.isNull();
	}

	private Element element(String xml) throws Exception {
		return DocumentBuilderFactory.newInstance().newDocumentBuilder()
			.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
			.getDocumentElement();
	}

	private Element completeOrder(String ordNo) throws Exception {
		return element("<order><ordNo>" + ordNo + "</ordNo><prdNm>비타민</prdNm>"
			+ "<rcvrNm>정나영</rcvrNm><ordQty>1</ordQty><selPrc>10000</selPrc><ordAmt>10000</ordAmt>"
			+ "<ordPrdSeq>1</ordPrdSeq><dlvNo>2716448228</dlvNo></order>");
	}

	private Element shippingOrder(String ordNo, String invcNo) throws Exception {
		return element("<order><ordNo>" + ordNo + "</ordNo><invcNo>" + invcNo + "</invcNo>"
			+ "<dlvEtprsCd>00034</dlvEtprsCd><rcvrNm>정나영</rcvrNm>"
			+ "<dlvNo>2716448228</dlvNo></order>");
	}

	private MarketCredential credential() {
		MarketCredential credential = mock(MarketCredential.class);
		when(credential.getAccessKey()).thenReturn("api-key");
		return credential;
	}

	private void stubEmptyExcept(List<Element> complete, List<Element> shipping) {
		when(elevenstOrderApiPort.fetchCompletedOrders(anyString(), anyString(), anyString()))
			.thenReturn(complete);
		when(elevenstOrderApiPort.fetchPackagingOrders(anyString(), anyString(), anyString()))
			.thenReturn(List.of());
		when(elevenstOrderApiPort.fetchShippingOrders(anyString(), anyString(), anyString()))
			.thenReturn(shipping);
		when(elevenstOrderApiPort.fetchCompletedDeliveryOrders(anyString(), anyString(), anyString()))
			.thenReturn(List.of());
	}

	private List<MarketOrderDto> fetch() {
		return new ElevenstOrderAdapter(elevenstOrderApiPort, statusMapper)
			.fetchOrders(credential(), LocalDate.now().minusDays(3), LocalDate.now());
	}

	private static ShippingStatus soleStatus(MarketOrderDto dto) {
		List<MarketLineItemDto> items = dto.getShipments().stream()
			.flatMap(s -> s.getLineItems().stream()).toList();
		assertThat(items).hasSize(1);
		return items.get(0).getStatus();
	}

	private static String soleTracking(MarketOrderDto dto) {
		assertThat(dto.getShipments()).hasSize(1);
		return dto.getShipments().get(0).getTrackingNo();
	}
}
