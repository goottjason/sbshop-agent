package com.sbshop.agent.infrastructure.client.sourcing.content;

import com.sbshop.agent.core.application.product.content.*;
import com.sbshop.agent.core.domain.product.enums.VendorType;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class VitabioticsProductContentSource implements ProductContentSource {
	private final VitabioticsCatalogClient catalog;
	private final IherbProductContentSource images;

	public VitabioticsProductContentSource(VitabioticsCatalogClient catalog, IherbProductContentSource images) {
		this.catalog = catalog;
		this.images = images;
	}

	@Override
	public Fetch fetch(String sourceUrl) {
		var product = catalog.fetch(sourceUrl);
		var data = product.data();
		// A product-wide gallery does not establish which additional photographs belong to a selected variant.
		if (data.path("variants").size() != 1)
			throw new ProductContentFailureException(ProductContentFailureException.Code.SOURCE_IMAGES_INVALID);
		var gallery = data.path("images");
		if (!gallery.isArray() || gallery.isEmpty() || gallery.size() > 8 || !data.path("featured_image").isTextual())
			throw new ProductContentFailureException(ProductContentFailureException.Code.SOURCE_IMAGES_INVALID);
		List<String> urls = new ArrayList<>();
		for (var value : gallery) {
			if (!value.isTextual())
				throw new ProductContentFailureException(ProductContentFailureException.Code.SOURCE_IMAGES_INVALID);
			urls.add(url(value.textValue()));
		}
		String representative = url(data.path("featured_image").textValue());
		if (new HashSet<>(urls).size() != urls.size() || !urls.contains(representative))
			throw new ProductContentFailureException(ProductContentFailureException.Code.SOURCE_IMAGES_INVALID);
		urls.remove(representative);
		urls.addFirst(representative);
		if (!data.path("description").isTextual())
			throw new ProductContentFailureException(ProductContentFailureException.Code.SOURCE_DETAILS_INVALID);
		return images.prepare(urls, data.path("description").textValue(),
			value -> ProductContentUrls.sourceImage(VendorType.VTB, value));
	}

	private String url(String value) {
		return ProductContentUrls.sourceImage(VendorType.VTB, value.startsWith("//") ? "https:" + value : value);
	}
}
