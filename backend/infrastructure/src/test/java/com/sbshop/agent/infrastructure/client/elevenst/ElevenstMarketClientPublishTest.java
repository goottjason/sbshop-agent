package com.sbshop.agent.infrastructure.client.elevenst;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sbshop.agent.core.domain.market.client.dto.MarketPublishContext;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.dto.ProductCreateCommand;
import com.sbshop.agent.core.domain.product.enums.MeasureUnit;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import com.sbshop.agent.infrastructure.client.elevenst.adapter.ElevenstMarketClient;
import com.sbshop.agent.infrastructure.client.elevenst.client.ElevenstMarketRestClient;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ElevenstMarketClientPublishTest {

	@Mock
	private ElevenstMarketRestClient restClient;

	private ElevenstMarketClient client;

	private static final String OK_RESPONSE = "<?xml version=\"1.0\" encoding=\"euc-kr\"?><Product><prdNo>3300123456</prdNo></Product>";

	@BeforeEach
	void setUp() {
		client = new ElevenstMarketClient(restClient);
	}

	private Product product() {
		ProductCreateCommand command = new ProductCreateCommand(
			"https://kr.iherb.com/pr/x/1", new BigDecimal("20000"), "비타민D3 K2",
			"Vitamin D3 K2", "California Gold Nutrition", "미국",
			new BigDecimal("60"), new BigDecimal("180"), MeasureUnit.EA,
			List.of("https://src/1.jpg"), List.of("https://cdn/1.jpg"),
			"<div>본문</div>", "보충제", true, 1, new BigDecimal("20"), VendorType.IHB, null);
		return Product.create("250726IHB001", command);
	}

	private MarketPublishContext context() {
		Map<String, Object> extra = new LinkedHashMap<>();
		extra.put("addrSeqOut", "5");
		extra.put("addrSeqIn", "3");
		extra.put("outsideYnOut", "Y");
		extra.put("outsideYnIn", "N");
		extra.put("dlvEtprsCd", "00034");
		extra.put("abrdBuyPlace", "iHerb");
		extra.put("rtngdDlvCst", 7000);
		extra.put("exchDlvCst", 7000);
		Map<String, String> notice = new LinkedHashMap<>();
		notice.put("productName", "비타민D3 K2");
		notice.put("ingredients", "비타민D3, 비타민K2");
		return new MarketPublishContext("1001234", "건강/의료 > 건강식품",
			new BigDecimal("46800"), List.of("비타민D3"), notice, extra);
	}

	private String capturePublishedXml() {
		when(restClient.post(eq("/rest/prodservices/product"), anyString())).thenReturn(OK_RESPONSE);
		client.publish(product(), context());
		ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
		verify(restClient).post(eq("/rest/prodservices/product"), captor.capture());
		return captor.getValue();
	}

	@Test
	@DisplayName("출고지·반품지가 주소 시퀀스코드로 나간다 (dlvCnAreaCd와 별개)")
	void sendsAddressSequenceCodes() {
		String xml = capturePublishedXml();

		assertThat(xml).contains("<addrSeqOut>5</addrSeqOut>");
		assertThat(xml).contains("<addrSeqIn>3</addrSeqIn>");
		assertThat(xml).contains("<outsideYnOut>Y</outsideYnOut>");
		assertThat(xml).contains("<outsideYnIn>N</outsideYnIn>");
		assertThat(xml).contains("<dlvCnAreaCd>01</dlvCnAreaCd>");
	}

	@Test
	@DisplayName("검수된 카테고리·판매가가 반영된다")
	void sendsReviewedCategoryAndPrice() {
		String xml = capturePublishedXml();

		assertThat(xml).contains("<dispCtgrNo>1001234</dispCtgrNo>");
		assertThat(xml).contains("<selPrc>46800</selPrc>");
	}

	@Test
	@DisplayName("해외구매대행 표기와 원산지가 들어간다")
	void sendsOverseasPurchaseAgentInfo() {
		String xml = capturePublishedXml();

		assertThat(xml).contains("abrdBuyPlace").contains("iHerb");
		assertThat(xml).contains("<abrdCntrCd>US</abrdCntrCd>");
		assertThat(xml).contains("<orgnTypCd>02</orgnTypCd>");
		assertThat(xml).contains("<orgnTypDtlsCd>1405</orgnTypDtlsCd>");
	}

	@Test
	@DisplayName("D-298: 옛 형식 고시 블록(pdNo/ProductNotificationItem)은 더 이상 내보내지 않는다 "
		+ "— 11번가 현행 규격은 type + item{code,name} 이다")
	void doesNotSendLegacyNotificationShape() {
		String xml = capturePublishedXml();

		assertThat(xml).doesNotContain("<pdNo>");
		assertThat(xml).doesNotContain("ProductNotificationItem");
		assertThat(xml).doesNotContain("<itemName>");
		assertThat(xml).doesNotContain("<itemValue>");
	}

	@Test
	@DisplayName("D-298 안전장치: 코드표 미확보 동안에는 고시 블록을 아예 안 싣는다 "
		+ "— 추측 코드로 만든 블록을 내보내는 것보다 낫다")
	void omitsNotificationWhileCodeTableUnresolved() {
		String xml = capturePublishedXml();

		assertThat(xml).doesNotContain("ProductNotification");
	}

	@Test
	@DisplayName("결함 A: 카테고리가 없으면 빈 dispCtgrNo 태그로 등록을 강행하지 않고 거부한다")
	void noCategory_rejectsPublishInsteadOfOmittingTag() {
		assertThatThrownBy(() -> client.publish(product(), MarketPublishContext.empty()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("dispCtgrNo");

		verify(restClient, org.mockito.Mockito.never()).post(anyString(), anyString());
	}

	@Test
	@DisplayName("prdNo가 없으면 가짜 ID를 만들지 않고 실패시킨다")
	void failsWhenProductNumberMissing() {
		when(restClient.post(eq("/rest/prodservices/product"), anyString()))
			.thenReturn("<Product><resultCode>500</resultCode>"
				+ "<resultMsg>출고지 주소를 확인해주세요</resultMsg></Product>");

		assertThatThrownBy(() -> client.publish(product(), context()))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("출고지 주소를 확인해주세요");
	}
}
