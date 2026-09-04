package com.sbshop.agent.core.application.product.port;

public interface CoupangBrandLookupPort {
	BrandLookupOutcome findOfficialBrandName(String keyword);
}
