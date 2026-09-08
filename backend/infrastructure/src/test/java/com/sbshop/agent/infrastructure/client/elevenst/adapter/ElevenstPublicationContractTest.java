package com.sbshop.agent.infrastructure.client.elevenst.adapter;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sbshop.agent.core.domain.market.client.dto.MarketPublishContext;
import com.sbshop.agent.core.domain.product.Product;
import com.sbshop.agent.core.domain.product.enums.StockStatus;
import com.sbshop.agent.infrastructure.client.elevenst.component.ElevenstProductNotice;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ElevenstPublicationContractTest {
	final ElevenstPublicationContract contract = new ElevenstPublicationContract();
	final Product product = mock(Product.class);

	void setup() {
		when(product.getRevision()).thenReturn(8L);
		when(product.getSbCode()).thenReturn("SB123");
		when(product.getProductName()).thenReturn("검토 상품");
		when(product.getBrand()).thenReturn("브랜드");
		when(product.getDetailHtml()).thenReturn("<p>실제 상품 설명</p>");
		when(product.getHostedImages()).thenReturn(List.of("https://images.example.com/product.png"));
		when(product.getSalesQuantity()).thenReturn(300);
		when(product.getStockStatus()).thenReturn(StockStatus.IN_STOCK);
	}

	@Test
	void sourceMetadataKeepsInputsSeparateFromExecutablePublicationAndNeverInventsSellerValues() {
		setup();
		var result = contract.describe(product, null);
		assertThat(result).containsEntry("stage", "INPUT_ONLY").containsEntry("executable", false)
			.containsEntry("categoryVerified", false).containsEntry("categoryId", "");
		assertThat(result.get("fields").toString()).doesNotContain("makerNm", "1012345", "682132", "7000");
		assertThat(((Map<?, ?>)result.get("productFields")).get("salesQuantity")).isEqualTo(300);
		assertThat(result.get("limitations").toString()).contains("600×600", "SHA", "중복 허용");
		verify(product, never()).getStock();
	}

	@Test
	void explicitCompleteInputCanBeExportedButNeverBecomesExecutable() {
		setup();
		var result = contract.review(product, context(values(), notices()));
		assertThat(result).containsEntry("inputComplete", true).containsEntry("executable", false)
			.containsEntry("stage", "INPUT_ONLY").containsEntry("productRevision", 8L);
		assertThat((List<?>)result.get("issues")).isEmpty();
		var returned = (MarketPublishContext)result.get("context");
		assertThat(returned.extraFields()).containsEntry("elevenst", values());
		assertThat(returned.salePrice()).isNull();
		verify(product, never()).getStock();
	}

	@Test
	void emptyInputHasMissingValuesWithoutFabricatedOriginShippingCertificationOrNoticeDefaults() {
		setup();
		var result = contract.review(product, MarketPublishContext.empty());
		assertThat(result).containsEntry("inputComplete", false).containsEntry("executable", false);
		assertThat(result.get("issues").toString()).contains("국내 셀러", "원산지", "출고지 주소 코드", "고시 유형");
		assertThat(((MarketPublishContext)result.get("context")).noticeFields()).isEmpty();
		assertThat(((MarketPublishContext)result.get("context")).extraFields()).containsEntry("elevenst", Map.of());
	}

	@Test
	void conditionalOriginProcessedMaterialAndCertificationFieldsAreNotSilentlyOmitted() {
		setup();
		var values = values();
		values.put("orgnTypCd", "02");
		values.remove("orgnNmVal");
		values.put("rmaterialTypCd", "03");
		values.put("certTypeCd", "122");
		var result = contract.review(product, context(values, notices()));
		assertThat(result).containsEntry("inputComplete", false);
		assertThat(result.get("issues").toString()).contains("원산지 지역 코드", "ProductRmaterial.ingredNm", "인증번호");
	}

	@Test
	void malformedMoneyAddressesAndUnsupportedShippingAreReported() {
		setup();
		var values = values();
		values.put("rtngdDlvCst", "123.5");
		values.put("addrSeqIn", "-1");
		values.put("dlvClf", "03");
		var result = contract.review(product, context(values, notices()));
		assertThat(result).containsEntry("inputComplete", false);
		assertThat(result.get("issues").toString()).contains("10원 단위", "주소 코드", "점검 범위");
	}

	@Test
	void KoreanServiceAndReturnGuidesUseByteLimitAndUnencodableValuesAreNotReplaced() {
		setup();
		var values = values();
		values.put("asDetail", "가".repeat(2001));
		assertThat(contract.review(product, context(values, notices())).get("issues").toString()).contains("4000바이트");
		values.put("asDetail", "지원 😀");
		assertThatThrownBy(() -> contract.review(product, context(values, notices()))).hasMessageContaining("EUC-KR");
	}

	@ParameterizedTest
	@ValueSource(ints = {0, -1, 1000000})
	void zeroOrInvalidSalesQuantityIsNotAllowedForCreation(int quantity) {
		setup();
		when(product.getSalesQuantity()).thenReturn(quantity);
		assertThat(contract.review(product, context(values(), notices()))).containsEntry("inputComplete", false);
	}

	@Test
	void StockErrorsOutOfStockAndGoneProductsCannotCompleteInputCheck() {
		setup();
		when(product.getStockStatus()).thenReturn(StockStatus.OUT_OF_STOCK);
		assertThat(contract.review(product, context(values(), notices()))).containsEntry("inputComplete", false);
		when(product.getStockStatus()).thenReturn(StockStatus.IN_STOCK);
		when(product.getLastCrawlError()).thenReturn("조회 실패");
		assertThat(contract.review(product, context(values(), notices()))).containsEntry("inputComplete", false);
		when(product.getLastCrawlError()).thenReturn(null);
		when(product.isSourceGone()).thenReturn(true);
		assertThat(contract.review(product, context(values(), notices()))).containsEntry("inputComplete", false);
	}

	@Test
	void unsupportedFieldOrWrongTypedValuesAndUnknownNoticeAreRejectedWithoutPartialExport() {
		setup();
		var values = values();
		values.put("makerNm", "invented");
		assertThatThrownBy(() -> contract.review(product, context(values, notices()))).hasMessageContaining("원문에 연결하지 않은");
		var typed = new MarketPublishContext("123", null, null, List.of(), Map.of(), Map.of("elevenst", Map.of("rtngdDlvCst", 7000)));
		assertThatThrownBy(() -> contract.review(product, typed)).hasMessageContaining("문자열");
		var notices = notices();
		notices.put("999", "임의값");
		assertThatThrownBy(() -> contract.review(product, context(values(), notices))).hasMessageContaining("없는 항목");
	}

	@ParameterizedTest
	@ValueSource(strings = {"200", "210"})
	void documentedReceiptUsesProductNoAndRetainsExactCodeAndMessageWithoutConfirmingListing(String code) {
		var result = contract.parseCreationReceipt("<ClientMessage><resultCode>" + code
			+ "</resultCode><productNo>52844137</productNo><message>상품등록이 정상적으로 진행 되었습니다. 상품번호 : 52844137</message></ClientMessage>");
		assertThat(result.productNo()).isEqualTo("52844137");
		assertThat(result.resultCode()).isEqualTo(code);
		assertThat(result.message()).contains("52844137");
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"<AuthMessage><resultCode>200</resultCode><productNo>123</productNo><message>계정 없음</message></AuthMessage>",
		"<ClientMessage><resultCode>500</resultCode><productNo>123</productNo><message>실패</message></ClientMessage>",
		"<ClientMessage><resultCode>200</resultCode><prdNo>123</prdNo><message>완료</message></ClientMessage>",
		"<ClientMessage><resultCode>200</resultCode><productNo>0</productNo><message>완료</message></ClientMessage>",
		"<ClientMessage><resultCode>200</resultCode><productNo>123</productNo></ClientMessage>",
		"<ClientMessage><resultCode>200</resultCode><resultCode>500</resultCode><productNo>123</productNo><message>완료</message></ClientMessage>",
		"<ClientMessage><resultCode>200</resultCode><productNo>123</productNo><productNo>456</productNo><message>완료</message></ClientMessage>",
		"<ClientMessage><resultCode><value>200</value></resultCode><productNo>123</productNo><message>완료</message></ClientMessage>",
		"<!DOCTYPE ClientMessage [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><ClientMessage><resultCode>200</resultCode><productNo>123</productNo><message>&x;</message></ClientMessage>",
		"", "<html>200 OK</html>"})
	void unsafeAmbiguousAuthOrLegacyReceiptCannotBeAccepted(String xml) {
		assertThatThrownBy(() -> contract.parseCreationReceipt(xml)).isInstanceOf(IllegalArgumentException.class);
	}

	Map<String, String> values() {
		var out = new LinkedHashMap<String, String>();
		out.putAll(Map.of("sellerClassification", "DOMESTIC", "selMthdCd", "01", "prdTypCd", "01", "prdStatCd", "01",
			"minorSelCnYn", "Y", "suplDtyfrPrdClfCd", "01", "dlvClf", "02", "dlvWyCd", "01", "dlvCnAreaCd", "01", "dlvCstInstBasiCd", "01"));
		out.putAll(Map.of("dlvCstPayTypCd", "03", "bndlDlvCnYn", "N", "addrSeqOut", "5", "addrSeqIn", "3",
			"rtngdDlvCst", "5000", "exchDlvCst", "10000", "jejuDlvCst", "0", "islandDlvCst", "0", "asDetail", "연락 후 확인", "rtngExchDetail", "반품 전 문의"));
		out.putAll(Map.of("orgnTypCd", "03", "orgnNmVal", "사용자가 확인한 원산지", "ntShortNm", "US", "rmaterialTypCd", "04", "certTypeCd", "131", "noticeType", "891031"));
		return out;
	}

	Map<String, String> notices() {
		var out = new LinkedHashMap<String, String>();
		ElevenstProductNotice.specOf(ElevenstProductNotice.NoticeType.PROCESSED_FOOD).items()
			.forEach(i -> out.put(i.code(), "사용자가 확인한 실제 항목값"));
		return out;
	}

	MarketPublishContext context(Map<String, String> values, Map<String, String> notices) {
		return new MarketPublishContext("123", null, null, List.of(), notices, Map.of("elevenst", values));
	}
}
