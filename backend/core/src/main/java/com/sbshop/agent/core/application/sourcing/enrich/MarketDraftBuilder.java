package com.sbshop.agent.core.application.sourcing.enrich;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbshop.agent.core.application.fee.MarketFeeService;
import com.sbshop.agent.core.application.sourcing.dto.MarketCategory;
import com.sbshop.agent.core.application.sourcing.port.MarketAccountResourcePort;
import com.sbshop.agent.core.application.sourcing.port.MarketCategoryResolverPort;
import com.sbshop.agent.core.config.MarketRegistrationDefaults;
import com.sbshop.agent.core.domain.order.enums.MarketType;
import com.sbshop.agent.core.domain.product.service.MarginCalculator;
import com.sbshop.agent.core.domain.sourcing.MarketDraft;
import com.sbshop.agent.core.domain.sourcing.ProductDraft;
import com.sbshop.agent.core.domain.sourcing.component.MarketProductRules;
import com.sbshop.agent.core.domain.sourcing.component.MarketRequiredFieldValidator;
import com.sbshop.agent.core.domain.sourcing.component.ProductNameComposer;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 마켓 1곳의 등록 초안을 만든다 — 상품명·카테고리·판매가·키워드·고시정보·마켓고유필드.
 *
 * <p>판매가는 <b>마켓별 실수수료</b>로 따로 산정한다(D-094와 같은 방식). 쿠팡 11%·스토어 8%·
 * 11번가 18%의 수수료 차이만큼 같은 마진을 남기려면 판매가가 달라야 한다.
 *
 * <p>만든 뒤 반드시 {@link MarketRequiredFieldValidator}로 검사해 미충족 항목을 남긴다 —
 * 검수 화면이 "무엇이 빠졌는지"를 보여주고 등록 버튼을 막는 근거가 된다.
 */
@Slf4j
@Component
public class MarketDraftBuilder {

	private final MarketFeeService marketFeeService;
	private final MarginCalculator marginCalculator;
	private final ProductNoticeBuilder noticeBuilder;
	private final MarketRegistrationDefaults defaults;
	private final ObjectMapper objectMapper;
	private final Map<MarketType, MarketCategoryResolverPort> categoryResolvers =
		new EnumMap<>(MarketType.class);
	/** 마켓 API로 자동 조회하는 계정 리소스(스토어 주소록 등). */
	private final Map<MarketType, MarketAccountResourcePort> accountResources =
		new EnumMap<>(MarketType.class);

	public MarketDraftBuilder(MarketFeeService marketFeeService, MarginCalculator marginCalculator,
		ProductNoticeBuilder noticeBuilder, MarketRegistrationDefaults defaults,
		ObjectMapper objectMapper, List<MarketCategoryResolverPort> resolvers,
		List<MarketAccountResourcePort> accountResourcePorts) {
		this.marketFeeService = marketFeeService;
		this.marginCalculator = marginCalculator;
		this.noticeBuilder = noticeBuilder;
		this.defaults = defaults;
		this.objectMapper = objectMapper;
		for (MarketCategoryResolverPort r : resolvers) {
			categoryResolvers.put(r.market(), r);
		}
		for (MarketAccountResourcePort a : accountResourcePorts) {
			accountResources.put(a.market(), a);
		}
	}

	public MarketDraft build(ProductDraft draft, MarketType marketType, String brandKo,
		List<String> keywordPool, String categoryHint, String categorySlug, BigDecimal couponRate) {

		MarketProductRules.Rules rules = MarketProductRules.of(marketType);

		String productName = ProductNameComposer.compose(
			brandKo != null ? brandKo : draft.getBrand(),
			draft.getBaseNameKo(), draft.getCapacity(),
			draft.getMeasureUnit() != null ? draft.getMeasureUnit().getDescription() : null,
			draft.getBundleQty() != null ? draft.getBundleQty() : 1,
			marketType);

		MarketCategory category = resolveCategory(marketType, categoryHint, productName, draft.getBrand());
		BigDecimal feeRate = marketFeeService.feeRate(marketType);
		BigDecimal salePrice = calculateSalePrice(draft, feeRate, couponRate);

		List<String> keywords = keywordPool.stream()
			.filter(k -> k.length() <= rules.keywordMaxLength())
			.limit(rules.maxKeywords())
			.toList();

		MarketDraft marketDraft = MarketDraft.builder()
			.marketType(marketType)
			.productName(productName)
			.categoryId(category.categoryId())
			.categoryPath(category.categoryPath())
			.salePrice(salePrice)
			.channelFeeRate(feeRate)
			.keywords(toJson(keywords))
			.noticeFields(toJson(noticeBuilder.build(draft, categorySlug)))
			.extraFields(toJson(extraFields(marketType, draft)))
			.build();

		List<String> missing = MarketRequiredFieldValidator.validate(draft, marketDraft);
		// 카테고리 자동 매칭에 확신이 없으면 값이 있어도 사람이 봐야 한다.
		if (category.isResolved() && !category.confident())
			missing = append(missing, "카테고리 확인 필요(자동 매칭 신뢰도 낮음)");

		marketDraft.applyValidation(toJson(missing), missing.isEmpty());
		return marketDraft;
	}

	/** 판매가 = 묶음 총매입가 기준, 마켓 실수수료를 divisor에 넣어 산정. */
	private BigDecimal calculateSalePrice(ProductDraft draft, BigDecimal feeRate, BigDecimal couponRate) {
		if (draft.getCostPrice() == null || draft.getCostPrice().signum() <= 0)
			return null;
		int bundleQty = draft.getBundleQty() != null ? draft.getBundleQty() : 1;
		BigDecimal marginRate = draft.getMarginRate() != null ? draft.getMarginRate() : new BigDecimal("20");
		return marginCalculator.calculateSalePrice(
			draft.getCostPrice(), bundleQty, marginRate, couponRate, null, feeRate);
	}

	private MarketCategory resolveCategory(MarketType marketType, String hint, String name, String brand) {
		MarketCategoryResolverPort resolver = categoryResolvers.get(marketType);
		if (resolver == null)
			return MarketCategory.unresolved();
		try {
			return resolver.resolve(hint, name, brand);
		} catch (Exception e) {
			// 카테고리 해석 실패로 초안 생성 전체를 실패시키지 않는다 — 미해결로 두고 사람이 고른다.
			log.warn("[초안생성] {} 카테고리 해석 실패: {}", marketType, e.getMessage());
			return MarketCategory.unresolved();
		}
	}

	/** 마켓 고유 필수필드 — 계정 고정값에서 채운다. 미설정이면 키를 넣지 않아 검증에서 걸린다. */
	private Map<String, Object> extraFields(MarketType marketType, ProductDraft draft) {
		Map<String, Object> extra = new LinkedHashMap<>();
		switch (marketType) {
			case COUPANG -> {
				putIfPresent(extra, "outboundShippingPlaceCode",
					defaults.getCoupangOutboundShippingPlaceCode());
				putIfPresent(extra, "returnCenterCode", defaults.getCoupangReturnCenterCode());
				extra.put("deliveryChargeOnReturn", defaults.getCoupangDeliveryChargeOnReturn());
				extra.put("originCountryCode", nz(draft.getOrigin(), defaults.getDefaultOrigin()));
				// 구매대행 상품은 1인당 구매수량을 제한해 통관 자가사용 기준 초과를 예방한다.
				extra.put("maximumBuyForPerson", 5);
				extra.put("maximumBuyForPersonPeriod", 30);
			}
			case SMART_STORE -> {
				// 출고지·반품지 주소록 ID는 커머스API로 자동 조회한다(계정마다 다르고 잘 바뀌지 않음).
				extra.putAll(accountResource(MarketType.SMART_STORE));
				putIfPresent(extra, "afterServiceTelephoneNumber",
					defaults.getSmartstoreAfterServiceTelephone());
				putIfPresent(extra, "afterServiceGuideContent", defaults.getSmartstoreAfterServiceGuide());
				extra.put("returnDeliveryFee", defaults.getSmartstoreReturnDeliveryFee());
				extra.put("exchangeDeliveryFee", defaults.getSmartstoreExchangeDeliveryFee());
				putIfPresent(extra, "originAreaCode", defaults.getSmartstoreOriginAreaCode());
				extra.put("minorPurchasable", true);
				extra.put("importer", "구매대행");
			}
			case ELEVEN_STREET -> {
				// 출고지·반품지는 '주소 시퀀스코드'(addrSeq*)다. dlvCnAreaCd는 배송가능지역(01=전국)이라
				// 필수 주소코드가 아니다 — 둘을 혼동하면 "출고지 주소를 확인해주세요" 오류가 난다(D-092).
				putIfPresent(extra, "addrSeqOut", defaults.getElevenstAddrSeqOut());
				putIfPresent(extra, "addrSeqIn", defaults.getElevenstAddrSeqIn());
				putIfPresent(extra, "outsideYnOut", defaults.getElevenstOutsideYnOut());
				putIfPresent(extra, "outsideYnIn", defaults.getElevenstOutsideYnIn());
				putIfPresent(extra, "dlvEtprsCd", defaults.getElevenstDeliveryCompanyCode());
				extra.put("rtngdDlvCst", defaults.getElevenstReturnDeliveryFee());
				extra.put("exchDlvCst", defaults.getElevenstExchangeDeliveryFee());
				putIfPresent(extra, "orgnTypDtlsCd", defaults.getElevenstOriginDetailCode());
				putIfPresent(extra, "abrdBuyPlace", defaults.getElevenstAbroadBuyPlace());
				extra.put("dlvCnAreaCd", "01");   // 배송가능지역: 전국
				extra.put("abrdCntrCd", "US");
			}
			case CAFE24 -> {
				// 진열 분류는 Cafe24CategoryResolver가 categoryId로 이미 채운다.
				extra.put("originPlace", nz(draft.getOrigin(), defaults.getDefaultOrigin()));
			}
			default -> { }
		}
		return extra;
	}

	/** 마켓 API 자동 조회 결과. 조회 실패는 빈 맵이고, 그러면 검증기가 미충족으로 잡는다. */
	private Map<String, Object> accountResource(MarketType marketType) {
		MarketAccountResourcePort port = accountResources.get(marketType);
		if (port == null)
			return Map.of();
		try {
			return new LinkedHashMap<>(port.resolve());
		} catch (Exception e) {
			log.warn("[초안생성] {} 계정 리소스 조회 실패: {}", marketType, e.getMessage());
			return Map.of();
		}
	}

	private void putIfPresent(Map<String, Object> target, String key, String value) {
		if (value != null && !value.isBlank())
			target.put(key, value);
	}

	private String nz(String v, String fallback) {
		return v != null && !v.isBlank() ? v : fallback;
	}

	private List<String> append(List<String> list, String item) {
		List<String> out = new java.util.ArrayList<>(list);
		out.add(item);
		return out;
	}

	private String toJson(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		} catch (Exception e) {
			log.warn("[초안생성] JSON 직렬화 실패: {}", e.getMessage());
			return "{}";
		}
	}
}
