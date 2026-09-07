package com.sbshop.agent.core.application.product.source;

import com.sbshop.agent.core.domain.product.enums.VendorType;

/** No product or market mutations. Unknown observations remain absent, never zero or fabricated stock counts. */
public interface ProductSourceObservationSource {
	ProductSourceData.Observed fetch(VendorType vendor, String sourceUrl);
}
