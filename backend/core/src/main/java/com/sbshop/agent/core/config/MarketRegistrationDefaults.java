package com.sbshop.agent.core.config;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Getter
@Component
public class MarketRegistrationDefaults {

	@Value("${market.coupang.outbound-shipping-place-code:1206157}")
	private String coupangOutboundShippingPlaceCode;

	@Value("${market.coupang.return-center-code:1000519746}")
	private String coupangReturnCenterCode;

	@Value("${market.coupang.delivery-charge-on-return:15000}")
	private Integer coupangDeliveryChargeOnReturn;

	@Value("${market.smartstore.after-service-telephone:010-2597-2480}")
	private String smartstoreAfterServiceTelephone;

	@Value("${market.smartstore.after-service-guide:구매대행 상품 문의는 판매자 문의하기를 이용해 주세요.}")
	private String smartstoreAfterServiceGuide;

	@Value("${market.smartstore.return-delivery-fee:7000}")
	private Integer smartstoreReturnDeliveryFee;

	@Value("${market.smartstore.exchange-delivery-fee:14000}")
	private Integer smartstoreExchangeDeliveryFee;

	@Value("${market.smartstore.origin-area-code:0200037}")
	private String smartstoreOriginAreaCode;

	@Value("${market.elevenst.addr-seq-out:5}")
	private String elevenstAddrSeqOut;

	@Value("${market.elevenst.addr-seq-in:3}")
	private String elevenstAddrSeqIn;

	@Value("${market.elevenst.outside-yn-out:Y}")
	private String elevenstOutsideYnOut;

	@Value("${market.elevenst.outside-yn-in:N}")
	private String elevenstOutsideYnIn;

	@Value("${market.elevenst.delivery-company-code:00034}")
	private String elevenstDeliveryCompanyCode;

	@Value("${market.elevenst.return-delivery-fee:7000}")
	private Integer elevenstReturnDeliveryFee;

	@Value("${market.elevenst.exchange-delivery-fee:7000}")
	private Integer elevenstExchangeDeliveryFee;

	@Value("${market.elevenst.origin-detail-code:1405}")
	private String elevenstOriginDetailCode;

	@Value("${market.elevenst.abroad-buy-place:iHerb}")
	private String elevenstAbroadBuyPlace;

	@Value("${market.common.origin:미국}")
	private String defaultOrigin;

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
