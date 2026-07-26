package com.sbshop.agent.core.config;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 마켓 신규 등록에 필요한 <b>판매자 계정 고정값</b>.
 *
 * <p>출고지·반품지 코드, A/S 연락처처럼 상품마다 달라지지 않고 계정에 종속된 값들이다.
 * 기존 코드는 이걸 {@code CoupangProductPayload}에 하드코딩해 뒀는데
 * ({@code returnCenterCode("1000519746")}, {@code outboundShippingPlaceCode(1206157)}),
 * 마켓이 늘어날수록 흩어져서 바꿀 자리를 찾기 어려워진다. 여기 한곳으로 모은다.
 *
 * <p>미설정 값은 {@code MarketRequiredFieldValidator}가 "필수필드 미충족"으로 잡아
 * 검수 화면에 표시하고 등록을 막는다 — 빈 값으로 마켓에 보내 400을 받는 것보다 낫다.
 */
@Getter
@Component
public class MarketRegistrationDefaults {

	// --- 쿠팡 ---
	@Value("${market.coupang.outbound-shipping-place-code:}")
	private String coupangOutboundShippingPlaceCode;

	@Value("${market.coupang.return-center-code:}")
	private String coupangReturnCenterCode;

	@Value("${market.coupang.delivery-charge-on-return:15000}")
	private Integer coupangDeliveryChargeOnReturn;

	// --- 스마트스토어 ---
	/** 커머스API 주소록(addressbooks)의 출고지 ID. 미설정이면 자동 조회를 시도한다. */
	@Value("${market.smartstore.shipping-address-id:}")
	private String smartstoreShippingAddressId;

	@Value("${market.smartstore.return-address-id:}")
	private String smartstoreReturnAddressId;

	@Value("${market.smartstore.after-service-telephone:}")
	private String smartstoreAfterServiceTelephone;

	@Value("${market.smartstore.after-service-guide:구매대행 상품 문의는 판매자 문의하기를 이용해 주세요.}")
	private String smartstoreAfterServiceGuide;

	@Value("${market.smartstore.return-delivery-fee:6000}")
	private Integer smartstoreReturnDeliveryFee;

	@Value("${market.smartstore.exchange-delivery-fee:12000}")
	private Integer smartstoreExchangeDeliveryFee;

	/** 원산지 코드. 해외 기타(구매대행)는 상세설명 참조 처리하는 경우가 많다. */
	@Value("${market.smartstore.origin-area-code:0200037}")
	private String smartstoreOriginAreaCode;

	// --- 11번가 ---
	@Value("${market.elevenst.outbound-area-code:}")
	private String elevenstOutboundAreaCode;

	@Value("${market.elevenst.return-area-code:}")
	private String elevenstReturnAreaCode;

	/** 해외구매대행 구매처(사이트명). 11번가 해외구매대행 상품 필수. */
	@Value("${market.elevenst.abroad-buy-place:iHerb}")
	private String elevenstAbroadBuyPlace;

	// --- Cafe24 ---
	@Value("${market.cafe24.default-category-no:}")
	private String cafe24DefaultCategoryNo;

	// --- 공통 ---
	@Value("${market.common.origin:미국}")
	private String defaultOrigin;

	/** 판매자가 직접 확인해야 하는 미설정 항목 목록(운영 점검용). */
	public Map<String, String> unconfigured() {
		Map<String, String> missing = new LinkedHashMap<>();
		putIfBlank(missing, "market.coupang.outbound-shipping-place-code",
			coupangOutboundShippingPlaceCode);
		putIfBlank(missing, "market.coupang.return-center-code", coupangReturnCenterCode);
		putIfBlank(missing, "market.smartstore.shipping-address-id", smartstoreShippingAddressId);
		putIfBlank(missing, "market.smartstore.return-address-id", smartstoreReturnAddressId);
		putIfBlank(missing, "market.smartstore.after-service-telephone",
			smartstoreAfterServiceTelephone);
		putIfBlank(missing, "market.elevenst.outbound-area-code", elevenstOutboundAreaCode);
		putIfBlank(missing, "market.elevenst.return-area-code", elevenstReturnAreaCode);
		putIfBlank(missing, "market.cafe24.default-category-no", cafe24DefaultCategoryNo);
		return missing;
	}

	private void putIfBlank(Map<String, String> target, String key, String value) {
		if (value == null || value.isBlank())
			target.put(key, "미설정");
	}
}
