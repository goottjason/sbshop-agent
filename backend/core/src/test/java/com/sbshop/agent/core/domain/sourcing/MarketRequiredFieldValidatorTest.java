package com.sbshop.agent.core.domain.sourcing;

import static org.assertj.core.api.Assertions.assertThat;

import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.sourcing.component.MarketRequiredFieldValidator;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 마켓별 필수필드 검사 규칙을 고정한다.
 *
 * <p>특히 <b>키 이름</b>을 고정하는 것이 목적이다. 11번가 출고지는 주소 시퀀스코드
 * {@code addrSeqOut}인데 배송가능지역 코드 {@code dlvCnAreaCd}와 헷갈리기 쉽고,
 * 실제로 그 혼동 때문에 "출고지 주소를 확인해주세요"로 막힌 전례가 있다(D-092).
 * 검증기가 엉뚱한 키를 보면 <b>미충족을 통과로 판정</b>해 등록 시점에야 실패한다.
 */
class MarketRequiredFieldValidatorTest {

	private ProductDraft draft(String origin, String detailHtml, String hostedImages) {
		ProductDraft d = ProductDraft.builder()
			.baseNameKo("비타민D3 K2")
			.brand("California Gold Nutrition")
			.bundleQty(1)
			.costPrice(new BigDecimal("20000"))
			.origin(origin)
			.build();
		d.applyEnrichment(detailHtml, hostedImages, null);
		return d;
	}

	private ProductDraft fullDraft() {
		return draft("상세설명 참조", "<div>상세</div>", "[\"https://cdn/img.jpg\"]");
	}

	private MarketDraft marketDraft(MarketType type, String extraFields) {
		return MarketDraft.builder()
			.marketType(type)
			.productName("캘리포니아골드뉴트리션 비타민D3 K2 180정")
			.categoryId("73199")
			.salePrice(new BigDecimal("42300"))
			.keywords("[\"비타민D3\",\"유산균\"]")
			.noticeFields("{\"noticeType\":\"HEALTH_FUNCTIONAL_FOOD\"}")
			.extraFields(extraFields)
			.build();
	}

	@Test
	@DisplayName("11번가 출고지는 addrSeqOut/addrSeqIn으로 검사한다 — dlvCnAreaCd는 배송가능지역이라 충족 근거가 못 된다")
	void elevenstChecksAddressSequenceNotDeliveryArea() {
		// 배송가능지역만 채운 상태 — 실제로는 출고지 주소가 없어 11번가가 거절한다.
		List<String> missing = MarketRequiredFieldValidator.validate(fullDraft(),
			marketDraft(MarketType.ELEVEN_STREET,
				"{\"dlvCnAreaCd\":\"01\",\"abrdBuyPlace\":\"iHerb\",\"dlvEtprsCd\":\"00034\"}"));

		assertThat(missing).contains("출고지 주소코드", "반품지 주소코드");
	}

	@Test
	@DisplayName("11번가 주소 시퀀스코드가 있으면 통과한다")
	void elevenstPassesWithAddressSequence() {
		List<String> missing = MarketRequiredFieldValidator.validate(fullDraft(),
			marketDraft(MarketType.ELEVEN_STREET,
				"{\"addrSeqOut\":\"5\",\"addrSeqIn\":\"3\",\"dlvEtprsCd\":\"00034\","
					+ "\"abrdBuyPlace\":\"iHerb\",\"dlvCnAreaCd\":\"01\"}"));

		assertThat(missing).isEmpty();
	}

	@Test
	@DisplayName("스마트스토어는 A/S·주소록·배송비·원산지코드를 모두 요구한다")
	void smartstoreRequiresAccountFields() {
		List<String> missing = MarketRequiredFieldValidator.validate(fullDraft(),
			marketDraft(MarketType.SMART_STORE, "{}"));

		assertThat(missing).contains(
			"A/S 전화번호", "A/S 안내", "출고지 주소ID", "반품지 주소ID",
			"반품 배송비", "교환 배송비", "원산지 코드");
	}

	@Test
	@DisplayName("스마트스토어 계정 필드가 채워지면 통과한다")
	void smartstorePassesWhenAccountFieldsPresent() {
		List<String> missing = MarketRequiredFieldValidator.validate(fullDraft(),
			marketDraft(MarketType.SMART_STORE,
				"{\"afterServiceTelephoneNumber\":\"010-2597-2480\","
					+ "\"afterServiceGuideContent\":\"문의하기\","
					+ "\"shippingAddressId\":\"1001\",\"returnAddressId\":\"1002\","
					+ "\"returnDeliveryFee\":7000,\"exchangeDeliveryFee\":14000,"
					+ "\"originAreaCode\":\"0200037\"}"));

		assertThat(missing).isEmpty();
	}

	@Test
	@DisplayName("빈 문자열·null 값은 '있음'으로 세지 않는다")
	void emptyValuesAreNotSatisfied() {
		List<String> missing = MarketRequiredFieldValidator.validate(fullDraft(),
			marketDraft(MarketType.ELEVEN_STREET,
				"{\"addrSeqOut\":\"\",\"addrSeqIn\":null,\"dlvEtprsCd\":\"00034\","
					+ "\"abrdBuyPlace\":\"iHerb\"}"));

		assertThat(missing).contains("출고지 주소코드", "반품지 주소코드");
	}

	@Test
	@DisplayName("공통 필드(상품명·판매가·카테고리·상세·이미지)가 비면 마켓과 무관하게 걸린다")
	void commonFieldsAreCheckedForEveryMarket() {
		ProductDraft empty = draft(null, null, "[]");
		MarketDraft md = MarketDraft.builder()
			.marketType(MarketType.CAFE24)
			.productName(null)
			.categoryId(null)
			.salePrice(null)
			.build();

		List<String> missing = MarketRequiredFieldValidator.validate(empty, md);

		assertThat(missing).contains("상품명", "판매가", "카테고리", "상세설명", "대표이미지", "원산지");
	}

	@Test
	@DisplayName("쿠팡은 출고지·반품지 코드와 검색태그를 요구한다")
	void coupangRequiresShippingCodesAndTags() {
		List<String> missing = MarketRequiredFieldValidator.validate(fullDraft(),
			marketDraft(MarketType.COUPANG, "{}"));

		assertThat(missing).contains("출고지 코드", "반품지 코드");
		// 검색태그는 marketDraft()가 채워 두었으므로 걸리지 않아야 한다.
		assertThat(missing).doesNotContain("검색태그");
	}
}
