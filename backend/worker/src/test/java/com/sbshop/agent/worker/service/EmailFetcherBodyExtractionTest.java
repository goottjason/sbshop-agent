package com.sbshop.agent.worker.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import jakarta.mail.BodyPart;
import jakarta.mail.Message;
import jakarta.mail.Multipart;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.sbshop.agent.core.application.actionlog.ActionLogService;
import com.sbshop.agent.core.application.order.service.MarketplaceShippingService;
import com.sbshop.agent.core.config.EmailAccountProperties;
import com.sbshop.agent.core.domain.order.repository.OrderLineItemRepository;
import com.sbshop.agent.core.domain.order.repository.OrderRepository;

/**
 * D-112-2: HTML 단독 발송 확인메일에서 본문이 빈 문자열이 되어 금액을 못 읽던 결함.
 * text/html 파트를 버리면 확인메일을 찾고도 실구매가가 영구 누락된다.
 */
@ExtendWith(MockitoExtension.class)
class EmailFetcherBodyExtractionTest {

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

	@Test
	@DisplayName("HTML 단독 메일의 본문을 읽는다")
	void readsHtmlOnlyMessage() throws Exception {
		Message message = org.mockito.Mockito.mock(Message.class);
		when(message.isMimeType("text/plain")).thenReturn(false);
		when(message.isMimeType("text/html")).thenReturn(true);
		when(message.getContent()).thenReturn("<div>총 결제 금액 &#8361;45,254</div>");

		assertThat(service.getTextFromMessage(message)).contains("45,254");
	}

	@Test
	@DisplayName("multipart에 html 파트만 있어도 본문을 읽는다")
	void readsHtmlPartInMultipart() throws Exception {
		BodyPart htmlPart = org.mockito.Mockito.mock(BodyPart.class);
		when(htmlPart.isMimeType("text/plain")).thenReturn(false);
		when(htmlPart.isMimeType("text/html")).thenReturn(true);
		when(htmlPart.getContent()).thenReturn("<td>총 결제 금액</td><td>$48.00</td>");

		Multipart multipart = org.mockito.Mockito.mock(Multipart.class);
		when(multipart.getCount()).thenReturn(1);
		when(multipart.getBodyPart(0)).thenReturn(htmlPart);

		Message message = org.mockito.Mockito.mock(Message.class);
		when(message.isMimeType("text/plain")).thenReturn(false);
		when(message.isMimeType("text/html")).thenReturn(false);
		when(message.isMimeType("multipart/*")).thenReturn(true);
		when(message.getContent()).thenReturn(multipart);

		assertThat(service.getTextFromMessage(message)).contains("$48.00");
	}

	@Test
	@DisplayName("plain·html이 함께 있으면 plain이 앞에 온다(기존 동작 보존)")
	void plainPrecedesHtml() throws Exception {
		BodyPart plainPart = org.mockito.Mockito.mock(BodyPart.class);
		when(plainPart.isMimeType("text/plain")).thenReturn(true);
		when(plainPart.getContent()).thenReturn("총 결제 금액 ₩45,254");

		BodyPart htmlPart = org.mockito.Mockito.mock(BodyPart.class);
		when(htmlPart.isMimeType("text/plain")).thenReturn(false);
		when(htmlPart.isMimeType("text/html")).thenReturn(true);
		when(htmlPart.getContent()).thenReturn("<div>총 결제 금액 ₩99,999</div>");

		Multipart multipart = org.mockito.Mockito.mock(Multipart.class);
		when(multipart.getCount()).thenReturn(2);
		when(multipart.getBodyPart(0)).thenReturn(plainPart);
		when(multipart.getBodyPart(1)).thenReturn(htmlPart);

		Message message = org.mockito.Mockito.mock(Message.class);
		when(message.isMimeType("text/plain")).thenReturn(false);
		when(message.isMimeType("text/html")).thenReturn(false);
		when(message.isMimeType("multipart/*")).thenReturn(true);
		when(message.getContent()).thenReturn(multipart);

		String body = service.getTextFromMessage(message);
		assertThat(body.indexOf("45,254")).isLessThan(body.indexOf("99,999"));
	}

	@Test
	@DisplayName("HTML 단독 본문이 실제로 금액 파싱까지 이어진다")
	void htmlBodyParsesIntoAmount() {
		OrderEmailParser realParser = new OrderEmailParser();
		String htmlBody = "<html><body><table><tr><td>총 결제 금액</td>"
			+ "<td>&#8361;45,254</td></tr></table></body></html>";

		OrderEmailParser.IherbConfirmationData data = realParser
			.parseIherbConfirmation("iHerb 주문이 확인되었습니다 #123456789", htmlBody).get();

		assertThat(data.getTotalAmount()).isEqualByComparingTo(new java.math.BigDecimal("45254"));
	}
}
