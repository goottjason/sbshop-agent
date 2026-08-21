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
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class MarketDraftBuilder {
	private final MarketFeeService marketFeeService;
	private final MarginCalculator marginCalculator;
	private final ProductNoticeBuilder noticeBuilder;
	private final MarketRegistrationDefaults defaults;
	private final ObjectMapper objectMapper;
	private final Map<MarketType, MarketCategoryResolverPort> categoryResolvers = new EnumMap<>(MarketType.class);

	private final Map<MarketType, MarketAccountResourcePort> accountResources = new EnumMap<>(MarketType.class);

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

		if (category.isResolved() && !category.confident())
			missing = append(missing, "카테고리 확인 필요(자동 매칭 신뢰도 낮음)");

		marketDraft.applyValidation(toJson(missing), missing.isEmpty());
		return marketDraft;
	}

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
			log.warn("[초안생성] {} 카테고리 해석 실패: {}", marketType, e.getMessage());
			return MarketCategory.unresolved();
		}
	}

	private Map<String, Object> extraFields(MarketType marketType, ProductDraft draft) {
		Map<String, Object> extra = new LinkedHashMap<>();
		switch (marketType) {
			case COUPANG -> {
				putIfPresent(extra, "outboundShippingPlaceCode",
					defaults.getCoupangOutboundShippingPlaceCode());
				putIfPresent(extra, "returnCenterCode", defaults.getCoupangReturnCenterCode());
				extra.put("deliveryChargeOnReturn", defaults.getCoupangDeliveryChargeOnReturn());
				extra.put("originCountryCode", nz(draft.getOrigin(), defaults.getDefaultOrigin()));

				extra.put("maximumBuyForPerson", 5);
				extra.put("maximumBuyForPersonPeriod", 30);
			}
			case SMART_STORE -> {
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
				putIfPresent(extra, "addrSeqOut", defaults.getElevenstAddrSeqOut());
				putIfPresent(extra, "addrSeqIn", defaults.getElevenstAddrSeqIn());
				putIfPresent(extra, "outsideYnOut", defaults.getElevenstOutsideYnOut());
				putIfPresent(extra, "outsideYnIn", defaults.getElevenstOutsideYnIn());
				putIfPresent(extra, "dlvEtprsCd", defaults.getElevenstDeliveryCompanyCode());
				extra.put("rtngdDlvCst", defaults.getElevenstReturnDeliveryFee());
				extra.put("exchDlvCst", defaults.getElevenstExchangeDeliveryFee());
				putIfPresent(extra, "orgnTypDtlsCd", defaults.getElevenstOriginDetailCode());
				putIfPresent(extra, "abrdBuyPlace", defaults.getElevenstAbroadBuyPlace());
				extra.put("dlvCnAreaCd", "01");
				extra.put("abrdCntrCd", "US");
			}
			case CAFE24 -> {
				extra.put("originPlace", nz(draft.getOrigin(), defaults.getDefaultOrigin()));
			}
			default -> {}
		}
		return extra;
	}

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
		List<String> out = new ArrayList<>(list);
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
