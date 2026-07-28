package com.sbshop.agent.worker.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.order.service.MarketplaceShippingService;
import com.sbshop.agent.core.config.EmailAccountProperties;
import com.sbshop.agent.core.domain.order.OrderLineItem;
import com.sbshop.agent.core.domain.order.enums.ShippingStatus;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;
import com.sbshop.agent.core.domain.order.vo.ShippingData;
import com.sbshop.agent.core.domain.order.vo.SourcingData;

/**
 * 실구매가 자동 주입이 배송 큐에 종속되지 않아야 한다.
 * 배송완료(DELIVERED) 주문도 실구매가가 비어 있으면 확인메일 검색 대상에 포함돼야 한다.
 */
@ExtendWith(MockitoExtension.class)
class EmailFetcherConfirmationQueueTest {

	@Mock
	EmailAccountProperties properties;
	@Mock
	OrderEmailParser parser;
	@Mock
	OrderLineItemRepository orderLineItemRepository;
	@Mock
	OrderRepository orderRepository;
	@Mock
	MarketplaceShippingService marketplaceShippingService;
	@Mock
	ActionLogService actionLogService;

	@InjectMocks
	EmailFetcherService service;

	@Captor
	ArgumentCaptor<OrderLineItem> savedItemCaptor;

	private OrderLineItem item(String orderNo, ShippingStatus status, BigDecimal amount) {
		return OrderLineItem.builder()
			.orderId(1L)
			.quantity(1)
			.sourcingData(SourcingData.builder()
				.sourcingVendor("IHB")
				.sourcingOrderNo(orderNo)
				.sourcingAmount(amount)
				.build())
			.shippingData(ShippingData.builder().shippingStatus(status).build())
			.build();
	}

	private EmailAccountProperties.Account account() {
		EmailAccountProperties.Account a = new EmailAccountProperties.Account();
		a.setUsername("central@gmail.com");
		a.setHost("imap.gmail.com");
		return a;
	}

	@Test
	@DisplayName("배송완료라 배송 큐에 없는 주문도 실구매가가 비면 검색 계획에 포함된다")
	void deliveredItemWithoutAmountIsSearched() {
		OrderLineItem preparing = item("111", ShippingStatus.PREPARING, null);
		OrderLineItem deliveredNoAmount = item("222", ShippingStatus.DELIVERED, null);

		Map<EmailAccountProperties.Account, Set<String>> plan = service.buildSearchPlan(
			List.of(preparing), List.of(deliveredNoAmount), List.of(account()));

		Set<String> orderNos = plan.values().stream().flatMap(Set::stream).collect(Collectors.toSet());
		assertThat(orderNos).containsExactlyInAnyOrder("111", "222");
	}

	@Test
	@DisplayName("두 큐에 같은 주문이 들어와도 검색은 1회로 합쳐진다")
	void duplicateOrderNoIsDeduplicated() {
		OrderLineItem shared = item("333", ShippingStatus.PREPARING, null);

		Map<EmailAccountProperties.Account, Set<String>> plan = service.buildSearchPlan(
			List.of(shared), List.of(shared), List.of(account()));

		assertThat(plan.values().stream().flatMap(Set::stream).toList()).containsExactly("333");
	}

	@Test
	@DisplayName("fetchAndProcessEmails 는 실구매가 미기록 큐도 함께 조회한다")
	void fetchConsultsPurchaseAmountQueue() {
		when(properties.getAccounts()).thenReturn(List.of(account()));
		when(orderLineItemRepository.findIherbItemsNeedingEmailProcessing()).thenReturn(List.of());
		when(orderLineItemRepository.findIherbItemsNeedingPurchaseAmount()).thenReturn(List.of());

		service.fetchAndProcessEmails();

		verify(orderLineItemRepository).findIherbItemsNeedingPurchaseAmount();
	}

	@Test
	@DisplayName("확인메일 금액은 실구매가가 비어 있을 때 주입된다")
	void confirmationInjectsAmountWhenMissing() {
		OrderLineItem target = item("444", ShippingStatus.DELIVERED, null);
		when(orderLineItemRepository.findBySourcingData_SourcingOrderNo("444"))
			.thenReturn(List.of(target));

		service.processIherbConfirmation(OrderEmailParser.IherbConfirmationData.builder()
			.orderNo("444").totalAmount(new BigDecimal("45254")).build());

		verify(orderLineItemRepository).save(savedItemCaptor.capture());
		assertThat(savedItemCaptor.getValue().getSourcingData().getSourcingAmount())
			.isEqualByComparingTo(new BigDecimal("45254"));
	}

	@Test
	@DisplayName("달러 표기 주문은 설정 환율로 원화 근사값을 주입한다")
	void usdIsConvertedWithConfiguredRate() {
		OrderLineItem target = item("777", ShippingStatus.DELIVERED, null);
		when(orderLineItemRepository.findBySourcingData_SourcingOrderNo("777"))
			.thenReturn(List.of(target));
		when(properties.getUsdKrwRate()).thenReturn(new BigDecimal("1473"));

		service.processIherbConfirmation(OrderEmailParser.IherbConfirmationData.builder()
			.orderNo("777").totalAmount(new BigDecimal("48.00"))
			.currency(OrderEmailParser.USD).build());

		verify(orderLineItemRepository).save(savedItemCaptor.capture());
		// 48.00 × 1473 = 70,704 (실제 청구 70,743과 0.06% 차이)
		assertThat(savedItemCaptor.getValue().getSourcingData().getSourcingAmount())
			.isEqualByComparingTo(new BigDecimal("70704"));
	}

	@Test
	@DisplayName("환율이 설정되지 않으면 달러 주문을 주입하지 않는다")
	void usdWithoutRateIsSkipped() {
		OrderLineItem target = item("888", ShippingStatus.DELIVERED, null);
		when(orderLineItemRepository.findBySourcingData_SourcingOrderNo("888"))
			.thenReturn(List.of(target));
		when(properties.getUsdKrwRate()).thenReturn(null);

		service.processIherbConfirmation(OrderEmailParser.IherbConfirmationData.builder()
			.orderNo("888").totalAmount(new BigDecimal("48.00"))
			.currency(OrderEmailParser.USD).build());

		verify(orderLineItemRepository, never()).save(any(OrderLineItem.class));
	}

	@Test
	@DisplayName("원화 표기 주문은 환율을 적용하지 않는다")
	void krwIsInjectedAsIs() {
		OrderLineItem target = item("999", ShippingStatus.DELIVERED, null);
		when(orderLineItemRepository.findBySourcingData_SourcingOrderNo("999"))
			.thenReturn(List.of(target));

		service.processIherbConfirmation(OrderEmailParser.IherbConfirmationData.builder()
			.orderNo("999").totalAmount(new BigDecimal("45254"))
			.currency(OrderEmailParser.KRW).build());

		verify(orderLineItemRepository).save(savedItemCaptor.capture());
		assertThat(savedItemCaptor.getValue().getSourcingData().getSourcingAmount())
			.isEqualByComparingTo(new BigDecimal("45254"));
	}

	@Test
	@DisplayName("한 iHerb 주문이 여러 라인아이템에 걸리면 총액을 나눠 넣을 수 없어 주입하지 않는다")
	void confirmationSkipsMultiItemOrder() {
		OrderLineItem first = item("666", ShippingStatus.DELIVERED, null);
		OrderLineItem second = item("666", ShippingStatus.DELIVERED, null);
		when(orderLineItemRepository.findBySourcingData_SourcingOrderNo("666"))
			.thenReturn(List.of(first, second));

		service.processIherbConfirmation(OrderEmailParser.IherbConfirmationData.builder()
			.orderNo("666").totalAmount(new BigDecimal("45254")).build());

		// 총액을 양쪽에 넣으면 원가가 2배로 잡혀 순수익이 왜곡된다(sourcing_amount는 라인아이템별 합산).
		verify(orderLineItemRepository, never()).save(any(OrderLineItem.class));
	}

	@Test
	@DisplayName("이미 실구매가가 있으면 확인메일 금액으로 덮어쓰지 않는다")
	void confirmationDoesNotOverwriteExistingAmount() {
		OrderLineItem target = item("555", ShippingStatus.DELIVERED, new BigDecimal("70743"));
		when(orderLineItemRepository.findBySourcingData_SourcingOrderNo("555"))
			.thenReturn(List.of(target));

		service.processIherbConfirmation(OrderEmailParser.IherbConfirmationData.builder()
			.orderNo("555").totalAmount(new BigDecimal("48")).build());

		verify(orderLineItemRepository, never()).save(target);
	}
}
