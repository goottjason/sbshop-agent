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

/**
 * <b>D-126의 "목록 신뢰 등급"은 2단계에서 제거됐다.</b> 이 파일은 그 자리에 남는 계약을 지킨다.
 *
 * <p>D-126은 "진행상태 축이 배송 축을 이긴다"는 등급 규칙으로 증상을 덮었다. 그 전제는
 * <i>네 목록이 같은 주문의 서로 다른 축을 본다</i>였는데, D-130에서 거짓으로 확정됐다 —
 * 목록 행은 <b>상품주문 단위</b>이고, 결제완료 목록과 배송중 목록은 같은 주문의 <b>서로 다른
 * 상품주문</b>을 돌려주고 있었다. 이길 필요가 없는 싸움을 심판하고 있었던 것이다.
 *
 * <p>지금은 상태를 {@code claimservice/orderlistall}의 {@code ordPrdStatNm}으로 상품주문마다
 * 직접 판정하므로 등급이 사라졌다. 3계층 변환 계약은 {@link ElevenstThreeTierFetchTest}가 지킨다.
 *
 * <p>여기 남는 것은 <b>등급과 무관하게 여전히 유효한 두 가지</b>다:
 * 서로 다른 주문이 섞이지 않는 것, 그리고 같은 주문이 여러 주간 chunk에 걸쳐 나와도 한 건이 되는 것.
 * 후자는 배송중 목록의 날짜 축이 주문일이 아니라서 실제로 일어난다.
 */
@ExtendWith(MockitoExtension.class)
class ElevenstStatusAxisPrecedenceTest {

	@Mock
	private ElevenstOrderApiPort elevenstOrderApiPort;

	private final ElevenstStatusMapper statusMapper = new ElevenstStatusMapper();

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
		when(elevenstOrderApiPort.fetchProductOrderStatuses(anyString(), anyString()))
			.thenReturn(List.of());

		List<MarketOrderDto> result = new ElevenstOrderAdapter(elevenstOrderApiPort, statusMapper)
			.fetchOrders(credential(), LocalDate.now().minusDays(29), LocalDate.now());

		assertThat(result).hasSize(1);
		// 같은 배송번호이므로 배송 하나에 상품주문 하나 — 결제완료가 준 상품주문에 배송중이 준 송장이 붙는다.
		assertThat(soleTracking(result.get(0))).isEqualTo("424079080471");
	}

	@Test
	@DisplayName("결제완료·배송중 목록이 같은 상품주문을 줘도 상태는 orderlistall이 정한다")
	void statusComesFromStatusApiNotListMembership() throws Exception {
		// 종전에는 여기서 목록 등급이 싸웠다. 지금은 심판이 필요 없다 — 상태의 출처가 하나다.
		String ordNo = "20260731088778989";
		stubEmptyExcept(List.of(completeOrder(ordNo)), List.of(shippingOrder(ordNo, "424079080471")));
		when(elevenstOrderApiPort.fetchProductOrderStatuses(anyString(), anyString()))
			.thenReturn(List.of(element("<order><ordNo>" + ordNo + "</ordNo>"
				+ "<ordPrdSeq>1</ordPrdSeq><dlvNo>2716448228</dlvNo>"
				+ "<ordPrdStatNm>결제완료</ordPrdStatNm><ordQty>1</ordQty></order>")));

		List<MarketOrderDto> result = fetch();

		assertThat(result).hasSize(1);
		assertThat(soleStatus(result.get(0))).isEqualTo(ShippingStatus.NEW);
		// 상태를 바로잡느라 사실을 지우지 않는다 — "결제완료인데 송장 있음"이 이 주문의 실제 상태다.
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

		// 이 목록은 ordPrdSeq를 주지 않는다. 드롭하면 주문이 조용히 사라지므로 식별자 없는
		// 라인아이템 1건으로 낸다 — 종전(주문번호로만 키잉)과 같은 형태다.
		assertThat(result).hasSize(1);
		assertThat(soleStatus(result.get(0))).isEqualTo(ShippingStatus.SHIPPED);
		assertThat(soleTracking(result.get(0))).isEqualTo("363082000865");
		assertThat(result.get(0).getShipments().get(0).getLineItems().get(0).getMarketLineItemNo())
			.isNull();
	}
}
