package com.sbshop.agent.core.application.product.port;

import java.util.List;

public interface CoupangBrandLookupPort {
	BrandLookupOutcome findOfficialBrandName(String keyword);

	List<String> enrolledBrandNames();
}
