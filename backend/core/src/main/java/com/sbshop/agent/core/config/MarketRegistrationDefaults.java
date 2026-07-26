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
 * 기존 코드는 이걸 어댑터에 흩어 하드코딩해 뒀는데
 * ({@code CoupangProductPayload}의 {@code returnCenterCode("1000519746")},
 * {@code ElevenstMarketClient}의 {@code addrSeqOut=5}), 마켓이 늘수록 바꿀 자리를 찾기 어려워진다.
 * 여기 한곳으로 모으고 <b>기본값은 라이브에서 검증된 실계정 값</b>으로 둔다.
 *
 * <p>스마트스토어 주소록 ID와 Cafe24 분류번호는 여기 두지 않고 마켓 API로 자동 조회한다
 * ({@code MarketAccountResourcePort} / {@code Cafe24CategoryResolver}) —
 * 계정마다 다르고 화면에서 찾아 옮겨 적기 번거롭지만 한 번 정해지면 바뀌지 않으므로 조회+캐시가 맞다.
 * 자동 조회가 틀렸을 때를 대비해 설정으로 덮어쓸 수 있는 통로는 남겨 둔다.
 */
@Getter
@Component
public class MarketRegistrationDefaults {

	// --- 쿠팡 (기존 CoupangProductPayload 하드코딩값을 승계) ---

	@Value("${market.coupang.outbound-shipping-place-code:1206157}")
	private String coupangOutboundShippingPlaceCode;

	@Value("${market.coupang.return-center-code:1000519746}")
	private String coupangReturnCenterCode;

	@Value("${market.coupang.delivery-charge-on-return:15000}")
	private Integer coupangDeliveryChargeOnReturn;

	// --- 스마트스토어 ---
	// shippingAddressId / returnAddressId는 SmartstoreAddressBookResolver가 자동 조회한다.

	@Value("${market.smartstore.after-service-telephone:010-2597-2480}")
	private String smartstoreAfterServiceTelephone;

	@Value("${market.smartstore.after-service-guide:구매대행 상품 문의는 판매자 문의하기를 이용해 주세요.}")
	private String smartstoreAfterServiceGuide;

	@Value("${market.smartstore.return-delivery-fee:7000}")
	private Integer smartstoreReturnDeliveryFee;

	@Value("${market.smartstore.exchange-delivery-fee:14000}")
	private Integer smartstoreExchangeDeliveryFee;

	/** 원산지 코드(해외 기타). 실제 원산지는 상품별로 검수 화면에서 확정한다. */
	@Value("${market.smartstore.origin-area-code:0200037}")
	private String smartstoreOriginAreaCode;

	// --- 11번가 ---
	// D-092에서 라이브로 검증된 실계정 값(11번가 주소조회 결과):
	//   addrSeqOut=5(미국 출고지, outsideYnOut=Y) / addrSeqIn=3(국내 반품지, outsideYnIn=N)
	// ⚠️ dlvCnAreaCd는 '배송가능지역'(01=전국) 코드지 주소코드가 아니다 — 혼동 금지.

	@Value("${market.elevenst.addr-seq-out:5}")
	private String elevenstAddrSeqOut;

	@Value("${market.elevenst.addr-seq-in:3}")
	private String elevenstAddrSeqIn;

	@Value("${market.elevenst.outside-yn-out:Y}")
	private String elevenstOutsideYnOut;

	@Value("${market.elevenst.outside-yn-in:N}")
	private String elevenstOutsideYnIn;

	/** 발송택배사 — CJ대한통운. */
	@Value("${market.elevenst.delivery-company-code:00034}")
	private String elevenstDeliveryCompanyCode;

	@Value("${market.elevenst.return-delivery-fee:7000}")
	private Integer elevenstReturnDeliveryFee;

	@Value("${market.elevenst.exchange-delivery-fee:7000}")
	private Integer elevenstExchangeDeliveryFee;

	/** 원산지 상세코드(미국). D-092에서 검증된 값. */
	@Value("${market.elevenst.origin-detail-code:1405}")
	private String elevenstOriginDetailCode;

	/** 해외구매대행 구매처(사이트명). */
	@Value("${market.elevenst.abroad-buy-place:iHerb}")
	private String elevenstAbroadBuyPlace;

	// --- Cafe24 ---
	// 진열 분류번호는 Cafe24CategoryResolver가 쇼핑몰 분류 목록에서 자동 매칭한다.

	// --- 공통 ---

	@Value("${market.common.origin:미국}")
	private String defaultOrigin;

	/**
	 * 판매자가 직접 확인해야 하는 미설정 항목(운영 점검용).
	 *
	 * <p>자동 조회 대상(스토어 주소록·Cafe24 분류)은 여기 넣지 않는다 —
	 * 설정이 비어 있는 게 정상이고, 실제 결측 여부는 조회 결과로만 알 수 있다.
	 */
	public Map<String, String> unconfigured() {
		Map<String, String> missing = new LinkedHashMap<>();
		putIfBlank(missing, "market.coupang.outbound-shipping-place-code",
			coupangOutboundShippingPlaceCode);
		putIfBlank(missing, "market.coupang.return-center-code", coupangReturnCenterCode);
		putIfBlank(missing, "market.smartstore.after-service-telephone",
			smartstoreAfterServiceTelephone);
		putIfBlank(missing, "market.elevenst.addr-seq-out", elevenstAddrSeqOut);
		putIfBlank(missing, "market.elevenst.addr-seq-in", elevenstAddrSeqIn);
		return missing;
	}

	private void putIfBlank(Map<String, String> target, String key, String value) {
		if (value == null || value.isBlank())
			target.put(key, "미설정");
	}
}
