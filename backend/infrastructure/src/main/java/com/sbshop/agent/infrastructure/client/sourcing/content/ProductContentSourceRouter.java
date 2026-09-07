package com.sbshop.agent.infrastructure.client.sourcing.content;

import com.sbshop.agent.core.application.product.content.*;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import java.net.URI;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Primary
@Component
public class ProductContentSourceRouter implements ProductContentSource {
	private final IherbProductContentSource ihb;
	private final VitabioticsProductContentSource vtb;
	private final SupplierCatalogClient catalog;

	public ProductContentSourceRouter(IherbProductContentSource ihb, VitabioticsProductContentSource vtb,
		SupplierCatalogClient catalog) {
		this.ihb = ihb;
		this.vtb = vtb;
		this.catalog = catalog;
	}

	@Override
	public Fetch fetch(String sourceUrl) {
		String host = URI.create(sourceUrl).getHost();
		if ("www.fortnumandmason.com".equals(host) || "www.costco.co.uk".equals(host) || "www.ocado.com".equals(host)) {
			VendorType vendor = "www.fortnumandmason.com".equals(host) ? VendorType.FTN
				: "www.ocado.com".equals(host) ? VendorType.OCD : VendorType.COK;
			var product = catalog.fetch(vendor, sourceUrl);
			return ihb.prepare(product.images(), product.html(),
				value -> ProductContentUrls.sourceImage(vendor, value));
		}
		if (URI.create(sourceUrl).getHost().endsWith("vitabiotics.com")) {
			ProductContentUrls.source(VendorType.VTB, sourceUrl);
			return vtb.fetch(sourceUrl);
		}
		return ihb.fetch(ProductContentUrls.source(sourceUrl));
	}
}
