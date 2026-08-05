package com.sbshop.agent.core.application.order.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.application.order.dto.MarketOrderDto;
import com.sbshop.agent.core.application.order.mapper.ElevenstStatusMapper;
import com.sbshop.agent.core.application.order.port.ElevenstOrderApiPort;
import com.sbshop.agent.core.domain.market.MarketCredential;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
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

/**
 * D-126: 11번가의 4개 주문 목록은 상호배타적이지 않다(2026-08-05 라이브 확증).
 *
 * <p>{@code /rest/ordservices/shipping}(배송중) 목록은 <b>송장이 등록된 주문</b>을 돌려주며,
 * 진행상태가 여전히 결제완료여도 포함된다. 실제 사례: 주문 20260731088778989(정나영)은
 * 11번가 판매자센터에서 <b>결제완료</b>인데 송장만 등록돼 있어, 결제완료 목록과 배송중 목록에
 * <b>동시에</b> 나타났다. 어댑터는 네 목록을 단순 concat 했고 배송중이 뒤에 처리돼
 * (마지막 승) 결제완료 주문이 그리드에서 "배송중"으로 보였다.
 *
 * <p>정정된 규칙 — <b>진행상태 축이 배송 축을 이긴다</b>:
 * 결제완료·배송준비중 목록은 11번가의 주문 진행상태를 직접 뜻하므로 확정적이고,
 * 배송중 목록은 송장 보유 사실만 뜻하므로 상태 근거가 되지 못한다. 따라서 충돌 시
 * 진행상태를 채택하되, <b>송장·택배사는 배송 목록에서 병합</b>해 정보를 잃지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class ElevenstStatusAxisPrecedenceTest {

	@Mock
	private ElevenstOrderApiPort elevenstOrderApiPort;

	private final ElevenstStatusMapper statusMapper = new ElevenstStatusMapper();

	private Element element(String xml) throws Exception {
		return DocumentBuilderFactory.newInstance()
			.newDocumentBuilder()
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
			+ "<dlvEtprsCd>00034</dlvEtprsCd><rcvrNm>정나영</rcvrNm></order>");
	}

	private MarketCredential credential() {
		MarketCredential credential = mock(MarketCredential.class);
		when(credential.getAccessKey()).thenReturn("api-key");
		return credential;
	}

	/** 배송중 목록만 값을 주고 나머지는 빈 목록으로 두는 기본 스텁. */
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

	@Test
	@DisplayName("[D-126] 결제완료·배송중 목록에 동시에 나오면 결제완료(NEW)가 이기고 송장은 보존된다")
	void completeListWinsOverShippingList_butKeepsTracking() throws Exception {
		String ordNo = "20260731088778989";
		stubEmptyExcept(List.of(completeOrder(ordNo)), List.of(shippingOrder(ordNo, "424079080471")));

		ElevenstOrderAdapter adapter = new ElevenstOrderAdapter(elevenstOrderApiPort, statusMapper);
		List<MarketOrderDto> result = adapter.fetchOrders(
			credential(), LocalDate.now().minusDays(3), LocalDate.now());

		// 주문번호당 한 건으로 병합돼야 한다 (과거엔 NEW·SHIPPED 두 건이 나와 뒤가 이겼다).
		assertThat(result).hasSize(1);
		MarketOrderDto dto = result.get(0);
		assertThat(dto.getMarketOrderNo()).isEqualTo(ordNo);
		assertThat(dto.getStatus()).isEqualTo(ShippingStatus.NEW);
		// 상태는 진행상태 축을 따르되, 배송 목록이 준 송장 정보는 잃지 않는다.
		assertThat(dto.getTrackingNo()).isEqualTo("424079080471");
		// 결제완료 목록이 준 전체 데이터(상품명·수량)도 유지된다 — 배송중 목록은 최소 정보뿐이다.
		assertThat(dto.getProductName()).isEqualTo("비타민");
		assertThat(dto.getQuantity()).isEqualTo(1);
	}

	@Test
	@DisplayName("[D-126] 배송중 목록에만 있으면 종전대로 SHIPPED로 매핑된다")
	void shippingOnlyStaysShipped() throws Exception {
		String ordNo = "20260801088977098";
		stubEmptyExcept(List.of(), List.of(shippingOrder(ordNo, "363082000865")));

		ElevenstOrderAdapter adapter = new ElevenstOrderAdapter(elevenstOrderApiPort, statusMapper);
		List<MarketOrderDto> result = adapter.fetchOrders(
			credential(), LocalDate.now().minusDays(3), LocalDate.now());

		assertThat(result).hasSize(1);
		assertThat(result.get(0).getStatus()).isEqualTo(ShippingStatus.SHIPPED);
		assertThat(result.get(0).getTrackingNo()).isEqualTo("363082000865");
	}

	@Test
	@DisplayName("[D-126] 배송완료 목록은 배송중 목록을 이긴다 (배송 축 안에서는 더 진행된 쪽)")
	void deliveredWinsOverShipping() throws Exception {
		String ordNo = "20260726087776259";
		when(elevenstOrderApiPort.fetchCompletedOrders(anyString(), anyString(), anyString()))
			.thenReturn(List.of());
		when(elevenstOrderApiPort.fetchPackagingOrders(anyString(), anyString(), anyString()))
			.thenReturn(List.of());
		when(elevenstOrderApiPort.fetchShippingOrders(anyString(), anyString(), anyString()))
			.thenReturn(List.of(shippingOrder(ordNo, "424410280092")));
		when(elevenstOrderApiPort.fetchCompletedDeliveryOrders(anyString(), anyString(), anyString()))
			.thenReturn(List.of(completeOrder(ordNo)));

		ElevenstOrderAdapter adapter = new ElevenstOrderAdapter(elevenstOrderApiPort, statusMapper);
		List<MarketOrderDto> result = adapter.fetchOrders(
			credential(), LocalDate.now().minusDays(3), LocalDate.now());

		assertThat(result).hasSize(1);
		assertThat(result.get(0).getStatus()).isEqualTo(ShippingStatus.DELIVERED);
		assertThat(result.get(0).getTrackingNo()).isEqualTo("424410280092");
	}

	@Test
	@DisplayName("[D-126] 서로 다른 주문은 병합되지 않는다")
	void distinctOrdersAreNotMerged() throws Exception {
		stubEmptyExcept(
			List.of(completeOrder("20260731088778989")),
			List.of(shippingOrder("20260801088977098", "363082000865")));

		ElevenstOrderAdapter adapter = new ElevenstOrderAdapter(elevenstOrderApiPort, statusMapper);
		List<MarketOrderDto> result = adapter.fetchOrders(
			credential(), LocalDate.now().minusDays(3), LocalDate.now());

		assertThat(result).hasSize(2);
		assertThat(result).extracting(MarketOrderDto::getStatus)
			.containsExactlyInAnyOrder(ShippingStatus.NEW, ShippingStatus.SHIPPED);
	}

	@Test
	@DisplayName("[D-126] 배송준비중(발주확인 완료)도 배송중 목록을 이긴다")
	void packagingWinsOverShipping() throws Exception {
		String ordNo = "20260730088728533";
		when(elevenstOrderApiPort.fetchCompletedOrders(anyString(), anyString(), anyString()))
			.thenReturn(List.of());
		when(elevenstOrderApiPort.fetchPackagingOrders(anyString(), anyString(), anyString()))
			.thenReturn(List.of(completeOrder(ordNo)));
		when(elevenstOrderApiPort.fetchShippingOrders(anyString(), anyString(), anyString()))
			.thenReturn(List.of(shippingOrder(ordNo, "6063465794604")));
		when(elevenstOrderApiPort.fetchCompletedDeliveryOrders(anyString(), anyString(), anyString()))
			.thenReturn(List.of());

		ElevenstOrderAdapter adapter = new ElevenstOrderAdapter(elevenstOrderApiPort, statusMapper);
		List<MarketOrderDto> result = adapter.fetchOrders(
			credential(), LocalDate.now().minusDays(3), LocalDate.now());

		assertThat(result).hasSize(1);
		assertThat(result.get(0).getStatus()).isEqualTo(ShippingStatus.PREPARING);
		assertThat(result.get(0).getTrackingNo()).isEqualTo("6063465794604");
	}

	@Test
	@DisplayName("[D-126] 여러 주간 chunk에 걸쳐 같은 주문이 나와도 한 건으로 병합된다")
	void mergesAcrossWeeklyChunks() throws Exception {
		String ordNo = "20260731088778989";
		// 30일 조회는 7일 단위 5 chunk로 분할된다. 결제완료는 첫 chunk에서만, 배송중은 마지막 chunk에서
		// 나오는 라이브 패턴(배송중 목록의 날짜 축이 주문일이 아니다)을 재현한다.
		when(elevenstOrderApiPort.fetchCompletedOrders(anyString(), anyString(), anyString()))
			.thenReturn(List.of(completeOrder(ordNo)), List.of(), List.of(), List.of(), List.of());
		when(elevenstOrderApiPort.fetchPackagingOrders(anyString(), anyString(), anyString()))
			.thenReturn(List.of());
		when(elevenstOrderApiPort.fetchShippingOrders(anyString(), anyString(), anyString()))
			.thenReturn(List.of(), List.of(), List.of(), List.of(), List.of(shippingOrder(ordNo, "424079080471")));
		when(elevenstOrderApiPort.fetchCompletedDeliveryOrders(anyString(), anyString(), anyString()))
			.thenReturn(List.of());

		ElevenstOrderAdapter adapter = new ElevenstOrderAdapter(elevenstOrderApiPort, statusMapper);
		List<MarketOrderDto> result = adapter.fetchOrders(
			credential(), LocalDate.now().minusDays(30), LocalDate.now());

		assertThat(result).hasSize(1);
		assertThat(result.get(0).getStatus()).isEqualTo(ShippingStatus.NEW);
		assertThat(result.get(0).getTrackingNo()).isEqualTo("424079080471");
	}

	@Test
	@DisplayName("[D-126] 병합해도 marketSpecificData(발주확인·배송번호)는 보존된다")
	void mergeKeepsMarketSpecificData() throws Exception {
		String ordNo = "20260731088778989";
		stubEmptyExcept(List.of(completeOrder(ordNo)), List.of(shippingOrder(ordNo, "424079080471")));

		ElevenstOrderAdapter adapter = new ElevenstOrderAdapter(elevenstOrderApiPort, statusMapper);
		List<MarketOrderDto> result = adapter.fetchOrders(
			credential(), LocalDate.now().minusDays(3), LocalDate.now());

		Map<String, Object> marketData = result.get(0).getMarketSpecificData();
		assertThat(marketData).containsEntry("ordPrdSeq", "1").containsEntry("dlvNo", "2716448228");
	}
}
